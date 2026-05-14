#!/usr/bin/env python3
"""
R08 Ring — QRing-vs-R08-Remote conflict verifier.

WHY THIS EXISTS
---------------
Our `Doc/02-hardware-and-protocol.md` and `app-project/.../R08Protocol.kt` are derived from
`com.ring.r08remote` (R08-Remote / 小猪遥控戒指), reverse-engineered against actual R08 hardware
and trusted as authoritative for R08. The QRing official app
(`refs/qring-new-version-protocol-2026-05-15.md`) is a SECONDARY source — it targets the broader
Colmi family and has no R08-specific touch awareness. It disagrees with R08-Remote in several
places; this script lets you confirm on a real R08 which interpretation the firmware honours,
**without committing those interpretations to production code** until you've verified.

`r08_probe.py` (the main probe) stays untouched. Run this when you have the ring in hand.

WHAT IT TESTS
-------------
Three phases, ordered from safest to most-dangerous:

  PHASE A: passive observation (no writes) — listens for new 0x73 sub-codes, the 0xA1
           accelerometer push, and the optional `charging` byte on `03` battery frames.

  PHASE B: additive QRing-discovered queries (safe, single 16-byte writes):
            • 0x3C  CMD_DEVICE_FUNCTION_SUPPORT     → 9-byte capability bitmap
            • 0x48  CMD_GET_STEP_TODAY              → 14-byte today's totals
            • 0x01  CMD_SET_DEVICE_TIME             → sync clock; required for history reads
            • 0x50 AA AA  CMD_ANTI_LOST_RATE        → QRing's "find device" cmd
            • 0x08 01     CMD_RE_BOOT               → QRing's "soft reboot" cmd
           Each is "send + wait + watch what the ring does + tell us y/n".

  PHASE C: contested R08-Remote commands (R08-Remote-derived behaviour we depend on but QRing
           interprets differently — gated by explicit confirmation prompts):
            • 0x06  R08-Remote says FIND_DEVICE  | QRing says CMD_MUTE
            • 0x10  R08-Remote says BLINK_TWICE  | QRing says CMD_BIND_SUCCESS
            • 0x0F  R08-Remote says SHUTDOWN     | QRing says TO_OTA   ⚠ recovery via web flasher

USAGE
-----
   python r08_verify_qring.py                                  # scan + run all phases
   python r08_verify_qring.py --mac AA:BB:CC:DD:EE:FF          # skip scan
   python r08_verify_qring.py --skip-c                         # don't test contested cmds
   python r08_verify_qring.py --record out.csv                 # log every notify frame to CSV

The output ends with a results table you can paste back so we know which interpretation R08
firmware honours. Each test asks you to type a one-letter answer:
   r = matches R08-Remote   q = matches QRing   ?  = unclear / unexpected behaviour

Power note: PHASE B + C send ≤ 10 writes total over a couple of minutes. Negligible vs the
baseline connection drain. Phase A is purely passive.
"""

from __future__ import annotations

import argparse
import asyncio
import csv
import sys
import time
from dataclasses import dataclass, field
from typing import Optional

from bleak import BleakClient, BleakScanner
from bleak.backends.device import BLEDevice

# Re-use the protocol constants from the main probe so we share the source of truth.
sys.path.insert(0, __file__.rsplit("/", 1)[0])
from r08_probe import (  # noqa: E402
    SERVICE_UUID, WRITE_CHAR, NOTIFY_CHAR, NAME_KEYWORDS,
    PREFIX_RING, SUB_GESTURE, SUB_TOUCH_STATUS, SUB_ACTIVITY,
    PREFIX_ACCEL, PREFIX_BATTERY, PREFIX_HEALTH, PREFIX_STEPS,
    G_NAMES,
    _cmd, _checksum,
    TOUCH_ENABLE, TOUCH_MODE, BATTERY_QUERY,
    FIND_DEVICE, BLINK_TWICE, SHUTDOWN,
    decode as r08remote_decode,
)


# ── QRing-discovered cmd builders ────────────────────────────────────────────────────────────────

DEVICE_CAPABILITY_QUERY = _cmd(0x3C)                       # → 0x3C 9-byte response (QRing source)
TODAY_SPORT_QUERY       = _cmd(0x48)                       # → 0x48 14-byte BE totals
FIND_DEVICE_QRING       = _cmd(0x50, bytes([0xAA, 0xAA]))  # QRing's find-device path
REBOOT_QRING            = _cmd(0x08, bytes([0x01]))        # QRing's soft reboot


def build_set_time_now(language: int = 0) -> bytes:
    """7-byte BCD payload `[yy-2000, MM, dd, hh, mm, ss, lang]`. Source: QRing `SetTimeReq.java`."""
    t = time.localtime()
    def bcd(v: int) -> int:
        v = max(0, min(99, v))
        return ((v // 10) << 4) | (v % 10)
    return _cmd(0x01, bytes([
        bcd(t.tm_year - 2000), bcd(t.tm_mon), bcd(t.tm_mday),
        bcd(t.tm_hour), bcd(t.tm_min), bcd(t.tm_sec),
        language & 0xFF,
    ]))


# ── QRing's 0x73 sync-trigger sub-code catalogue (for the decoder) ───────────────────────────────
#
# R08-Remote knows three 0x73 sub-codes: 2A (touch status), 2D (gesture), 12 (activity totals).
# QRing knows ~30 more under the same prefix. None of the sub-bytes overlap; both interpretations
# can be true simultaneously. Logging any non-R08-Remote 0x73 sub-code we see in the wild tells
# us whether R08 firmware emits the QRing-discovered events.
QRING_73_SUBS = {
    0x01: "new HR record",         0x02: "new BP record",      0x03: "new SpO2 record",
    0x04: "new step detail",       0x05: "new temperature",    0x07: "sport ended",
    0x0B: "(reserved)",            0x0C: "battery low",        0x0D: "new blood sugar",
    0x10: "target reached",        0x11: "step increment",
    0x25: "(muslim worship)",      0x27: "new temperature",    0x29: "ring game key",
    0x2B: "new HRV",               0x2C: "new pressure",
    0x30: "lover double-tap (!)",  0x31: "current HR push",    0x34: "alarm ring",
    0x37: "manual HR test",        0x38: "(muslim praise)",    0x3A: "medication reminder",
    0x3D: "temperature alarm",     0x3E: "G-sensor still tick",0x3F: "ECG connect state",
}


# ── Extended decoder ─────────────────────────────────────────────────────────────────────────────

@dataclass
class Decoded:
    kind: str
    detail: str
    interesting: bool = False   # flag for "this is something new we want to highlight"


def extended_decode(data: bytes) -> Decoded:
    """Like r08_probe.decode but adds QRing-discovered shapes. Doesn't replace the R08-Remote
    interpretations — augments them with notes when QRing predicts something more."""
    if not data:
        return Decoded("Unknown", "empty")
    b0 = data[0]
    b1 = data[1] if len(data) >= 2 else -1

    # 0x73 namespace: R08-Remote knows 2A/2D/12; flag anything else as a QRing sub-code observation.
    if b0 == PREFIX_RING and b1 == SUB_GESTURE and len(data) >= 3:
        return Decoded("Gesture[R08-Remote]", G_NAMES.get(data[2], f"0x{data[2]:02x}?"))
    if b0 == PREFIX_RING and b1 == SUB_TOUCH_STATUS and len(data) >= 3:
        return Decoded("TouchStatus[R08-Remote]", "enabled" if data[2] == 0 else "disabled")
    if b0 == PREFIX_RING and b1 == SUB_ACTIVITY and len(data) >= 11:
        steps = (data[2] << 16) | (data[3] << 8) | data[4]
        cal   = ((data[5] << 16) | (data[6] << 8) | data[7]) / 1000.0
        dist  = ((data[8] << 16) | (data[9] << 8) | data[10]) / 1000.0
        return Decoded("Activity[R08-Remote]", f"steps={steps} cal={cal:.3f} dist={dist:.3f}m")
    if b0 == PREFIX_RING and b1 >= 0:
        name = QRING_73_SUBS.get(b1, f"unknown-sub-0x{b1:02x}")
        return Decoded("0x73[QRing]", f"sub=0x{b1:02x} ({name}) payload={data[2:].hex(' ')}",
                       interesting=True)

    if b0 == PREFIX_ACCEL:
        return Decoded("Accel[R08-only]", f"len={len(data)} payload={data[1:].hex(' ')}",
                       interesting=True)

    if b0 == PREFIX_BATTERY and len(data) >= 2:
        # QRing claims byte [2] is `isCharging`. Flag the difference.
        if len(data) >= 3:
            return Decoded("Battery", f"{data[1]}%  charging={'yes' if data[2] != 0 else 'no'}",
                           interesting=True)
        return Decoded("Battery", f"{data[1]}%  (no charging byte present)")

    if b0 == PREFIX_HEALTH and len(data) >= 4:
        kind = {1: "HR", 3: "SpO2", 8: "stress"}.get(b1, f"kind=0x{b1:02x}")
        err = data[2] if len(data) >= 3 else 0
        value = data[3]
        if err == 1:
            return Decoded("Health", f"{kind} not-worn (errCode=1)", interesting=True)
        return Decoded("Health", f"{kind} val={value} err={err}")

    if b0 == PREFIX_STEPS and len(data) >= 3:
        s = data[1] | (data[2] << 8)
        return Decoded("Steps", f"{s}")

    # QRing-discovered prefixes
    if b0 == 0x3C:
        return Decoded("DeviceCapability[QRing]", f"len={len(data)} bits={data[1:].hex(' ')}",
                       interesting=True)
    if b0 == 0x48 and len(data) >= 14:
        def be3(o: int) -> int: return (data[o] << 16) | (data[o+1] << 8) | data[o+2]
        def be2(o: int) -> int: return (data[o] << 8) | data[o+1]
        return Decoded("TodaySport[QRing]",
            f"steps={be3(1)} running={be3(4)} cal={be3(7)} dist={be3(10)}m min={be2(13)}",
            interesting=True)
    if b0 == 0x01:
        # SetTime ACK — QRing's SetTimeRsp echoes capability bits.
        return Decoded("SetTimeAck[QRing]", f"payload={data[1:].hex(' ')}", interesting=True)
    if b0 == 0x50:
        return Decoded("FindDevice50[QRing]", f"payload={data[1:].hex(' ')}", interesting=True)
    if b0 == 0x08:
        return Decoded("Reboot08[QRing]", f"payload={data[1:].hex(' ')}", interesting=True)

    # High-bit error flag: QRing's frame format reserves [0]&0x80 = error.
    if b0 & 0x80:
        return Decoded("ErrorFlag[QRing]",
            f"err-cmd=0x{b0 & 0x7F:02x} payload={data[1:].hex(' ')}", interesting=True)

    return Decoded("Unknown", f"prefix=0x{b0:02x}{(' sub=0x%02x' % b1) if b1 >= 0 else ''}")


# ── Scan + connect (re-uses logic from r08_probe but kept local for self-containment) ───────────

async def find_ring(timeout: float = 8.0) -> Optional[BLEDevice]:
    print(f"⌕ Scanning for {timeout}s…")
    devices = await BleakScanner.discover(timeout=timeout, return_adv=True)
    for d, adv in devices.values():
        name = (d.name or adv.local_name or "")
        if any(k in name for k in NAME_KEYWORDS):
            print(f"  ✓ {name}  {d.address}  rssi={adv.rssi}dBm")
            return d
    print("  no R08-like device found.")
    return None


# ── Result accumulator ───────────────────────────────────────────────────────────────────────────

@dataclass
class Verdict:
    name: str
    r08remote_says: str
    qring_says: str
    user_answer: str = ""   # 'r' | 'q' | '?' | ''
    notes: str = ""

@dataclass
class Results:
    phase_a_notes: list[str] = field(default_factory=list)
    phase_b: list[Verdict] = field(default_factory=list)
    phase_c: list[Verdict] = field(default_factory=list)


# ── Helper: send a write, wait N seconds while logging notify frames ────────────────────────────

async def send_and_listen(
    client: BleakClient, label: str, packet: bytes,
    listen_s: float, notify_log: list, frame_log: Optional[list] = None,
) -> list[bytes]:
    """Send packet, then collect notify frames for listen_s seconds. Returns the frames received
    DURING the listen window (so the caller can grade what happened)."""
    start_idx = len(notify_log)
    print(f"\n→ {label}: {packet.hex(' ')}")
    try:
        await client.write_gatt_char(WRITE_CHAR, packet, response=False)
    except Exception as e:
        print(f"  ✗ write failed: {e}")
        return []
    await asyncio.sleep(listen_s)
    received = notify_log[start_idx:]
    if not received:
        print(f"  (no notify frames in {listen_s:.1f}s)")
    else:
        for ts, frame, dec in received:
            tag = "★ " if dec.interesting else "  "
            print(f"  {tag}[{ts:>6d}ms]  {frame.hex(' '):<48s}  {dec.kind:<24s}  {dec.detail}")
            if frame_log is not None:
                frame_log.append((ts, frame.hex(' '), dec.kind, dec.detail))
    return [f for _, f, _ in received]


# ── Interactive grader ──────────────────────────────────────────────────────────────────────────

async def ask_grade(label: str, r08remote: str, qring: str) -> tuple[str, str]:
    """Ask the user whether the observed behaviour matched R08-Remote or QRing.
    Returns (answer, notes). Answer is one of 'r', 'q', '?'. Notes is free text."""
    print(f"\nGrade [{label}]:")
    print(f"  R08-Remote says: {r08remote}")
    print(f"  QRing says:      {qring}")
    print(f"  What did the ring just do?")
    print(f"    r → matches R08-Remote     q → matches QRing")
    print(f"    ? → neither / unclear      (free-text after letter optional)")
    loop = asyncio.get_running_loop()
    line = await loop.run_in_executor(None, sys.stdin.readline)
    line = (line or "").strip()
    if not line:
        return "?", "(no answer)"
    letter = line[0].lower()
    if letter not in ("r", "q", "?"):
        return "?", line
    notes = line[1:].strip().lstrip(":").strip()
    return letter, notes


# ── Main verification loop ──────────────────────────────────────────────────────────────────────

async def run(args) -> int:
    if args.mac:
        addr = args.mac
    else:
        dev = await find_ring(args.scan_timeout)
        if dev is None: return 2
        addr = dev.address

    t0 = time.monotonic()
    notify_log: list[tuple[int, bytes, Decoded]] = []  # (ms_since_start, raw_bytes, decoded)
    frame_csv: list = []                                # only populated if --record

    def on_notify(_sender, data: bytearray):
        now = time.monotonic()
        ms = int((now - t0) * 1000)
        b = bytes(data)
        d = extended_decode(b)
        notify_log.append((ms, b, d))
        tag = "★ " if d.interesting else "  "
        print(f"  {tag}[{ms:>6d}ms]  {b.hex(' '):<48s}  {d.kind:<24s}  {d.detail}")
        if args.record:
            frame_csv.append((ms, b.hex(' '), d.kind, d.detail))

    print(f"⌕ Connecting to {addr}…")
    async with BleakClient(addr) as client:
        print("  ✓ connected")
        await client.start_notify(NOTIFY_CHAR, on_notify)
        print("  ✓ subscribed to notify char")

        # Init sequence — same as the main probe.
        await asyncio.sleep(0.80)
        await client.write_gatt_char(WRITE_CHAR, TOUCH_ENABLE, response=False)
        await asyncio.sleep(0.50)
        await client.write_gatt_char(WRITE_CHAR, TOUCH_MODE, response=False)
        await asyncio.sleep(1.50)

        results = Results()

        # ── PHASE A: passive observation ────────────────────────────────────────────────────
        if not args.skip_a:
            print("\n" + "=" * 72)
            print("PHASE A — Passive observation (15 s)")
            print("=" * 72)
            print("Wear the ring naturally. Don't tap it on purpose; we want to see what frames")
            print("the firmware pushes spontaneously (battery, accel, 0x73 sub-codes, etc.).")
            print("Move your hand a little so the IMU has something to report if it's pushing.")
            phase_a_start = len(notify_log)
            await asyncio.sleep(15.0)
            phase_a_frames = notify_log[phase_a_start:]

            # Auto-tabulate what we saw.
            seen_kinds = {}
            for _, _, d in phase_a_frames:
                seen_kinds[d.kind] = seen_kinds.get(d.kind, 0) + 1
            for kind, count in sorted(seen_kinds.items()):
                results.phase_a_notes.append(f"{kind}: {count} frame(s)")

            # Specific flags
            seen_0xa1 = any(f[0] == PREFIX_ACCEL for _, f, _ in phase_a_frames)
            seen_qring_73 = any(
                f[0] == PREFIX_RING and len(f) >= 2 and f[1] not in (SUB_GESTURE, SUB_TOUCH_STATUS, SUB_ACTIVITY)
                for _, f, _ in phase_a_frames
            )
            seen_battery_charging = any(
                f[0] == PREFIX_BATTERY and len(f) >= 3 for _, f, _ in phase_a_frames
            )

            results.phase_a_notes.append(f"0xA1 accelerometer push observed: {'YES' if seen_0xa1 else 'no'}")
            results.phase_a_notes.append(f"0x73 sub-codes outside R08-Remote set: {'YES' if seen_qring_73 else 'no'}")
            results.phase_a_notes.append(f"Battery frame has charging byte: {'YES' if seen_battery_charging else 'no'}")

        # ── PHASE B: additive QRing-discovered queries ──────────────────────────────────────
        if not args.skip_b:
            print("\n" + "=" * 72)
            print("PHASE B — Additive QRing queries (safe, single-write each)")
            print("=" * 72)

            await send_and_listen(client, "0x3C DEVICE_CAPABILITY_QUERY", DEVICE_CAPABILITY_QUERY,
                                  listen_s=2.5, notify_log=notify_log, frame_log=frame_csv)
            ans, notes = await ask_grade(
                "0x3C capability query",
                r08remote="(unknown — not in R08-Remote)",
                qring="responds with `3C ...` 9-byte capability bitmap",
            )
            results.phase_b.append(Verdict("0x3C cap query", "(silent / unknown)",
                                           "9-byte capability bitmap returned", ans, notes))

            await send_and_listen(client, "0x48 TODAY_SPORT_QUERY", TODAY_SPORT_QUERY,
                                  listen_s=2.5, notify_log=notify_log, frame_log=frame_csv)
            ans, notes = await ask_grade(
                "0x48 today-totals query",
                r08remote="(unknown — not in R08-Remote)",
                qring="responds with `48 ...` 14-byte BE totals (steps/run/cal/dist/min)",
            )
            results.phase_b.append(Verdict("0x48 today totals", "(silent / unknown)",
                                           "14-byte today totals returned", ans, notes))

            await send_and_listen(client, "0x01 SET_TIME", build_set_time_now(),
                                  listen_s=2.5, notify_log=notify_log, frame_log=frame_csv)
            ans, notes = await ask_grade(
                "0x01 set-time",
                r08remote="(unknown)",
                qring="ACK with `01 ...` (capability echo). Clock now in sync.",
            )
            results.phase_b.append(Verdict("0x01 set-time", "(unknown)",
                                           "01 ACK + capability echo", ans, notes))

            print("\nPress Enter when you're ready for the 0x50 AA AA find-device test")
            print("(QRing claims this vibrates + LED-blinks the ring):")
            loop = asyncio.get_running_loop()
            await loop.run_in_executor(None, sys.stdin.readline)
            await send_and_listen(client, "0x50 AA AA FIND_DEVICE (QRing)", FIND_DEVICE_QRING,
                                  listen_s=3.0, notify_log=notify_log, frame_log=frame_csv)
            ans, notes = await ask_grade(
                "0x50 AA AA",
                r08remote="(not used by R08-Remote)",
                qring="vibrates + LED blinks (find device)",
            )
            results.phase_b.append(Verdict("0x50 AA AA find-qring", "(unused)",
                                           "vibrate + LED blink", ans, notes))

            print("\nPress Enter for the 0x08 01 soft-reboot test (QRing's reboot cmd):")
            await loop.run_in_executor(None, sys.stdin.readline)
            print("⚠ The ring will disconnect briefly if reboot works. Reconnect afterward.")
            print("  Confirm with YES to proceed, anything else to skip:")
            confirm = await loop.run_in_executor(None, sys.stdin.readline)
            if confirm.strip() == "YES":
                await send_and_listen(client, "0x08 01 REBOOT (QRing)", REBOOT_QRING,
                                      listen_s=4.0, notify_log=notify_log, frame_log=frame_csv)
                ans, notes = await ask_grade(
                    "0x08 01",
                    r08remote="(not used)",
                    qring="soft reboot — ring disconnects + reconnects",
                )
                results.phase_b.append(Verdict("0x08 01 reboot", "(unused)",
                                               "soft reboot / disconnect", ans, notes))
            else:
                print("  (skipped)")

        # ── PHASE C: contested R08-Remote commands ──────────────────────────────────────────
        if not args.skip_c:
            print("\n" + "=" * 72)
            print("PHASE C — Contested R08-Remote commands (use with caution)")
            print("=" * 72)
            print("These cmds are in our production code with R08-Remote semantics. If R08")
            print("firmware actually matches QRing's interpretation, we have wrong behaviour")
            print("in `R08Protocol.kt`. The 0x0F test is gated behind an extra confirmation.")

            loop = asyncio.get_running_loop()

            print("\nPress Enter to send 0x06 (R08-Remote: find-device LED-blink ~10s | QRing: mute):")
            await loop.run_in_executor(None, sys.stdin.readline)
            await send_and_listen(client, "0x06 (contested)", FIND_DEVICE,
                                  listen_s=12.0, notify_log=notify_log, frame_log=frame_csv)
            print("  (watched 12 s — did the LED blink for ~10 s? Or did notifications mute?)")
            ans, notes = await ask_grade(
                "0x06",
                r08remote="LED blinks for ~10 seconds (find-device)",
                qring="ring goes into DnD mute mode (notifications suppressed)",
            )
            results.phase_c.append(Verdict("0x06", "LED blink ~10s",
                                           "DnD mute", ans, notes))

            print("\nPress Enter to send 0x10 (R08-Remote: blink-twice | QRing: bind-success ACK):")
            await loop.run_in_executor(None, sys.stdin.readline)
            await send_and_listen(client, "0x10 (contested)", BLINK_TWICE,
                                  listen_s=2.5, notify_log=notify_log, frame_log=frame_csv)
            ans, notes = await ask_grade(
                "0x10",
                r08remote="quick 2-blink LED",
                qring="silent / interpreted as bind-success (no visible action)",
            )
            results.phase_c.append(Verdict("0x10", "quick 2-blink", "silent ACK",
                                           ans, notes))

            print("\n" + "!" * 72)
            print("⚠  0x0F (R08-Remote: SHUTDOWN | QRing: TO_OTA bootloader mode)")
            print("!" * 72)
            print("If QRing is right, this puts the ring into OTA firmware-flasher mode.")
            print("Recovery requires the OTA web flasher at")
            print("    https://atc1441.github.io/ATC_RF03_Writer.html")
            print("plus an OTA-capable .bin (we have one in research/ATC_RF03_Ring/OTA_firmwares/).")
            print()
            print("If you're not 100 % comfortable risking that, type SKIP. Otherwise type YES:")
            confirm = await loop.run_in_executor(None, sys.stdin.readline)
            if confirm.strip() == "YES":
                await send_and_listen(client, "0x0F (DANGER — contested)", SHUTDOWN,
                                      listen_s=5.0, notify_log=notify_log, frame_log=frame_csv)
                print("\nObservation window — what happened?")
                print(" • Ring powered off cleanly? (R08-Remote interpretation)")
                print(" • Ring entered OTA mode (LED pattern changed, advertising different name)?")
                print(" • Nothing visible? (Maybe firmware silently dropped the cmd.)")
                ans, notes = await ask_grade(
                    "0x0F",
                    r08remote="ring powers off (requires re-cradle to wake)",
                    qring="ring enters OTA bootloader mode (visible LED pattern change, new BLE name)",
                )
                results.phase_c.append(Verdict("0x0F", "shutdown", "OTA mode", ans, notes))
            else:
                print("  (skipped 0x0F)")
                results.phase_c.append(Verdict("0x0F", "shutdown", "OTA mode", "?", "(skipped)"))

        try: await client.stop_notify(NOTIFY_CHAR)
        except Exception: pass

    # ── Final report ─────────────────────────────────────────────────────────────────────────
    print("\n" + "=" * 72)
    print("FINAL VERDICT — paste this back to the project")
    print("=" * 72)

    print("\n## Phase A (passive observation)")
    for note in results.phase_a_notes:
        print(f"  • {note}")

    print("\n## Phase B (additive QRing queries) — does R08 firmware honour these?")
    for v in results.phase_b:
        mark = {"r": "R08-Remote", "q": "QRing", "?": "unclear"}.get(v.user_answer, "(no answer)")
        print(f"  • {v.name:30s} → {mark}    {v.notes}")

    print("\n## Phase C (contested R08-Remote cmds) — which interpretation does R08 follow?")
    for v in results.phase_c:
        mark = {"r": "R08-Remote ✓", "q": "QRing ⚠", "?": "unclear"}.get(v.user_answer, "(no answer)")
        print(f"  • {v.name:30s} → {mark}    {v.notes}")

    print("\nFull notify-frame log: {} entries".format(len(notify_log)))
    if args.record and frame_csv:
        with open(args.record, "w", newline="") as fh:
            w = csv.writer(fh)
            w.writerow(["ms", "hex", "kind", "detail"])
            for row in frame_csv:
                w.writerow(row)
        print(f"CSV written to {args.record}")

    print("\nNext step: paste the verdict above so we know how to update R08Protocol.kt.")
    return 0


def main():
    p = argparse.ArgumentParser(
        description="R08 ring: verify QRing vs R08-Remote BLE protocol interpretations on real hardware",
    )
    p.add_argument("--mac", help="Skip scanning, connect directly to this MAC")
    p.add_argument("--scan-timeout", type=float, default=8.0)
    p.add_argument("--skip-a", action="store_true", help="Skip Phase A (passive observation)")
    p.add_argument("--skip-b", action="store_true", help="Skip Phase B (additive QRing queries)")
    p.add_argument("--skip-c", action="store_true", help="Skip Phase C (contested cmds; default-on)")
    p.add_argument("--record", help="Write every notify frame to this CSV path")
    args = p.parse_args()
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main() or 0)
