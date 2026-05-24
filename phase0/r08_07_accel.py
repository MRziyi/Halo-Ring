#!/usr/bin/env python3
"""
Stage 7 — Accelerometer 0xA1 characterisation.

Both QRing and 小猪 receive 0xA1 16-byte frames with a 6-byte payload at data[2..7] but
NEITHER decodes them. We characterise by motion-labelled recording.

Strategy: per-byte variance during a labelled motion pattern tells us which bytes are the
active axis. Two-byte pairs likely encode an int16 per axis (STK8321 is 14-bit at ±2g →
~0.25 mg/LSB at the int16 ceiling).

Usage:
  python r08_07_accel.py --pattern still      # noise floor (place ring on table)
  python r08_07_accel.py --pattern rotate-x   # rotate around finger-axis (pitch)
  python r08_07_accel.py --pattern rotate-y
  python r08_07_accel.py --pattern rotate-z
  python r08_07_accel.py --pattern tap        # does A1 spike with 73 2D 03?
  python r08_07_accel.py --pattern shake      # saturation test
  python r08_07_accel.py --pattern free --record accel.csv  --duration 30
"""

from __future__ import annotations

import argparse
import asyncio
import sys
import time

from bleak import BleakClient
from r08_lib import (
    NOTIFY_CHAR, find_ring,
    NotifyLog, StageVerdict, print_stage_card, print_verdict_block,
    PREFIX_ACCEL, PREFIX_RING, SUB_GESTURE,
)


PATTERNS = {
    "still":      "Place the ring on a flat surface. Don't touch it.",
    "rotate-x":   "Hold the ring on your finger. Rotate around the finger-axis (pitch) 10×.",
    "rotate-y":   "Rotate around the up-axis (yaw) 10×.",
    "rotate-z":   "Rotate around the left-right axis (roll) 10×.",
    "translate":  "Translate the hand back-and-forth in any one direction 10×.",
    "tap":        "Tap the ring's touch surface 10× at ~1 Hz.",
    "shake":      "Shake the ring vigorously for 5 s, then rest.",
    "free":       "Move the ring naturally — type, gesture. Mixed motion.",
}


async def run(args) -> int:
    if args.pattern not in PATTERNS:
        print(f"✗ unknown pattern '{args.pattern}'; pick one of {list(PATTERNS)}")
        return 1

    print_stage_card(
        stage_num=7, name=f"Accelerometer 0xA1 ({args.pattern})",
        time_min=args.duration / 60.0 + 0.5, power_pct="0%",
        pre_flight=[
            "Ring on a finger (for rotate-*) OR on a flat surface (for still).",
            "Practice the motion once before starting — it's a 3-2-1 countdown.",
            "For --pattern still: place ring on a table, don't touch it.",
            "For --pattern rotate-*: do 10 slow rotations in the named axis.",
            "For --pattern tap: 10 taps at ~1 Hz on the touch surface.",
        ],
        during=[
            "3-2-1 countdown, then begin the motion immediately.",
            f"Keep the motion going for the full {args.duration:.0f} seconds.",
            "Script prints accel-count + non-accel-count every 1 s in the terminal.",
            "At the end: per-byte variance table — the bytes with the biggest 'span' are the active axis.",
            "If --record was passed, the CSV has every frame for offline analysis.",
        ],
        success=(
            "✓ at least ~5 0xA1 frames/sec during motion patterns\n"
            "✓ in 'still': all 6 payload bytes have small span (noise floor)\n"
            "✓ in 'rotate-x/y/z': two of the six bytes have a much bigger span\n"
            "   than the other four → that pair is the rotated axis (likely int16)"
        ),
    )

    addr = args.mac
    if not addr:
        dev = await find_ring(args.scan_timeout)
        if dev is None: return 2
        addr = dev.address

    log = NotifyLog()
    log.csv_path = args.record
    # Only print Health / 0x73 stuff inline; A1 frames are too noisy to print.
    log.print_filter = lambda d: d.kind not in ("Accel",)

    print(f"\n⌕ Connecting to {addr}…")
    async with BleakClient(addr) as client:
        print("  ✓ connected")
        await client.start_notify(NOTIFY_CHAR, log.attach_handler(client))
        print("  ✓ subscribed\n")
        await asyncio.sleep(0.5)

        print(f"\n=== Pattern: {args.pattern} ({args.duration:.0f} s) ===")
        print(f"  {PATTERNS[args.pattern]}")
        print(f"\n  Starting in 3 s…")
        for s in range(3, 0, -1):
            print(f"    {s}…")
            await asyncio.sleep(1.0)
        print(f"  GO!\n")

        start_idx = len(log.frames)
        start_t = time.monotonic()
        while time.monotonic() - start_t < args.duration:
            await asyncio.sleep(1.0)
            elapsed = int(time.monotonic() - start_t)
            n_accel = sum(1 for _, raw, _ in log.frames[start_idx:]
                          if (raw[0] & 0x7F) == PREFIX_ACCEL)
            n_other = len(log.frames) - start_idx - n_accel
            print(f"  [{elapsed:>3d}s]  accel={n_accel}  other={n_other}")

        try: await client.stop_notify(NOTIFY_CHAR)
        except Exception: pass

    # Analyse
    pat_frames = log.frames[start_idx:]
    accel = [raw for _, raw, _ in pat_frames if (raw[0] & 0x7F) == PREFIX_ACCEL]
    print(f"\n— pattern '{args.pattern}' result —")
    print(f"  total accel frames:   {len(accel)}")
    if len(accel) < 2:
        print(f"  (insufficient data; 0xA1 may not be enabled by default on this firmware)")
        return 3

    # Per-byte stats for payload [2..7]
    print(f"\n  Per-byte stats (payload [2..7]):")
    for i in range(2, 8):
        col = [f[i] for f in accel if len(f) > i]
        if not col: continue
        mn, mx, avg = min(col), max(col), sum(col) / len(col)
        span = mx - mn
        bar = "█" * min(40, span // 6)
        print(f"    byte[{i}]: [{mn:3d}, {mx:3d}]  span={span:3d}  mean={avg:5.1f}  {bar}")

    # Inter-frame timing
    rels = [ms for ms, raw, _ in pat_frames if (raw[0] & 0x7F) == PREFIX_ACCEL]
    if len(rels) >= 2:
        deltas = [b - a for a, b in zip(rels, rels[1:])]
        deltas.sort()
        med = deltas[len(deltas)//2]
        print(f"\n  Inter-frame timing: median={med} ms  ({1000.0/med:.1f} Hz if regular)")

    # 0xA1 + touch correlation (for --pattern tap)
    if args.pattern == "tap":
        touches = [
            ms for ms, raw, _ in pat_frames
            if (raw[0] & 0x7F) == PREFIX_RING and len(raw) >= 3
            and raw[1] == SUB_GESTURE and raw[2] == 0x03
        ]
        accel_ts = [ms for ms, raw, _ in pat_frames if (raw[0] & 0x7F) == PREFIX_ACCEL]
        # For each touch, find nearest accel frame and report offset
        offsets = []
        for tt in touches:
            if accel_ts:
                nearest = min(accel_ts, key=lambda a: abs(a - tt))
                offsets.append(nearest - tt)
        print(f"\n  Touch ↔ Accel correlation:")
        print(f"    touches: {len(touches)} · accel frames: {len(accel_ts)}")
        if offsets:
            print(f"    nearest-accel offset (ms): min={min(offsets)} max={max(offsets)}")

    # Verdict
    print_verdict_block(f"7 — Accelerometer ({args.pattern})", verdicts=[
        StageVerdict(f"0xA1 frames present during '{args.pattern}'",
                     "yes, expect at fixed rate",
                     "yes, both sources receive but don't decode",
                     "?",
                     f"{len(accel)} frames in {args.duration:.0f}s "
                     f"(≈ {len(accel)/args.duration:.1f} Hz)"),
    ])

    log.flush_csv()
    print("\nNext: pair bytes as int16 LE/BE, signed/unsigned, and look for plausible ±1g")
    print("at rest from gravity on the single 'still' axis. Use the recorded CSV across all")
    print("patterns to disambiguate.")
    return 0


def main():
    p = argparse.ArgumentParser(description="Stage 7: 0xA1 accelerometer characterisation")
    p.add_argument("--mac")
    p.add_argument("--scan-timeout", type=float, default=8.0)
    p.add_argument("--pattern", required=True, choices=list(PATTERNS),
                   help="Motion pattern label")
    p.add_argument("--duration", type=float, default=20.0,
                   help="Recording duration in seconds (default: 20; use 30 for 'still')")
    p.add_argument("--record")
    args = p.parse_args()
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main() or 0)
