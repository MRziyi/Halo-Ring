#!/usr/bin/env python3
"""
Stage 4 — 小猪's touch + gesture path verification (the R08-specific addendum).

Sends 小猪's TOUCH_ENABLE + TOUCH_MODE, then walks through the 4 gestures, then a 30-tap fast
sequence for the dedup window, and finally a double-tap probe for the firmware-side
0x73 0x30 LOVER_DOUBLE_TAP signal.

Usage:
  python r08_04_xiaozhu.py
  python r08_04_xiaozhu.py --skip-tutorial    # only do init + dedup probe
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
    TOUCH_ENABLE, TOUCH_MODE, TOUCH_DISABLE,
    PREFIX_RING, SUB_GESTURE, SUB_TOUCH_STATUS, GESTURE_NAMES,
)


GESTURES_IN_ORDER = [
    ("TOUCH",       0x03, "Single tap"),
    ("SWIPE_UP",    0x01, "Forward swipe (toward fingertip)"),
    ("SWIPE_DOWN",  0x02, "Backward swipe (toward palm)"),
    ("LONG_PRESS",  0x04, "Press and hold ~600 ms"),
]


async def wait_for_gesture(log: NotifyLog, expected_code: int, timeout_s: float = 5.0) -> bool:
    """Wait up to timeout for a 73 2D <expected> frame after the current log tail."""
    start_idx = len(log.frames)
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        await asyncio.sleep(0.05)
        for _, raw, _ in log.frames[start_idx:]:
            if len(raw) >= 3 and (raw[0] & 0x7F) == PREFIX_RING and raw[1] == SUB_GESTURE and raw[2] == expected_code:
                return True
    return False


async def measure_dedup_window(log: NotifyLog) -> dict:
    """User taps as fast as possible 30 times. We collect TOUCH-frame timestamps and compute stats."""
    print("\n— DEDUP MEASUREMENT —")
    print(" Tap the ring AS FAST AS YOU CAN, 30 times in a row.")
    print(" Press Enter when ready, then tap…")
    loop = asyncio.get_running_loop()
    await loop.run_in_executor(None, sys.stdin.readline)

    start_idx = len(log.frames)
    print("  → recording 15 s…")
    await asyncio.sleep(15.0)

    taps = []
    last_bytes = None
    repeats = 0
    for ms, raw, _ in log.frames[start_idx:]:
        if len(raw) >= 3 and (raw[0] & 0x7F) == PREFIX_RING and raw[1] == SUB_GESTURE and raw[2] == 0x03:
            taps.append((ms, raw))
            if last_bytes is not None and raw == last_bytes:
                repeats += 1
            last_bytes = raw

    print(f"  → got {len(taps)} TOUCH frames")
    if len(taps) < 2:
        return {"count": len(taps), "min_delta_ms": None, "median_delta_ms": None,
                "exact_repeats": repeats, "recommendation": "(insufficient data)"}

    deltas = [b[0] - a[0] for a, b in zip(taps, taps[1:])]
    deltas.sort()
    min_d = deltas[0]
    med_d = statistics.median(deltas)
    has_varying = (repeats < len(taps) - 1)  # at least one consecutive pair was non-identical

    rec = (
        f"counter-byte present (repeats {repeats}/{len(taps)-1}) — "
        "use 'drop on exact match within ~50 ms', any window"
        if has_varying else
        f"no counter byte (all {len(taps)} frames byte-identical) — "
        f"set dedup window to {max(20, min_d - 10)} ms"
    )

    return {"count": len(taps), "min_delta_ms": min_d, "median_delta_ms": med_d,
            "exact_repeats": repeats, "varying_byte": has_varying,
            "recommendation": rec}


async def doubletap_probe(log: NotifyLog) -> dict:
    """Check whether 0x73 0x30 LOVER_DOUBLE_TAP fires on actual double-tap."""
    print("\n— DOUBLE-TAP FIRMWARE PROBE —")
    print(" Perform 5 deliberate double-taps (two quick taps, 100–250 ms apart).")
    print(" Watch for 0x73 0x30 (LOVER_DOUBLE_TAP) alongside the usual 0x73 0x2D 0x03 pair.")
    print(" Press Enter when ready…")
    loop = asyncio.get_running_loop()
    await loop.run_in_executor(None, sys.stdin.readline)

    start_idx = len(log.frames)
    print("  → recording 15 s…")
    await asyncio.sleep(15.0)

    n_30 = 0
    n_2d_03 = 0
    for _, raw, _ in log.frames[start_idx:]:
        if len(raw) >= 2 and (raw[0] & 0x7F) == PREFIX_RING:
            if raw[1] == 0x30:
                n_30 += 1
            elif raw[1] == SUB_GESTURE and len(raw) >= 3 and raw[2] == 0x03:
                n_2d_03 += 1

    print(f"  → {n_30} × (73 30 LOVER_DOUBLE_TAP), {n_2d_03} × (73 2D 03 TOUCH)")
    return {"lover_double_tap_count": n_30, "raw_touch_count": n_2d_03}


async def run(args) -> int:
    print_stage_card(
        stage_num=4, name="小猪 touch + gestures + dedup",
        time_min=15, power_pct="<0.05%",
        pre_flight=[
            "Ring on a finger. Touch surface facing your thumb / accessible side.",
            "Practice the 4 motions once before running:",
            "  TOUCH       — quick tap with thumb",
            "  SWIPE_UP    — slide thumb FORWARD along the ring's flat surface",
            "  SWIPE_DOWN  — slide thumb BACKWARD",
            "  LONG_PRESS  — press and hold ~600 ms",
        ],
        during=[
            "1) Script sends TOUCH_ENABLE → 73 2A 00 echo should appear",
            "2) Tutorial: 4 gestures, 3 attempts each, follow the on-screen prompt",
            "3) Dedup measurement: 30 fast taps in 15 s",
            "4) Double-tap probe: 5 deliberate double-taps in 15 s",
            "At each grade prompt, type x (matches 小猪) / q (matches QRing) / ? + notes.",
        ],
        success=(
            "✓ 73 2A 00 echo within 1.5 s of TOUCH_ENABLE\n"
            "✓ all 4 gestures recognised on first or second attempt\n"
            "✓ dedup: min inter-tap delta is reasonable (≥ 50 ms typical)\n"
            "✓ double-tap probe: any 73 30 fires → big latency win available"
        ),
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

        verdicts: list[StageVerdict] = []

        # 1. TOUCH_ENABLE
        print(f"\n→ TOUCH_ENABLE: {TOUCH_ENABLE.hex(' ')}")
        await client.write_gatt_char(WRITE_CHAR, TOUCH_ENABLE, response=False)
        start_idx = len(log.frames)
        await asyncio.sleep(1.5)
        # look for 73 2A 00
        found_status = False
        for _, raw, _ in log.frames[start_idx:]:
            if len(raw) >= 3 and (raw[0] & 0x7F) == PREFIX_RING and raw[1] == SUB_TOUCH_STATUS:
                found_status = (raw[2] == 0)
                break
        print(f"  → 73 2A 00 echo: {'✓' if found_status else '✗ NOT SEEN'}")
        verdicts.append(StageVerdict("TOUCH_ENABLE → 73 2A 00 echo", "echo within ~800 ms",
                                     "echo within ~800 ms",
                                     "x" if found_status else "?",
                                     "" if found_status else "no echo in 1.5 s — touch IC may be on a different opcode on this firmware"))

        # 2. TOUCH_MODE
        await asyncio.sleep(0.5)
        print(f"\n→ TOUCH_MODE: {TOUCH_MODE.hex(' ')}")
        await client.write_gatt_char(WRITE_CHAR, TOUCH_MODE, response=False)
        await asyncio.sleep(0.5)

        if not args.skip_tutorial:
            # 3. Gesture tutorial
            print("\n— 4-GESTURE WALKTHROUGH —")
            for name, code, instr in GESTURES_IN_ORDER:
                got = False
                for attempt in range(1, 4):
                    print(f"\n  Perform: {name}  ({instr})  — attempt {attempt}/3")
                    if await wait_for_gesture(log, code, timeout_s=5.0):
                        print(f"    ✓ 73 2D 0x{code:02x} seen")
                        got = True
                        break
                    else:
                        print(f"    ⌛ no 73 2D 0x{code:02x} in 5 s")
                verdicts.append(StageVerdict(f"Gesture {name}",
                                             f"73 2D 0x{code:02x} within 5 s",
                                             f"73 2D 0x{code:02x} within 5 s",
                                             "x" if got else "?",
                                             "" if got else "missed in 3 attempts"))

        # 4. Dedup measurement
        dedup = await measure_dedup_window(log)
        notes = (f"min_delta={dedup.get('min_delta_ms')} ms · "
                 f"median_delta={dedup.get('median_delta_ms')} ms · "
                 f"varying_byte={dedup.get('varying_byte')} · "
                 f"recommendation: {dedup['recommendation']}")
        verdicts.append(StageVerdict("Dedup window measurement",
                                     "100 ms (xiaozhu default)",
                                     "100 ms (xiaozhu default)",
                                     "?", notes))

        # 5. Double-tap firmware probe
        dt = await doubletap_probe(log)
        had_lover_dt = dt["lover_double_tap_count"] > 0
        notes_dt = (f"LOVER_DOUBLE_TAP (73 30): {dt['lover_double_tap_count']} · "
                    f"TOUCH (73 2D 03): {dt['raw_touch_count']}")
        verdicts.append(StageVerdict("Firmware double-tap (73 30)",
                                     "fires on real double-tap → app combo window redundant",
                                     "(not in 小猪)",
                                     "q" if had_lover_dt else "?", notes_dt))

        # 6. TOUCH_DISABLE
        await client.write_gatt_char(WRITE_CHAR, TOUCH_DISABLE, response=False)
        try: await client.stop_notify(NOTIFY_CHAR)
        except Exception: pass

    print_verdict_block("4 — 小猪 touch + gestures + dedup", verdicts)
    log.flush_csv()
    return 0


def main():
    p = argparse.ArgumentParser(description="Stage 4: 小猪 touch + gestures + dedup window")
    p.add_argument("--mac")
    p.add_argument("--scan-timeout", type=float, default=8.0)
    p.add_argument("--skip-tutorial", action="store_true",
                   help="Skip the 4-gesture walkthrough; only do init + dedup + double-tap probes")
    p.add_argument("--record")
    args = p.parse_args()
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main() or 0)
