#!/usr/bin/env python3
"""
R08 Ring — Health-stream + accelerometer characterisation probe.

WHY THIS EXISTS
---------------
Two open questions from Doc/02 the other two phase-0 scripts can't answer cheaply:

  1. **Vitals timing**. `R08Protocol.kt` currently runs the `0x69 <kind> 01` start command for
     ~3 seconds per kind, then sends `0x6A` to stop. QRing decompile (refs/qring-...md) shows the
     official app uses a **25-second** measurement window for HR / SpO2 / Stress / BP / HRV / Temp,
     with a 500 ms tick cadence. We don't know which the R08 firmware actually wants — the value
     might come from the ring (fixed 25 s — ignore our stop) or from us (we decide).

     This probe starts the stream, logs every notify with timestamps, and waits for either an
     auto-stop or 30 s, whichever comes first. The CSV makes the question answerable.

  2. **`0x69` errCode = "not worn"**. QRing checks `data[2] == 1` on every vitals frame and pops
     a "wearing detection" dialog. 小猪 doesn't look at byte [2]. If R08 firmware honours QRing's
     semantics, we get a zero-cost wear-state signal opportunistically during measurements.
     `--wear-test` mode prompts you to remove the ring 5 s into the stream and watches the err byte.

  3. **`0xA1` accelerometer layout**. Both 小猪 and QRing receive 16-byte `0xA1` frames with a
     6-byte payload at `data[2..7]` but neither decodes it. STK8321 (the accel chip per the BOM)
     is 14-bit 3-axis, so the payload is likely `(x, y, z)` as int16s. `--accel <pattern>` mode
     prompts you to perform a specific motion and tags every recorded frame so we can correlate
     later.

USAGE
-----
   python r08_health_probe.py --measure hr           # 30-s HR stream
   python r08_health_probe.py --measure spo2
   python r08_health_probe.py --measure stress
   python r08_health_probe.py --measure hr --wear-test    # remove ring mid-stream
   python r08_health_probe.py --accel still --duration 30 # 30 s ring-still recording
   python r08_health_probe.py --accel rotate-x
   python r08_health_probe.py --mac AA:BB:CC:DD:EE:FF
   python r08_health_probe.py --record out.csv

POWER NOTE
----------
A 25-second vitals stream has the PPG LED on continuously — that's the most power-expensive
thing this script does, and the most-expensive thing any health feature in the production code
would ever do. Doc/06 §3.4 budget: PPG ≈ 3 mA × 25 s ≈ 0.02 mAh per snapshot, sustainable at
≤1/hour. Don't loop this script with --measure unattended.
"""

from __future__ import annotations

import argparse
import asyncio
import csv
import sys
import time
from dataclasses import dataclass
from typing import Optional

from bleak import BleakClient, BleakScanner
from bleak.backends.device import BLEDevice

# Re-use protocol constants from the main probe.
sys.path.insert(0, __file__.rsplit("/", 1)[0])
from r08_probe import (  # noqa: E402
    SERVICE_UUID, WRITE_CHAR, NOTIFY_CHAR, NAME_KEYWORDS,
    PREFIX_RING, PREFIX_ACCEL, PREFIX_HEALTH,
    _cmd,
    TOUCH_ENABLE, TOUCH_MODE, BATTERY_QUERY,
)


# ── Real-time vitals start/stop builders ─────────────────────────────────────────────────────────
# Per QRing `StartHeartRateReq.java` action constants: START=1, PAUSE=2, CONTINUE=3, STOP=4.
# Kind constants (QRing): 1=HR, 2=BP, 3=SpO2, 4=Fatigue, 5=HealthCheck, 6=RealtimeHR, 7=ECG,
# 8=Pressure/stress, 9=BloodSugar, 10=HRV, 11=Temp.

KIND_HR     = 1
KIND_SPO2   = 3
KIND_STRESS = 8

def vitals_start(kind: int) -> bytes:
    """0x69 <kind> 01 — start streaming."""
    return _cmd(0x69, bytes([kind, 1]))

def vitals_stop(kind: int, last_value: int = 0) -> bytes:
    """0x6A <kind> <last> 00 — stop. QRing passes the last sampled value; we just pass it."""
    return _cmd(0x6A, bytes([kind, last_value & 0xFF, 0]))


# ── Decode + accumulate ──────────────────────────────────────────────────────────────────────────

@dataclass
class Frame:
    t_ms: int            # ms since start of probe
    rel_ms: int          # ms since start of the current measurement
    raw: bytes
    kind: str            # 'Health' / 'Accel' / 'Other'
    detail: str
    is_err: bool = False
    err_code: int = 0


def decode_one(data: bytes, t_ms: int, rel_ms: int) -> Frame:
    if not data:
        return Frame(t_ms, rel_ms, data, "Other", "empty")
    b0 = data[0]
    if b0 == PREFIX_HEALTH and len(data) >= 4:
        kind_map = {1: "HR", 3: "SpO2", 8: "stress", 11: "Temp", 10: "HRV"}
        kind_name = kind_map.get(data[1], f"kind=0x{data[1]:02x}")
        err = data[2] if len(data) >= 3 else 0
        val = data[3]
        detail = f"{kind_name} err={err} value={val}"
        if len(data) >= 6 and data[1] == 2:  # BP carries sbp/dbp
            detail += f" sbp={data[4]} dbp={data[5]}"
        return Frame(t_ms, rel_ms, data, "Health", detail, is_err=(err == 1), err_code=err)
    if b0 == PREFIX_ACCEL:
        # 16-byte fixed; 6-byte payload at [2..7] per 小猪 DataParser. Show all 16 bytes for forensic.
        return Frame(t_ms, rel_ms, data, "Accel", f"payload[2..7]={data[2:8].hex(' ')}")
    return Frame(t_ms, rel_ms, data, "Other", f"prefix=0x{b0:02x} hex={data.hex(' ')}")


# ── Scanning + connection (mirrors r08_probe) ────────────────────────────────────────────────────

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


async def connect_and_init(client: BleakClient) -> None:
    """Same init dance as r08_probe so the ring starts emitting normally."""
    await asyncio.sleep(0.80)
    await client.write_gatt_char(WRITE_CHAR, TOUCH_ENABLE, response=False)
    await asyncio.sleep(0.50)
    await client.write_gatt_char(WRITE_CHAR, TOUCH_MODE, response=False)
    await asyncio.sleep(1.50)
    await client.write_gatt_char(WRITE_CHAR, BATTERY_QUERY, response=False)
    await asyncio.sleep(0.50)


# ── Mode: --measure ─────────────────────────────────────────────────────────────────────────────

KIND_NAMES = {"hr": KIND_HR, "spo2": KIND_SPO2, "stress": KIND_STRESS}

async def run_measure(client: BleakClient, kind_name: str, duration_s: float,
                      wear_test: bool, frames: list[Frame], t0: float) -> None:
    kind = KIND_NAMES[kind_name]
    measure_start = time.monotonic()

    def fresh_decode(data: bytes) -> Frame:
        now = time.monotonic()
        t_ms = int((now - t0) * 1000)
        rel_ms = int((now - measure_start) * 1000)
        return decode_one(data, t_ms, rel_ms)

    print(f"\n→ vitals_start({kind_name})  : {vitals_start(kind).hex(' ')}")
    await client.write_gatt_char(WRITE_CHAR, vitals_start(kind), response=False)

    print(f"\nWaiting up to {duration_s:.0f} s for vitals frames…")
    print(f"(If QRing protocol applies, ring should auto-stop around 25 s. If R08-Remote applies,")
    print(f"we drive the stop after {duration_s:.0f} s. Either way: watch for `69 {kind:02x} <err> <val>` frames.)")

    if wear_test:
        print(f"\n⌕ WEAR-TEST: at +5 s, REMOVE the ring from your finger and hold it still.")
        print(f"  Watch the err column: 0=worn, 1=not-worn (per QRing).")

    # Track frames received during this measurement window.
    start_idx = len(frames)
    last_health: Optional[Frame] = None
    deadline = measure_start + duration_s
    last_print = 0.0
    while time.monotonic() < deadline:
        # We don't drive notify handling here — frames is being filled by the on_notify cb.
        await asyncio.sleep(0.05)
        # Print new frames since the last tick
        new = frames[start_idx:]
        if len(new) > 0:
            for f in new[len(new) - (len(frames) - start_idx) + 0:]:
                pass  # we'll print fresh-frame info via the callback
        # Print a wear-test reminder timeline.
        elapsed = time.monotonic() - measure_start
        if wear_test and last_print < 4 <= elapsed:
            print(f"  [   {int(elapsed*1000):>4d}ms]  → remove the ring in 1 second…")
        if wear_test and last_print < 5 <= elapsed:
            print(f"  [   {int(elapsed*1000):>4d}ms]  → NOW: remove the ring.")
        last_print = elapsed

    # Send STOP (we don't know yet if the ring auto-stops; defence in depth)
    if last_health is not None:
        last_val = last_health.raw[3] if len(last_health.raw) >= 4 else 0
    else:
        last_val = 0
    print(f"\n→ vitals_stop({kind_name})   : {vitals_stop(kind, last_val).hex(' ')}")
    await client.write_gatt_char(WRITE_CHAR, vitals_stop(kind, last_val), response=False)

    # Capture frames in the 1 s after stop (was there an ACK?)
    await asyncio.sleep(1.0)

    # Per-measurement summary
    measure_frames = frames[start_idx:]
    health_frames = [f for f in measure_frames if f.kind == "Health" and f.raw[1] == kind]
    err1_count = sum(1 for f in health_frames if f.is_err)
    print(f"\n— measurement summary —")
    print(f"  Health frames matching kind={kind_name}: {len(health_frames)}")
    if health_frames:
        rels = [f.rel_ms for f in health_frames]
        deltas = [b - a for a, b in zip(rels, rels[1:])]
        print(f"  First frame   : t_rel={rels[0]} ms")
        print(f"  Last frame    : t_rel={rels[-1]} ms")
        print(f"  Median tick   : {sorted(deltas)[len(deltas)//2] if deltas else 'n/a'} ms")
        print(f"  Stream length : {rels[-1] - rels[0]} ms")
    print(f"  errCode=1 frames (wear-detect): {err1_count}")
    if wear_test:
        print(f"  → If errCode=1 appeared shortly after you removed the ring, QRing's wear-detect works.")


# ── Mode: --accel ───────────────────────────────────────────────────────────────────────────────

ACCEL_PATTERNS = {
    "still":     "Hold the ring perfectly still (rest the ring on the table). Records the noise floor.",
    "rotate-x":  "Rotate the ring slowly around the finger-axis (pitch) 10 times. Stay otherwise still.",
    "rotate-y":  "Rotate around the up-axis (yaw) 10 times.",
    "rotate-z":  "Rotate around the left-right axis (roll) 10 times.",
    "translate-x": "Slide the ring horizontally back-and-forth 10 times along one axis.",
    "translate-y": "Slide vertically up-and-down 10 times.",
    "translate-z": "Slide forward-and-back 10 times.",
    "shake":     "Shake the ring vigorously. Tests saturation / clipping.",
    "tap":       "Tap the ring 10 times. We want to see if 0xA1 fires alongside 73 2D 03.",
    "free":      "Move the ring naturally — write, gesture, type. Tests realistic mixed motion.",
}

async def run_accel(client: BleakClient, pattern: str, duration_s: float,
                    frames: list[Frame], t0: float) -> None:
    print(f"\n=== ACCEL PATTERN: {pattern} ===")
    print(f"\n{ACCEL_PATTERNS.get(pattern, 'unknown pattern')}")
    print(f"\nDuration: {duration_s:.0f} s. Begin in 3 seconds…")
    for s in range(3, 0, -1):
        print(f"  {s}…")
        await asyncio.sleep(1)
    print(f"  GO!")
    measure_start = time.monotonic()
    start_idx = len(frames)
    while time.monotonic() - measure_start < duration_s:
        await asyncio.sleep(0.5)
        elapsed = time.monotonic() - measure_start
        accel_so_far = sum(1 for f in frames[start_idx:] if f.kind == "Accel")
        print(f"  [{int(elapsed*1000):>5d}ms]  ({accel_so_far} accel frames)")
    print(f"\n— accel summary —")
    measure_frames = frames[start_idx:]
    accel_frames = [f for f in measure_frames if f.kind == "Accel"]
    print(f"  Accel frames received: {len(accel_frames)}")
    if accel_frames:
        rates = [b.rel_ms - a.rel_ms for a, b in zip(accel_frames, accel_frames[1:])]
        avg_dt = sum(rates) / len(rates) if rates else 0
        hz = 1000.0 / avg_dt if avg_dt > 0 else 0
        print(f"  Average inter-frame: {avg_dt:.1f} ms  (≈ {hz:.1f} Hz)")
        # Per-byte variance across [2..7]
        bytes_at = [[f.raw[i] for f in accel_frames if len(f.raw) > i] for i in range(2, 8)]
        for i, col in enumerate(bytes_at):
            if col:
                mn, mx, avg = min(col), max(col), sum(col) / len(col)
                print(f"  byte[{i+2}]: range [{mn:3d}, {mx:3d}]  span={mx-mn:3d}  mean={avg:5.1f}")
        print(f"  → Bytes with the biggest span during '{pattern}' are likely the active axis(es).")
        print(f"  → For two-byte axes (int16): pair high+low bytes; signed-LE most likely (STK8321 convention).")


# ── Main probe ───────────────────────────────────────────────────────────────────────────────────

async def run(args) -> int:
    if args.mac:
        addr = args.mac
    else:
        dev = await find_ring(args.scan_timeout)
        if dev is None: return 2
        addr = dev.address

    t0 = time.monotonic()
    frames: list[Frame] = []

    def on_notify(_sender, data: bytearray):
        now = time.monotonic()
        t_ms = int((now - t0) * 1000)
        b = bytes(data)
        # rel_ms gets recomputed in the mode-specific code; we still log every frame
        f = decode_one(b, t_ms, 0)
        frames.append(f)
        # Pretty-print only interesting frames so the console isn't drowned by 0xA1 streaming.
        if f.kind in ("Health", "Other") or args.verbose:
            tag = "★ " if f.is_err else "  "
            print(f"  {tag}[{t_ms:>6d}ms]  {b.hex(' '):<48s}  {f.kind:<8s}  {f.detail}")

    print(f"⌕ Connecting to {addr}…")
    async with BleakClient(addr) as client:
        print("  ✓ connected")
        await client.start_notify(NOTIFY_CHAR, on_notify)
        print("  ✓ subscribed")

        await connect_and_init(client)

        if args.measure:
            duration = args.duration if args.duration > 0 else 30.0
            await run_measure(client, args.measure, duration, args.wear_test, frames, t0)
        elif args.accel:
            duration = args.duration if args.duration > 0 else 20.0
            await run_accel(client, args.accel, duration, frames, t0)
        else:
            # Default: just listen for --duration seconds and report tallies.
            duration = args.duration if args.duration > 0 else 30.0
            print(f"\nNo mode selected. Listening for {duration:.0f} s; use --measure or --accel.")
            await asyncio.sleep(duration)

        try: await client.stop_notify(NOTIFY_CHAR)
        except Exception: pass

    # Final summary
    print(f"\n=== TOTAL {len(frames)} notify frames ===")
    by_kind = {}
    for f in frames:
        by_kind[f.kind] = by_kind.get(f.kind, 0) + 1
    for k, n in sorted(by_kind.items()):
        print(f"  {k:<8s}: {n}")

    if args.record:
        with open(args.record, "w", newline="") as fh:
            w = csv.writer(fh)
            w.writerow(["t_ms", "rel_ms", "hex", "kind", "detail", "err_code"])
            for f in frames:
                w.writerow([f.t_ms, f.rel_ms, f.raw.hex(' '), f.kind, f.detail, f.err_code])
        print(f"\nCSV written to {args.record}  ({len(frames)} rows)")

    return 0


def main():
    p = argparse.ArgumentParser(
        description="R08 ring: vitals-stream timing + accelerometer characterisation",
    )
    p.add_argument("--mac", help="Skip scanning, connect directly to this MAC")
    p.add_argument("--scan-timeout", type=float, default=8.0)
    p.add_argument("--measure", choices=list(KIND_NAMES.keys()),
                   help="Start a real-time vitals stream of the given kind for --duration s.")
    p.add_argument("--wear-test", action="store_true",
                   help="Prompt to remove the ring 5 s into measurement; watch for errCode=1.")
    p.add_argument("--accel", choices=list(ACCEL_PATTERNS.keys()),
                   help="Record an accelerometer characterisation run with a labelled motion pattern.")
    p.add_argument("--duration", type=float, default=0.0,
                   help="Override the default duration in seconds (measure default 30 s, accel default 20 s).")
    p.add_argument("--record", help="Write every frame to this CSV path.")
    p.add_argument("--verbose", action="store_true",
                   help="Print every frame, including 0xA1 accel (default: only Health and Other).")
    args = p.parse_args()
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main() or 0)
