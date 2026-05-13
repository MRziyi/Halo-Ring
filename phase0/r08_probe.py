#!/usr/bin/env python3
"""
R08 Ring — Phase-0 protocol probe.

What it does
------------
1. Scans for the R08 ring (filters by service UUID + name keywords).
2. Connects to it via BLE GATT.
3. Enables notifications on the TX characteristic.
4. Sends the initialisation sequence the official QRing app uses:
     +0ms   subscribe(NOTIFY_CHAR)
     +800ms write(TOUCH_ENABLE)
     +500ms write(TOUCH_MODE)
     +1500ms write(BATTERY_QUERY)
5. Decodes every notify frame and prints it with: timestamp, hex bytes, decoded interpretation.

What it's for
-------------
Phase-0 of the R08-→-AR-glasses remote project (see ../R08-Remote-Design.md §10, §18.7).
Run this with a ring in hand to verify that everything in §3 of the design doc actually matches
your specific R08 unit BEFORE we write any Android code that depends on those bytes.

Acceptance criteria (verbatim from the design doc):
  ✔ Scan finds a device named "R08_xxxx".
  ✔ Within ~800ms of subscribing+ENABLE, you see a `73 2A 00` frame (TouchStatus enabled).
  ✔ Tapping the ring once produces `73 2D 03`.
  ✔ Swiping forward / backward produces `73 2D 01` / `73 2D 02`.
  ✔ Long-pressing produces `73 2D 04`.
  ✔ BATTERY_QUERY produces an `03 <level%>` frame.
  ✔ Each gesture done ×10 in a row produces 10 frames (no drops). If you see drops, that's the
    "100ms-dedup vs human-double-tap" risk we noted in §20.3 — measure the inter-frame interval and
    decide the right dedup window for AndroidR08BleClient.

Optional modes
--------------
  --record <path>    Append every frame to a CSV: `t_ms,hex,decoded`. Use this to do the §10
                     verification (each gesture ×10, count the events, measure intervals).
  --no-init          Just connect & subscribe; don't send TOUCH_ENABLE/MODE. Use to test what the
                     ring sends by default.
  --interactive      After init, drop into a tiny REPL:
                        enable / disable / mode / battery / blink / shutdown / quit
  --tutorial         Guided gesture walkthrough — prints each of the 12 gestures from the user
                     manual in turn, waits for the user to perform it, and confirms detection.
                     Good for first-run verification and as an onboarding aid.
                     (See ../Doc/09-user-manual.md §4 for the gesture catalogue.)
  --mac <AA:BB:..>   Skip scanning, connect directly to this MAC.

Install:        pip install -r requirements.txt
Run (macOS):    python r08_probe.py
Run (Windows):  python r08_probe.py  (Bluetooth must be on; Win10+ is fine)
"""

from __future__ import annotations

import argparse
import asyncio
import csv
import os
import sys
import time
from dataclasses import dataclass
from typing import Optional

from bleak import BleakClient, BleakScanner
from bleak.backends.device import BLEDevice

# ── Protocol constants (mirrors :core/.../ble/R08Protocol.kt; SOURCE OF TRUTH ────────────────────
# is R08-Remote-Design.md §3, derived from the com.ring.r08remote APK reverse-engineering). ──────
SERVICE_UUID   = "6e40fff0-b5a3-f393-e0a9-e50e24dcca9e"
WRITE_CHAR     = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
NOTIFY_CHAR    = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
NAME_KEYWORDS  = ("R08", "R06", "Colmi", "COLMI")

# notify frame prefixes
PREFIX_RING        = 0x73   # 's' — ring-specific reports
SUB_GESTURE        = 0x2D   # 73 2D <code>
SUB_TOUCH_STATUS   = 0x2A   # 73 2A <0=enabled>
SUB_ACTIVITY       = 0x12   # 73 12 ...steps/cal/dist
PREFIX_ACCEL       = 0xA1   # accel raw (encoding unknown — action item)
PREFIX_BATTERY     = 0x03   # 03 <level%>
PREFIX_HEALTH      = 0x69   # 'i' — 69 <1=HR|3=SpO2|8=stress> ?? <value>
PREFIX_STEPS       = 0x51   # 51 <lo> <hi> ...

# gesture codes
G_NAMES = {0x01: "SWIPE_UP", 0x02: "SWIPE_DOWN", 0x03: "TOUCH", 0x04: "LONG_PRESS"}


def _checksum(buf: bytearray) -> int:
    return sum(buf[:15]) & 0xFF


def _cmd(code: int, payload: bytes = b"") -> bytes:
    """Build a 16-byte command: [0]=code, [1..]=payload zero-padded, [15]=checksum."""
    assert len(payload) <= 14
    out = bytearray(16)
    out[0] = code
    out[1 : 1 + len(payload)] = payload
    out[15] = _checksum(out)
    return bytes(out)


TOUCH_ENABLE  = _cmd(0x3B, bytes([0x01, 0x00, 0x01, 0x01]))   # 3B 01 00 01 01 ... 3E
TOUCH_MODE    = _cmd(0x3B, bytes([0x02, 0x00, 0x09, 0x01]))   # 3B 02 00 09 01 ... 47
TOUCH_DISABLE = _cmd(0x3B, bytes([0x01, 0x00, 0x01, 0x00]))   # 3B 01 00 01 00 ... 3D
BATTERY_QUERY = _cmd(0x03)                                     # 03 ... 03
FIND_DEVICE   = _cmd(0x06)                                     # 06 ... 06   (blink LED ~10s)
BLINK_TWICE   = _cmd(0x10)
SHUTDOWN      = _cmd(0x0F)


@dataclass
class Decoded:
    kind: str       # e.g. "Gesture", "Battery", "TouchStatus", "Health", "Activity", "Steps", "Accel", "Unknown"
    detail: str     # human-readable explanation


# ── Tutorial-mode gesture recogniser ─────────────────────────────────────────────────────────────
# A simplified, Python port of the design's GestureSynthesizer — just enough to confirm to the
# tutorial user that "yes, you did a DOUBLE_TAP_SWIPE_UP" etc. Defaults match
# ../app-project/core/.../gesture/Gestures.kt / DefaultProfiles.NAVIGATION.

MULTI_TAP_WINDOW_S = 0.280
COMBO_WINDOW_S = 0.300
LP_FOLLOWUP_WINDOW_S = 0.400

GESTURES_IN_TUTORIAL_ORDER = [
    ("TAP",                    "Tap the touch surface once.",                                     "Used for Confirm / Play-Pause."),
    ("DOUBLE_TAP",             "Tap twice quickly (within ~280 ms).",                             "Used for Back."),
    ("TRIPLE_TAP",             "Tap three times quickly.",                                        "System: cycle to the next profile."),
    ("QUADRUPLE_TAP",          "Tap four times quickly.",                                         "System: peek at the status HUD."),
    ("SWIPE_UP",               "Glide your finger forward along the touch surface.",              "Used for Previous / Move focus up."),
    ("SWIPE_DOWN",             "Glide your finger backward.",                                     "Used for Next / Move focus down."),
    ("LONG_PRESS",             "Press and hold for about half a second.",                         "Used for Menu (Navigation) / Back (Fast). Also wakes the screen when it's off."),
    ("DOUBLE_TAP_SWIPE_UP",    "Two quick taps, then a forward swipe within ~500 ms.",            "Used for Take Photo."),
    ("DOUBLE_TAP_SWIPE_DOWN",  "Two quick taps, then a backward swipe.",                          "Used for Ask Visual AI / Open Translate."),
    ("LONG_PRESS_SWIPE_UP",    "Long-press, then a forward swipe within ~400 ms.",                "Used for Notifications / Volume modal."),
    ("LONG_PRESS_SWIPE_DOWN",  "Long-press, then a backward swipe — the screen-off gesture.",     "System: SLEEP the screen (the most-important deliberate gesture)."),
    ("DOUBLE_LONG_PRESS",      "Two long-presses within ~400 ms of each other.",                  "System: force-reconnect the ring."),
]


class TutorialRecogniser:
    """Stateful recogniser. Feed every (raw, t) and ask `latest()` for the most recent fully-formed
    gesture. Resets via `consume()`. Single-threaded; called from one asyncio task."""

    def __init__(self):
        self.tap_count = 0
        self.last_tap_t = -1.0
        self.in_combo = False             # awaiting swipe after double-tap
        self.combo_until = 0.0
        self.in_lp_followup = False       # awaiting swipe / 2nd LP after long-press
        self.lp_followup_until = 0.0
        self._committed: list[str] = []   # ring buffer of recently-emitted gestures

    def latest(self) -> str | None:
        return self._committed[-1] if self._committed else None

    def consume(self):
        self._committed.clear()

    def feed(self, raw: str, t: float) -> str | None:
        """Returns the emitted gesture name if one was *committed* by this event, else None.
        Note: TAP / DOUBLE_TAP / TRIPLE_TAP / QUADRUPLE_TAP commit on window expiry, not on the
        last touch — callers should also call `tick(t)` from a steady loop to drive the timers."""
        # Expire stale windows first
        self._expire(t)

        if raw == "LONG_PRESS":
            if self.in_lp_followup:
                self.in_lp_followup = False
                return self._emit("DOUBLE_LONG_PRESS")
            self._flush_pending_tap()
            self.in_lp_followup = True
            self.lp_followup_until = t + LP_FOLLOWUP_WINDOW_S
            return None
        if raw in ("SWIPE_UP", "SWIPE_DOWN"):
            if self.in_lp_followup:
                self.in_lp_followup = False
                return self._emit("LONG_PRESS_SWIPE_UP" if raw == "SWIPE_UP" else "LONG_PRESS_SWIPE_DOWN")
            if self.in_combo:
                self.in_combo = False
                self.tap_count = 0
                return self._emit("DOUBLE_TAP_SWIPE_UP" if raw == "SWIPE_UP" else "DOUBLE_TAP_SWIPE_DOWN")
            self._flush_pending_tap()
            return self._emit(raw)
        if raw == "TOUCH":
            # A TOUCH during an LP follow-up commits the bare LP first.
            if self.in_lp_followup:
                self.in_lp_followup = False
                self._emit("LONG_PRESS")
            if self.last_tap_t > 0 and t - self.last_tap_t < MULTI_TAP_WINDOW_S:
                self.tap_count = min(self.tap_count + 1, 4)
            else:
                self.tap_count = 1
            self.last_tap_t = t
            if self.tap_count == 2:
                self.in_combo = True
                self.combo_until = t + COMBO_WINDOW_S
            elif self.tap_count >= 3:
                self.in_combo = False
            return None
        return None

    def tick(self, t: float) -> str | None:
        """Drive the timers. Call this periodically (e.g. every 50ms while waiting)."""
        emitted = self._expire(t)
        return emitted

    def _expire(self, t: float) -> str | None:
        # Tap window: expires `MULTI_TAP_WINDOW_S` after the last tap if no follow-up.
        if self.tap_count > 0 and t - self.last_tap_t >= MULTI_TAP_WINDOW_S and not self.in_combo:
            n = self.tap_count
            self.tap_count = 0
            if n == 1: return self._emit("TAP")
            if n == 2: return self._emit("DOUBLE_TAP")
            if n == 3: return self._emit("TRIPLE_TAP")
            if n >= 4: return self._emit("QUADRUPLE_TAP")
        # Combo window: if double-tap timed out without a swipe, commit DOUBLE_TAP.
        if self.in_combo and t >= self.combo_until:
            self.in_combo = False
            self.tap_count = 0
            return self._emit("DOUBLE_TAP")
        # LP follow-up: if no swipe / 2nd LP, commit bare LONG_PRESS.
        if self.in_lp_followup and t >= self.lp_followup_until:
            self.in_lp_followup = False
            return self._emit("LONG_PRESS")
        return None

    def _flush_pending_tap(self):
        if self.tap_count == 1:
            self._emit("TAP")
        elif self.tap_count == 2 and not self.in_combo:
            self._emit("DOUBLE_TAP")
        elif self.tap_count >= 3:
            self._emit("TRIPLE_TAP" if self.tap_count == 3 else "QUADRUPLE_TAP")
        self.tap_count = 0

    def _emit(self, name: str) -> str:
        self._committed.append(name)
        return name


def decode(data: bytes) -> Decoded:
    if not data:
        return Decoded("Unknown", "empty")
    b0 = data[0]
    b1 = data[1] if len(data) >= 2 else -1
    if b0 == PREFIX_RING and b1 == SUB_GESTURE and len(data) >= 3:
        return Decoded("Gesture", G_NAMES.get(data[2], f"0x{data[2]:02x}?"))
    if b0 == PREFIX_RING and b1 == SUB_TOUCH_STATUS and len(data) >= 3:
        return Decoded("TouchStatus", "enabled" if data[2] == 0 else "disabled")
    if b0 == PREFIX_RING and b1 == SUB_ACTIVITY and len(data) >= 11:
        steps = (data[2] << 16) | (data[3] << 8) | data[4]
        cal   = ((data[5] << 16) | (data[6] << 8) | data[7]) / 1000.0
        dist  = ((data[8] << 16) | (data[9] << 8) | data[10]) / 1000.0
        return Decoded("Activity", f"steps={steps} cal={cal:.3f} dist={dist:.3f}m")
    if b0 == PREFIX_ACCEL:
        return Decoded("Accel", f"len={len(data)} payload={data[1:].hex(' ')}  (encoding TBD — see §20.1)")
    if b0 == PREFIX_BATTERY and len(data) >= 2:
        return Decoded("Battery", f"{data[1]}%")
    if b0 == PREFIX_HEALTH and len(data) >= 4:
        kind = {1: "HR", 3: "SpO2", 8: "stress"}.get(b1)
        if kind and data[3] > 0:
            return Decoded("Health", f"{kind}={data[3]}")
    if b0 == PREFIX_STEPS and len(data) >= 3:
        s = data[1] | (data[2] << 8)
        return Decoded("Steps", f"{s}")
    return Decoded("Unknown", f"prefix=0x{b0:02x}{(' sub=0x%02x' % b1) if b1 >= 0 else ''}")


# ── Scanning & connection ─────────────────────────────────────────────────────────────────────────

async def find_ring(timeout: float = 8.0) -> Optional[BLEDevice]:
    print(f"⌕ Scanning for {timeout}s (filter: service UUID + name keywords)…")
    # bleak doesn't expose a service-UUID scan filter portably, so post-filter by name.
    devices = await BleakScanner.discover(timeout=timeout, return_adv=True)
    for d, adv in devices.values():
        name = (d.name or adv.local_name or "")
        if any(k in name for k in NAME_KEYWORDS):
            print(f"  ✓ {name}  {d.address}  rssi={adv.rssi}dBm")
            return d
        # Some platforms (macOS) hide MAC; print everything so the user can spot their ring
    print("  no R08-like device found; scanned:")
    for d, adv in devices.values():
        print(f"    - {(d.name or adv.local_name or '<no-name>'):>20s}  {d.address}  rssi={adv.rssi}")
    return None


# ── Main probe ───────────────────────────────────────────────────────────────────────────────────

async def run(args) -> int:
    if args.mac:
        addr = args.mac
        print(f"⌕ Connecting directly to {addr}…")
    else:
        dev = await find_ring(args.scan_timeout)
        if dev is None:
            return 2
        addr = dev.address

    writer: Optional[csv.writer] = None
    csv_fh = None
    if args.record:
        csv_fh = open(args.record, "w", newline="")
        writer = csv.writer(csv_fh)
        writer.writerow(["t_ms_since_start", "hex", "kind", "detail"])

    t0 = time.monotonic()
    seen = {"count": 0, "last_bytes": b"", "last_t": -1.0}

    def on_notify(_sender, data: bytearray):
        now = time.monotonic()
        ms = int((now - t0) * 1000)
        b = bytes(data)
        dt = (now - seen["last_t"]) * 1000 if seen["last_t"] > 0 else -1
        dup = b == seen["last_bytes"] and 0 <= dt < 100
        seen["last_bytes"] = b; seen["last_t"] = now; seen["count"] += 1
        d = decode(b)
        marker = "DUP" if dup else "   "
        print(f"  [{ms:>7d}ms] {marker}  {b.hex(' '):<48s}  {d.kind:<12s}  {d.detail}")
        if writer:
            writer.writerow([ms, b.hex(" "), d.kind, d.detail])
            csv_fh.flush()

    print(f"⌕ Connecting to {addr}…")
    async with BleakClient(addr) as client:
        print("  ✓ connected")
        # Quick sanity: list services + confirm ours
        svcs = list(client.services)
        ours = next((s for s in svcs if s.uuid.lower() == SERVICE_UUID), None)
        if ours is None:
            print(f"  ✗ service {SERVICE_UUID} not found! services seen:")
            for s in svcs: print(f"      {s.uuid}")
            return 3
        print(f"  ✓ found service {SERVICE_UUID}  (chars: {[c.uuid for c in ours.characteristics]})")

        await client.start_notify(NOTIFY_CHAR, on_notify)
        print("  ✓ subscribed to notify char\n")

        if not args.no_init:
            await asyncio.sleep(0.80)
            print(f"→ TOUCH_ENABLE      {TOUCH_ENABLE.hex(' ')}")
            await client.write_gatt_char(WRITE_CHAR, TOUCH_ENABLE, response=False)

            await asyncio.sleep(0.50)
            print(f"→ TOUCH_MODE        {TOUCH_MODE.hex(' ')}")
            await client.write_gatt_char(WRITE_CHAR, TOUCH_MODE, response=False)

            await asyncio.sleep(1.50)
            print(f"→ BATTERY_QUERY     {BATTERY_QUERY.hex(' ')}")
            await client.write_gatt_char(WRITE_CHAR, BATTERY_QUERY, response=False)
            print("")

        if args.tutorial:
            await run_tutorial(client, t0, seen)
            try: await client.stop_notify(NOTIFY_CHAR)
            except Exception: pass
            return 0

        if args.interactive:
            print("interactive: enable / disable / mode / battery / blink / shutdown / quit")
            loop = asyncio.get_running_loop()
            while True:
                line = await loop.run_in_executor(None, sys.stdin.readline)
                cmd = (line or "").strip().lower()
                if not cmd or cmd == "quit": break
                pkt = {
                    "enable":   TOUCH_ENABLE,  "disable": TOUCH_DISABLE,
                    "mode":     TOUCH_MODE,    "battery": BATTERY_QUERY,
                    "blink":    FIND_DEVICE,   "shutdown": SHUTDOWN,
                }.get(cmd)
                if pkt is None:
                    print(f"  ?? unknown: {cmd}")
                else:
                    print(f"  → {cmd}: {pkt.hex(' ')}")
                    await client.write_gatt_char(WRITE_CHAR, pkt, response=False)
        else:
            print(f"⌖ Now make your gestures. Suggested verification (§10):")
            print(f"    tap×10, double-tap×10, long-press×10, swipe-forward×10, swipe-backward×10")
            print(f"    space them ~3s apart so the timestamps make them easy to separate.")
            print(f"  Press Ctrl-C to stop.\n")
            try:
                while True:
                    await asyncio.sleep(3600)
            except asyncio.CancelledError:
                pass

        try: await client.stop_notify(NOTIFY_CHAR)
        except Exception: pass

    if csv_fh: csv_fh.close()
    print(f"\nReceived {seen['count']} notify frames.")
    return 0


async def run_tutorial(client: BleakClient, t0: float, seen: dict) -> None:
    """Guided gesture walkthrough. Asks the user to perform each of the 12 gestures from
    Doc/09-user-manual.md §4 in turn; recognises what they did with [TutorialRecogniser]; gives
    Pass/Fail feedback per gesture. Doubles as a verification that the protocol is delivering
    every raw event reliably."""
    print("\n" + "=" * 72)
    print("R08 RING — GUIDED GESTURE TUTORIAL")
    print("=" * 72)
    print(" • Make each gesture when prompted. You have ~5 s per attempt.")
    print(" • If we don't recognise it, you can retry (up to 3 attempts).")
    print(" • Ctrl-C to abort.")
    print("=" * 72)
    print()

    rec = TutorialRecogniser()
    raw_q: asyncio.Queue = asyncio.Queue()

    # Swap in a tutorial-specific notify handler that feeds the recogniser.
    def on_tutorial_notify(_sender, data: bytearray):
        b = bytes(data)
        if len(b) >= 3 and b[0] == PREFIX_RING and b[1] == SUB_GESTURE:
            mapping = {0x01: "SWIPE_UP", 0x02: "SWIPE_DOWN", 0x03: "TOUCH", 0x04: "LONG_PRESS"}
            raw = mapping.get(b[2])
            if raw:
                try: raw_q.put_nowait((raw, time.monotonic()))
                except asyncio.QueueFull: pass

    await client.stop_notify(NOTIFY_CHAR)
    await client.start_notify(NOTIFY_CHAR, on_tutorial_notify)

    results: list[tuple[str, bool]] = []
    try:
        for name, prompt, what in GESTURES_IN_TUTORIAL_ORDER:
            ok = False
            for attempt in range(1, 4):
                attempt_note = "" if attempt == 1 else f"  (attempt {attempt}/3)"
                print(f"⌖ {name}{attempt_note}")
                print(f"  → {prompt}")
                print(f"    ({what})")
                # Drain any stray raw events from a previous gesture, then arm recogniser.
                while not raw_q.empty():
                    try: raw_q.get_nowait()
                    except asyncio.QueueEmpty: break
                rec.consume()
                rec.tap_count = 0; rec.in_combo = False; rec.in_lp_followup = False
                rec.last_tap_t = -1.0
                deadline = time.monotonic() + 5.0
                got: str | None = None
                while time.monotonic() < deadline:
                    timeout = min(0.05, deadline - time.monotonic())
                    try:
                        raw, t = await asyncio.wait_for(raw_q.get(), timeout=timeout)
                        emitted = rec.feed(raw, t)
                        if emitted is None:
                            emitted = rec.tick(t)
                        if emitted == name:
                            got = emitted; break
                        elif emitted and emitted != name:
                            print(f"  ✗ got {emitted}, expected {name}")
                            got = emitted; break
                    except asyncio.TimeoutError:
                        emitted = rec.tick(time.monotonic())
                        if emitted == name:
                            got = emitted; break
                        elif emitted and emitted != name:
                            print(f"  ✗ got {emitted}, expected {name}")
                            got = emitted; break
                if got == name:
                    print(f"  ✓ recognised {name}\n")
                    ok = True; break
                elif got:
                    # got something else — let user retry
                    continue
                else:
                    print(f"  ⌛ timed out (no event)\n" if attempt < 3 else "  ✗ giving up\n")
            results.append((name, ok))

        print("=" * 72)
        print("SUMMARY")
        print("=" * 72)
        passed = sum(1 for _, ok in results if ok)
        for name, ok in results:
            mark = "✓" if ok else "✗"
            print(f"  {mark}  {name}")
        print(f"  {passed}/{len(results)} gestures recognised.")
        if passed == len(results):
            print("\nGreat — every gesture in the design is reaching the BLE layer cleanly.")
        elif passed >= 8:
            print("\nMost gestures recognised. Re-do the failed ones; if they keep failing,")
            print("check Doc/09-user-manual.md §10 (Troubleshooting) and the rhythm tips in §4.")
        else:
            print("\nMany gestures didn't register. Likely culprits:")
            print(" • Ring not snug / touch surface oriented wrong → re-seat the ring.")
            print(" • TOUCH_ENABLE / TOUCH_MODE didn't take → re-run without --tutorial first")
            print(f"   and confirm you see a `73 2a 00` frame.")
            print(" • Firmware variant difference → check the de-dup investigation in")
            print("   phase0/README.md §'What to record'.")
        print("=" * 72)

    finally:
        try: await client.stop_notify(NOTIFY_CHAR)
        except Exception: pass


def main():
    p = argparse.ArgumentParser(description="R08 ring BLE phase-0 probe")
    p.add_argument("--mac", help="Skip scanning, connect directly to this MAC")
    p.add_argument("--scan-timeout", type=float, default=8.0)
    p.add_argument("--no-init", action="store_true", help="Don't send TOUCH_ENABLE/MODE")
    p.add_argument("--interactive", action="store_true")
    p.add_argument("--tutorial", action="store_true",
                   help="Guided gesture walkthrough (see Doc/09-user-manual.md §4)")
    p.add_argument("--record", help="Append every frame to this CSV path")
    args = p.parse_args()
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main() or 0)
