"""
R08 phase-0 — shared library.

Re-used by every r08_NN_*.py stage script. Keeps each stage script under ~200 lines and ensures
the BLE plumbing (scan / connect / decode / CSV) stays consistent across stages.

Nothing here is R08-firmware-verified yet — phase-0 IS the verification. This file just packages
the constants from QRing (primary) + 小猪 (R08-touch addendum) for the stage scripts to send.

Source-of-truth ordering: see Doc/02 §0.
  - QRing official (com.qcwireless.smart) — primary, ~70 cmds
  - 小猪遥控戒指 (com.ring.r08remote)    — R08 touch-IC path, 4 cmds
  - Heritage (R08-Dev.md): discarded
  - colmi_r02_client:       reference only
"""

from __future__ import annotations

import asyncio
import csv
import os
import sys
import time
from dataclasses import dataclass, field
from typing import Optional, Callable

from bleak import BleakClient, BleakScanner
from bleak.backends.device import BLEDevice

# Where each stage's verdict block also gets saved (alongside printing to terminal).
# Path is relative to where the script is run from; phase0/verdicts/ is the canonical home.
VERDICTS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "verdicts")


# ── GATT constants — Stage 0 confirms ─────────────────────────────────────────────────────────

SERVICE_UUID   = "6e40fff0-b5a3-f393-e0a9-e50e24dcca9e"
WRITE_CHAR     = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
NOTIFY_CHAR    = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
NAME_KEYWORDS  = ("R08", "R06", "Colmi", "COLMI")

CMD_DATA_LENGTH    = 16
FLAG_MASK_ERROR    = 0x80


# ── Frame builder ─────────────────────────────────────────────────────────────────────────────

def checksum(buf: bytearray) -> int:
    return sum(buf[:15]) & 0xFF


def cmd(code: int, payload: bytes = b"") -> bytes:
    """Build a 16-byte command: [0]=code, [1..]=payload zero-padded, [15]=checksum.
    Identical to both QRing's BaseReqCmd.java and 小猪's ProtocolConstants checksum scheme."""
    assert len(payload) <= 14, f"payload too long: {len(payload)}"
    out = bytearray(CMD_DATA_LENGTH)
    out[0] = code & 0xFF
    out[1 : 1 + len(payload)] = payload
    out[15] = checksum(out)
    return bytes(out)


# ── 小猪 (R08-touch-IC) commands — Stage 4 verifies ───────────────────────────────────────────

TOUCH_ENABLE  = cmd(0x3B, bytes([0x01, 0x00, 0x01, 0x01]))   # → 73 2A 00 echo expected
TOUCH_MODE    = cmd(0x3B, bytes([0x02, 0x00, 0x09, 0x01]))   # send ~500 ms after TOUCH_ENABLE
TOUCH_DISABLE = cmd(0x3B, bytes([0x01, 0x00, 0x01, 0x00]))


# ── QRing commands — Stages 1, 2, 5, 6, 8 verify ──────────────────────────────────────────────

BATTERY_QUERY        = cmd(0x03)                                          # both sources agree
GET_STEP_TODAY       = cmd(0x48)                                          # QRing: 14-byte BE response
DEVICE_FUNCTION_SUPP = cmd(0x3C)                                          # QRing: 9-byte capability bitmap
FIND_DEVICE_QRING    = cmd(0x50, bytes([0xAA, 0xAA]))                     # QRing: vibrate + LED
REBOOT_QRING         = cmd(0x08, bytes([0x01]))                           # QRing: soft reboot


def set_time_now_cmd(language: int = 0) -> bytes:
    """0x01 SetTime — BCD payload `[yy-2000, MM, dd, hh, mm, ss, lang]`. QRing `SetTimeReq.java`."""
    t = time.localtime()
    def bcd(v: int) -> int:
        v = max(0, min(99, v))
        return ((v // 10) << 4) | (v % 10)
    return cmd(0x01, bytes([
        bcd(t.tm_year - 2000), bcd(t.tm_mon), bcd(t.tm_mday),
        bcd(t.tm_hour), bcd(t.tm_min), bcd(t.tm_sec),
        language & 0xFF,
    ]))


def vitals_start(kind: int) -> bytes:
    """0x69 <kind> 01 — start a real-time vitals stream. kind: 1=HR, 2=BP, 3=SpO2, 8=stress, 10=HRV, 11=temp."""
    return cmd(0x69, bytes([kind, 0x01]))


def vitals_stop(kind: int, last_value: int = 0) -> bytes:
    """0x6A <kind> <last> 00 — stop. QRing passes the last sampled value back."""
    return cmd(0x6A, bytes([kind, last_value & 0xFF, 0x00]))


# Auto-monitor reads — payload `[1]` per QRing
def auto_monitor_read(cmd_code: int) -> bytes:
    return cmd(cmd_code, bytes([0x01]))


# Auto-monitor writes — payload starts with `[2]` per QRing
def auto_monitor_write(cmd_code: int, body: bytes) -> bytes:
    return cmd(cmd_code, bytes([0x02]) + body)


# History reads — Stage 8
def hr_history_read(midnight_unix_le: int) -> bytes:
    """0x15 + 4-byte LE midnight unix-time."""
    t = midnight_unix_le & 0xFFFFFFFF
    return cmd(0x15, bytes([t & 0xFF, (t >> 8) & 0xFF, (t >> 16) & 0xFF, (t >> 24) & 0xFF]))


def hrv_history_read(day_index: int = 0) -> bytes:
    """0x39 + 1-byte day index (0 = today)."""
    return cmd(0x39, bytes([day_index & 0xFF]))


def stress_history_read(midnight_unix_le: int) -> bytes:
    """0x37 + 4-byte LE time + [0, 50]."""
    t = midnight_unix_le & 0xFFFFFFFF
    return cmd(0x37, bytes([t & 0xFF, (t >> 8) & 0xFF, (t >> 16) & 0xFF, (t >> 24) & 0xFF, 0x00, 0x32]))


def step_history_read(day_off: int = 0, seg_lo: int = 0, seg_hi: int = 0x5F) -> bytes:
    """0x43 + [dayOff, 0x0F, segLo, segHi, 0x01]. day_off ≤ 29, seg_hi ≤ 95."""
    return cmd(0x43, bytes([day_off & 0xFF, 0x0F, seg_lo & 0xFF, seg_hi & 0xFF, 0x01]))


def sleep_history_read(day_off: int = 0, seg_lo: int = 0, seg_hi: int = 0x5F) -> bytes:
    """0x44 + [dayOff, segLo, segHi]."""
    return cmd(0x44, bytes([day_off & 0xFF, seg_lo & 0xFF, seg_hi & 0xFF]))


def today_midnight_unix() -> int:
    """Returns LE-encodable unix time for today 00:00 local — matches QRing's `HistoryReadHelper`."""
    t = time.localtime()
    midnight = time.mktime((t.tm_year, t.tm_mon, t.tm_mday, 0, 0, 0, 0, 0, -1))
    return int(midnight)


# ── 小猪 raw-frame prefixes (Stage 0, 3, 4 use these in decode) ───────────────────────────────

PREFIX_RING      = 0x73   # 's' — both R08-specific 73 2D/2A/12 AND QRing's wider 73 sub-codes
SUB_GESTURE      = 0x2D   # 73 2D <code> — R08 touch IC
SUB_TOUCH_STATUS = 0x2A   # 73 2A <0=enabled>
SUB_ACTIVITY     = 0x12   # 73 12 <11 bytes>
PREFIX_ACCEL     = 0xA1   # 16-byte fixed; 6-byte payload at [2..7] — undecoded
PREFIX_BATTERY   = 0x03   # 03 <level> [<charging>]
PREFIX_HEALTH    = 0x69   # 0x69 <kind> <errCode> <value> — stream tick
PREFIX_STEPS     = 0x51   # 51 <lo> <hi> ...

GESTURE_NAMES = {0x01: "SWIPE_UP", 0x02: "SWIPE_DOWN", 0x03: "TOUCH", 0x04: "LONG_PRESS"}

# QRing's full `0x73 <sub>` catalogue (HealthyFragment.java:367-705). Stage 3 catalogues which
# subset R08 emits.
QRING_73_SUBS = {
    0x01: "NEW_HR_RECORD",     0x02: "NEW_BP_RECORD",       0x03: "NEW_SPO2_RECORD",
    0x04: "NEW_STEP_DETAIL",   0x05: "NEW_TEMP_RECORD",     0x07: "SPORT_ENDED",
    0x0B: "(reserved)",        0x0C: "BATTERY_LOW",         0x0D: "NEW_BLOOD_SUGAR",
    0x10: "TARGET_REACHED",    0x11: "STEP_INCREMENT",      0x12: "ACTIVITY_TOTAL",
    0x25: "(muslim_worship)",  0x27: "NEW_TEMP",            0x29: "RING_GAME_KEY",
    0x2A: "TOUCH_STATUS",      0x2B: "NEW_HRV",             0x2C: "NEW_STRESS",
    0x2D: "GESTURE",           0x30: "LOVER_DOUBLE_TAP",    0x31: "CURRENT_HR_PUSH",
    0x32: "(muslim)",          0x33: "(muslim)",            0x34: "ALARM_RING",
    0x37: "MANUAL_HR_TEST",    0x38: "(muslim_praise)",     0x39: "MENSTRUATION",
    0x3A: "MEDICATION_REMIND", 0x3D: "TEMP_ALARM",          0x3E: "G_SENSOR_STILL_TICK",
    0x3F: "ECG_CONNECT_STATE",
}


# ── Generic frame decoder (returns string label + flag whether it's "interesting") ────────────

@dataclass
class Decoded:
    kind: str          # category for tabulation
    detail: str        # human-readable
    interesting: bool = False
    err_code: int = 0


def decode_frame(data: bytes) -> Decoded:
    if not data:
        return Decoded("Unknown", "empty")
    err_flag = (data[0] & FLAG_MASK_ERROR) != 0
    b0 = data[0] & 0x7F
    b1 = data[1] if len(data) >= 2 else -1
    if err_flag:
        return Decoded("ErrorFlag", f"err-cmd=0x{b0:02x} payload={data[1:].hex(' ')}", interesting=True)

    # 0x73 namespace
    if b0 == PREFIX_RING and b1 == SUB_GESTURE and len(data) >= 3:
        return Decoded("Gesture[R08]", GESTURE_NAMES.get(data[2], f"0x{data[2]:02x}?"))
    if b0 == PREFIX_RING and b1 == SUB_TOUCH_STATUS and len(data) >= 3:
        return Decoded("TouchStatus[R08]", "enabled" if data[2] == 0 else "disabled")
    if b0 == PREFIX_RING and b1 == SUB_ACTIVITY and len(data) >= 11:
        steps = (data[2] << 16) | (data[3] << 8) | data[4]
        cal   = ((data[5] << 16) | (data[6] << 8) | data[7]) / 1000.0
        dist  = ((data[8] << 16) | (data[9] << 8) | data[10]) / 1000.0
        return Decoded("Activity[R08]", f"steps={steps} cal={cal:.3f} dist={dist:.3f}km")
    if b0 == PREFIX_RING and b1 >= 0:
        # QRing sub-code namespace — anything not in the R08 subset is interesting
        name = QRING_73_SUBS.get(b1, f"unknown-sub-0x{b1:02x}")
        return Decoded("0x73[QRing]", f"sub=0x{b1:02x} ({name}) payload={data[2:].hex(' ')}",
                       interesting=True)

    if b0 == PREFIX_ACCEL:
        # 16-byte fixed; show payload [2..7]
        return Decoded("Accel", f"len={len(data)} payload[2..7]={data[2:8].hex(' ')}",
                       interesting=True)

    if b0 == PREFIX_BATTERY and len(data) >= 2:
        if len(data) >= 3:
            return Decoded("Battery", f"{data[1]}%  charging={'yes' if data[2] != 0 else 'no'}",
                           interesting=True)
        return Decoded("Battery", f"{data[1]}%  (no charging byte)")

    if b0 == PREFIX_HEALTH and len(data) >= 4:
        kind_map = {1: "HR", 2: "BP", 3: "SpO2", 4: "Fatigue", 7: "ECG", 8: "stress",
                    10: "HRV", 11: "Temp"}
        kind_name = kind_map.get(b1, f"kind=0x{b1:02x}")
        err = data[2] if len(data) >= 3 else 0
        val = data[3]
        detail = f"{kind_name} err={err} val={val}"
        if len(data) >= 6 and b1 == 2:
            detail += f" sbp={data[4]} dbp={data[5]}"
        return Decoded("Health", detail, interesting=(err == 1), err_code=err)

    if b0 == PREFIX_STEPS and len(data) >= 3:
        return Decoded("Steps", f"{data[1] | (data[2] << 8)}")

    # QRing's various command-echo prefixes
    qring_echo_names = {
        0x01: "SetTimeAck",       0x03: "Battery",
        0x15: "HRHistory",        0x16: "HRSettings",
        0x21: "TargetSetting",    0x29: "Orientation",
        0x2C: "SpO2Auto",         0x36: "StressAuto",
        0x37: "StressHistory",    0x38: "HRVAuto",
        0x39: "HRVHistory",       0x3B: "TouchControl",
        0x3C: "DeviceCapability",
        0x43: "StepHistory",      0x44: "SleepHistory",
        0x48: "TodaySport",
        0x50: "FindDevice",       0x69: "VitalsStart",  0x6A: "VitalsStop",
    }
    if b0 in qring_echo_names:
        return Decoded(qring_echo_names[b0], f"len={len(data)} payload={data[1:].hex(' ')}",
                       interesting=True)

    return Decoded("Unknown", f"prefix=0x{b0:02x}{(' sub=0x%02x' % b1) if b1 >= 0 else ''} hex={data.hex(' ')}")


# ── Scan + connect ────────────────────────────────────────────────────────────────────────────

async def find_ring(timeout: float = 8.0) -> Optional[BLEDevice]:
    print(f"⌕ Scanning for {timeout}s (filter: name keywords {NAME_KEYWORDS})…")
    devices = await BleakScanner.discover(timeout=timeout, return_adv=True)
    for d, adv in devices.values():
        name = (d.name or adv.local_name or "")
        if any(k in name for k in NAME_KEYWORDS):
            print(f"  ✓ {name}  {d.address}  rssi={adv.rssi}dBm")
            return d
    print("  no R08-like device found; scanned:")
    for d, adv in devices.values():
        print(f"    - {(d.name or adv.local_name or '<no-name>'):>20s}  {d.address}  rssi={adv.rssi}")
    return None


# ── Notify log accumulator (every stage uses this shape) ──────────────────────────────────────

@dataclass
class NotifyLog:
    t0: float = field(default_factory=time.monotonic)
    frames: list = field(default_factory=list)   # list[tuple(ms_since_start, raw_bytes, Decoded)]
    csv_path: Optional[str] = None
    csv_rows: list = field(default_factory=list)
    print_filter: Optional[Callable[[Decoded], bool]] = None

    def attach_handler(self, client: BleakClient):
        async def on_notify(_sender, data: bytearray):
            now = time.monotonic()
            ms = int((now - self.t0) * 1000)
            b = bytes(data)
            d = decode_frame(b)
            self.frames.append((ms, b, d))
            if self.csv_path:
                self.csv_rows.append((ms, b.hex(' '), d.kind, d.detail, d.err_code))
            if self.print_filter is None or self.print_filter(d):
                tag = "★ " if d.interesting else "  "
                print(f"  {tag}[{ms:>6d}ms]  {b.hex(' '):<48s}  {d.kind:<24s}  {d.detail}")
        return on_notify

    def flush_csv(self):
        if not self.csv_path:
            return
        with open(self.csv_path, "w", newline="") as fh:
            w = csv.writer(fh)
            w.writerow(["ms", "hex", "kind", "detail", "err_code"])
            for row in self.csv_rows:
                w.writerow(row)
        print(f"\nCSV → {self.csv_path}  ({len(self.csv_rows)} rows)")

    def tally(self) -> dict:
        t = {}
        for _, _, d in self.frames:
            t[d.kind] = t.get(d.kind, 0) + 1
        return t


# ── Common send-and-listen helper ─────────────────────────────────────────────────────────────

async def send_and_grade(
    client: BleakClient, label: str, packet: bytes, log: NotifyLog,
    listen_s: float = 2.5,
) -> list:
    """Send a packet, wait `listen_s` seconds, return the frames received during the window."""
    start_idx = len(log.frames)
    print(f"\n→ {label}: {packet.hex(' ')}")
    try:
        await client.write_gatt_char(WRITE_CHAR, packet, response=False)
    except Exception as e:
        print(f"  ✗ write failed: {e}")
        return []
    await asyncio.sleep(listen_s)
    return log.frames[start_idx:]


async def grade_prompt(
    label: str, expected_qring: str, expected_xiaozhu: str = "(not in xiaozhu)",
) -> tuple[str, str]:
    """Print expected outcomes per source, read one line of grading input from stdin.
    Returns (letter, notes). Letter ∈ {q, x, h, ?}."""
    print(f"\nGrade [{label}]:")
    print(f"  QRing expects:    {expected_qring}")
    print(f"  小猪 expects:     {expected_xiaozhu}")
    print(f"  What did the ring do?")
    print(f"    q → matches QRing       x → matches 小猪")
    print(f"    h → matches heritage    ? → neither / unclear")
    loop = asyncio.get_running_loop()
    line = await loop.run_in_executor(None, sys.stdin.readline)
    line = (line or "").strip()
    if not line:
        return "?", "(no answer)"
    letter = line[0].lower()
    if letter not in ("q", "x", "h", "?"):
        return "?", line
    return letter, line[1:].strip().lstrip(":").strip()


# ── Operator card (run at the top of every stage) ─────────────────────────────────────────────

def print_stage_card(
    stage_num: int, name: str,
    time_min: float, power_pct: str,
    pre_flight: list[str],
    during: list[str],
    success: str,
):
    """Big ascii banner shown at script start so the operator knows what to do."""
    bar = "─" * 72
    print()
    print("┌" + bar + "┐")
    print(f"│  STAGE {stage_num} — {name}".ljust(73) + "│")
    print(f"│  ≈ {time_min:.0f} min · ring battery cost: {power_pct}".ljust(73) + "│")
    print("├" + bar + "┤")
    print("│  📦 BEFORE YOU START".ljust(73) + "│")
    for line in pre_flight:
        print(f"│    • {line}".ljust(73) + "│")
    print("│".ljust(73) + "│")
    print("│  ▶ WHAT'LL HAPPEN".ljust(73) + "│")
    for line in during:
        print(f"│    • {line}".ljust(73) + "│")
    print("│".ljust(73) + "│")
    print("│  ✓ SUCCESS LOOKS LIKE".ljust(73) + "│")
    for line in success.split("\n"):
        print(f"│    {line}".ljust(73) + "│")
    print("│".ljust(73) + "│")
    print("│  📤 AFTER".ljust(73) + "│")
    print("│    • Verdict block prints at the end + auto-saves to".ljust(73) + "│")
    print(f"│      phase0/verdicts/stage_{stage_num:02d}_*.md".ljust(73) + "│")
    print("│    • Copy the lines between the scissor markers, paste back to chat".ljust(73) + "│")
    print("└" + bar + "┘")
    print()


# ── Verdict accumulator (consistent across stages) ────────────────────────────────────────────

@dataclass
class StageVerdict:
    name: str
    qring_says: str
    xiaozhu_says: str = "(n/a)"
    answer: str = ""        # q / x / h / ?
    notes: str = ""


def _verdict_markdown(stage_name: str, verdicts: list[StageVerdict],
                      passive_notes: list[str] | None) -> str:
    """Render the verdict block as markdown the user can paste back."""
    lines = []
    lines.append(f"## Stage {stage_name}")
    lines.append(f"_recorded {time.strftime('%Y-%m-%d %H:%M:%S')}_")
    lines.append("")
    if passive_notes:
        lines.append("### Passive observations")
        for n in passive_notes:
            lines.append(f"- {n}")
        lines.append("")
    if verdicts:
        lines.append("### Tests")
        for v in verdicts:
            mark = {"q": "QRing ✓", "x": "小猪 ✓", "h": "heritage ✓",
                    "?": "unclear"}.get(v.answer, "(no answer)")
            row = f"- **{v.name}** → {mark}"
            if v.notes:
                row += f" — {v.notes}"
            lines.append(row)
        lines.append("")
        # Auto PASS / FAIL summary
        pass_n = sum(1 for v in verdicts if v.answer in ("q", "x", "h"))
        total  = len(verdicts)
        if total > 0:
            verdict_word = "PASS" if pass_n == total else (
                "PARTIAL" if pass_n > 0 else "INCONCLUSIVE")
            lines.append(f"**Overall**: {verdict_word} ({pass_n}/{total} graded)")
    return "\n".join(lines)


def print_verdict_block(stage_name: str, verdicts: list[StageVerdict],
                        passive_notes: list[str] = None):
    """Pretty-print to terminal AND auto-save to phase0/verdicts/. Both contain the same body
    so the operator has two ways to share results (copy from terminal OR send the saved file)."""
    body = _verdict_markdown(stage_name, verdicts, passive_notes)

    # Terminal print with scissor markers
    scissor = "─ ✂ " * 14 + "─"
    print()
    print(scissor)
    print("PASTE TO CHAT — everything between the two scissor lines")
    print(scissor)
    print()
    print(body)
    print()
    print(scissor)
    print("END VERDICT BLOCK")
    print(scissor)

    # Auto-save to phase0/verdicts/
    try:
        os.makedirs(VERDICTS_DIR, exist_ok=True)
        # Filename: stage_NN_<slug>.md where slug is derived from stage_name
        # Examples: "0 — Sanity" → "00_sanity", "5 — Vitals stream (hr)" → "05_vitals_stream_hr"
        slug = stage_name.lower().replace("—", "").replace("(", "").replace(")", "")
        slug = "_".join(s for s in slug.split() if s)
        # Ensure leading two-digit stage number
        first_token = slug.split("_", 1)[0] if "_" in slug else slug
        if first_token.isdigit():
            slug = first_token.zfill(2) + ("_" + slug.split("_", 1)[1] if "_" in slug else "")
        fname = os.path.join(VERDICTS_DIR, f"stage_{slug}.md")
        with open(fname, "w") as fh:
            fh.write(body + "\n")
        print(f"\n💾 also saved to {fname}")
    except OSError as e:
        print(f"\n(could not save verdict file: {e})")
    print()
