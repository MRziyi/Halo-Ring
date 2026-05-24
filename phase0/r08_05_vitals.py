#!/usr/bin/env python3
"""
Stage 5 — Real-time vitals stream (0x69 / 0x6A).

Tests QRing's universal `0x69 <kind> 01` start / `0x6A <kind> <last>` stop protocol on R08.
Key questions:
  - Does R08 emit `69 <kind> <err> <val>` ticks? At what cadence?
  - Does it auto-stop after ~25 s (QRing's UX target) or stream until we send 0x6A?
  - Does `errCode = 1` appear when the ring is off-finger (QRing's "not worn" signal)?

POWER WARNING: PPG LED is on continuously during a 0x69 stream. ~0.02 mAh per 25-s burst.
Allow 5 min between back-to-back invocations on the same kind.

Usage:
  python r08_05_vitals.py --measure hr
  python r08_05_vitals.py --measure spo2
  python r08_05_vitals.py --measure stress
  python r08_05_vitals.py --measure hr --wear-test     # remove ring at +5 s
  python r08_05_vitals.py --measure hr --duration 60   # extend observation window
"""

from __future__ import annotations

import argparse
import asyncio
import statistics
import sys
import time

from bleak import BleakClient
from r08_lib import (
    WRITE_CHAR, NOTIFY_CHAR, find_ring,
    NotifyLog, StageVerdict, print_stage_card, print_verdict_block,
    vitals_start, vitals_stop, PREFIX_HEALTH,
)


KIND_NAMES = {"hr": 1, "spo2": 3, "stress": 8, "hrv": 10, "temp": 11}


async def run(args) -> int:
    if args.measure not in KIND_NAMES:
        print(f"✗ unknown kind '{args.measure}'; pick one of {list(KIND_NAMES)}")
        return 1
    kind = KIND_NAMES[args.measure]

    pre = ["Ring on a finger, snug (not loose — PPG needs skin contact)."]
    during = [
        f"Script writes 0x69 0x{kind:02x} 0x01 to start the {args.measure} stream.",
        f"Then listens passively up to {args.duration:.0f} s, logging every `69 …` tick.",
        f"Finally writes 0x6A 0x{kind:02x} … to stop.",
    ]
    if args.wear_test:
        during.append("WEAR-TEST: at +5 s, the script prints REMOVE — take the ring off then.")
        during.append("Watch the errCode column (the `err=` in each tick) for it to flip to 1.")
    success_lines = [
        "✓ a stream of `69 0x%02x <err> <val>` ticks starts within ~1 s" % kind,
        "✓ ticks arrive at ~500 ms cadence (median printed at end)",
        "✓ if QRing's prediction holds, stream auto-stops around 25 s",
    ]
    if args.wear_test:
        success_lines.append("✓ errCode flips to 1 within 1-2 s of physical removal")
    print_stage_card(
        stage_num=5, name=f"Vitals stream ({args.measure})",
        time_min=2, power_pct="~0.1% (PPG)",
        pre_flight=pre,
        during=during,
        success="\n".join(success_lines),
    )

    addr = args.mac
    if not addr:
        dev = await find_ring(args.scan_timeout)
        if dev is None: return 2
        addr = dev.address

    log = NotifyLog()
    log.csv_path = args.record

    print(f"\n⌕ Connecting to {addr}…")
    async with BleakClient(addr) as client:
        print("  ✓ connected")
        await client.start_notify(NOTIFY_CHAR, log.attach_handler(client))
        print("  ✓ subscribed\n")
        await asyncio.sleep(0.5)

        # Send start
        start_pkt = vitals_start(kind)
        print(f"→ vitals_start({args.measure}, kind=0x{kind:02x}): {start_pkt.hex(' ')}")
        measure_start = time.monotonic()
        await client.write_gatt_char(WRITE_CHAR, start_pkt, response=False)

        if args.wear_test:
            print("\n⌕ WEAR-TEST: in ~5 s I'll prompt you to REMOVE the ring.")

        # Listen
        print(f"\n— Listening up to {args.duration:.0f} s for `69 0x{kind:02x} <err> <val>` ticks —")
        last_val = 0
        next_prompt_at = 5.0 if args.wear_test else float("inf")
        prompted = False
        last_health_idx = 0

        while time.monotonic() - measure_start < args.duration:
            await asyncio.sleep(0.05)
            elapsed = time.monotonic() - measure_start
            if not prompted and elapsed >= next_prompt_at:
                print(f"  ★★★ [{int(elapsed*1000):>5d}ms]  REMOVE THE RING NOW — watching errCode")
                prompted = True
            # Track most recent value to pass into the stop cmd
            for _, raw, d in log.frames[last_health_idx:]:
                if (raw[0] & 0x7F) == PREFIX_HEALTH and len(raw) >= 4 and raw[1] == kind:
                    last_val = raw[3]
                last_health_idx += 1

        # Send stop
        stop_pkt = vitals_stop(kind, last_val)
        print(f"\n→ vitals_stop({args.measure}): {stop_pkt.hex(' ')}")
        await client.write_gatt_char(WRITE_CHAR, stop_pkt, response=False)
        await asyncio.sleep(1.0)

        try: await client.stop_notify(NOTIFY_CHAR)
        except Exception: pass

    # Analyse
    health = [(ms, raw, d) for ms, raw, d in log.frames
              if (raw[0] & 0x7F) == PREFIX_HEALTH and len(raw) >= 4 and raw[1] == kind]
    if not health:
        print("\n✗ No health frames received for this kind. Either the ring rejected the start cmd, or this kind isn't supported on this firmware (consult Stage 1's capability bitmap).")
        return 3

    rel_ms = [ms - health[0][0] for ms, _, _ in health]
    deltas = [b - a for a, b in zip(rel_ms, rel_ms[1:])]
    err_codes = [raw[2] for _, raw, _ in health if len(raw) >= 3]
    err1_count = sum(1 for e in err_codes if e == 1)
    err0_count = sum(1 for e in err_codes if e == 0)

    print("\n— measurement summary —")
    print(f"  total frames:           {len(health)}")
    print(f"  first tick at:          rel +{rel_ms[0]} ms (after start cmd)")
    print(f"  last tick at:           rel +{rel_ms[-1]} ms")
    print(f"  stream span:            {rel_ms[-1] - rel_ms[0]} ms")
    if deltas:
        print(f"  median inter-tick:      {int(statistics.median(deltas))} ms")
        print(f"  min/max inter-tick:     {min(deltas)} / {max(deltas)} ms")
    print(f"  errCode = 0 frames:     {err0_count}")
    print(f"  errCode = 1 frames:     {err1_count}")
    if args.wear_test:
        print(f"  → if errCode=1 appeared after the prompt at +5 s, QRing's wear-detect works on R08.")

    # Verdict
    verdicts = [
        StageVerdict(f"{args.measure} stream produces 69 ticks",
                     f"yes, ~500 ms cadence, ~25 s duration",
                     "(not in 小猪)",
                     "q" if len(health) > 5 else "?",
                     f"{len(health)} ticks, median {int(statistics.median(deltas)) if deltas else '-'} ms"),
        StageVerdict(f"{args.measure} stream span",
                     "auto-stops near 25 s (firmware decides)",
                     "(not in 小猪)",
                     "?",
                     f"saw stream up to {rel_ms[-1]} ms"),
    ]
    if args.wear_test:
        verdicts.append(StageVerdict("errCode=1 wear-detect on removal",
                                     "fires within 1-2 s of physical removal",
                                     "(not in 小猪)",
                                     "q" if err1_count > 0 else "?",
                                     f"{err1_count} errCode=1 frames observed"))

    print_verdict_block(f"5 — Vitals stream ({args.measure})", verdicts)
    log.flush_csv()
    return 0


def main():
    p = argparse.ArgumentParser(description="Stage 5: real-time vitals stream timing + wear-detect")
    p.add_argument("--mac")
    p.add_argument("--scan-timeout", type=float, default=8.0)
    p.add_argument("--measure", required=True, choices=list(KIND_NAMES),
                   help="Which vitals kind to stream")
    p.add_argument("--duration", type=float, default=30.0,
                   help="Observation window in seconds (default: 30)")
    p.add_argument("--wear-test", action="store_true",
                   help="Prompt to remove the ring at +5 s; check for errCode=1")
    p.add_argument("--record")
    args = p.parse_args()
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main() or 0)
