#!/usr/bin/env python3
"""
Stage 3 — Passive observation of `0x73 <sub>` sync triggers.

Three 60-second windows: rest / wear-cycle / motion. No writes. Auto-tabulates which of QRing's
~30 sub-codes the R08 firmware emits.

Usage:
  python r08_03_passive.py
  python r08_03_passive.py --window 30      # shorter windows
  python r08_03_passive.py --windows rest   # only the rest window
"""

from __future__ import annotations

import argparse
import asyncio
import sys

from bleak import BleakClient
from r08_lib import (
    NOTIFY_CHAR, find_ring, PREFIX_RING, QRING_73_SUBS,
    NotifyLog, StageVerdict, print_stage_card, print_verdict_block,
)


WINDOW_PROMPTS = {
    "rest": (
        "REST WINDOW",
        "Sit still with the ring on your finger. Don't tap it or move much.",
        "What baseline sub-codes does the firmware emit on its own?",
    ),
    "wear": (
        "WEAR-CYCLE WINDOW",
        "At +5 s: remove the ring. At +30 s: put it back on. Sit still otherwise.",
        "Any sub-code correlated with the on/off transition? Especially 73 3E (G-sensor still-tick)?",
    ),
    "motion": (
        "MOTION WINDOW",
        "Keep moving your hand naturally — type, gesture, walk around the room.",
        "Step-increment (73 11), activity-total (73 12), motion-related sub-codes?",
    ),
    "doubletap": (
        "DOUBLE-TAP WINDOW",
        "Perform 10 physical double-taps on the ring's touch surface (slow + fast variants).",
        "Does 73 30 (LOVER_DOUBLE_TAP) fire? If yes, firmware-side double-tap is available.",
    ),
}


async def run_window(client, log: NotifyLog, name: str, prompt: tuple, duration: float) -> dict:
    head, instr, question = prompt
    print("\n" + "=" * 72)
    print(f"{name.upper()} — {head}  ({duration:.0f} s)")
    print("=" * 72)
    print(f" {instr}")
    print(f" Looking for: {question}")
    print(" Press Enter when ready…")
    loop = asyncio.get_running_loop()
    await loop.run_in_executor(None, sys.stdin.readline)

    start_idx = len(log.frames)
    print(f"  → recording…")
    await asyncio.sleep(duration)

    win_frames = log.frames[start_idx:]
    # Tabulate
    sub_counts: dict[int, int] = {}
    other_kinds: dict[str, int] = {}
    for _, raw, d in win_frames:
        if len(raw) >= 2 and (raw[0] & 0x7F) == PREFIX_RING:
            sub = raw[1]
            sub_counts[sub] = sub_counts.get(sub, 0) + 1
        else:
            other_kinds[d.kind] = other_kinds.get(d.kind, 0) + 1

    print(f"\n— {name} window result ({len(win_frames)} total frames) —")
    if sub_counts:
        print(f"  0x73 sub-codes:")
        for sub, count in sorted(sub_counts.items()):
            qring_name = QRING_73_SUBS.get(sub, f"unknown-0x{sub:02x}")
            print(f"    0x{sub:02x} ({qring_name:<22s}): {count}")
    if other_kinds:
        print(f"  Other frames:")
        for k, n in sorted(other_kinds.items()):
            print(f"    {k:<22s}: {n}")
    if not win_frames:
        print(f"  (silent — ring auto-slept or not on finger)")

    return {"sub_counts": sub_counts, "other_kinds": other_kinds, "total": len(win_frames)}


async def run(args) -> int:
    print_stage_card(
        stage_num=3, name="Passive 0x73 sub-code catalogue",
        time_min=15, power_pct="0%",
        pre_flight=[
            "Ring on a finger.",
            "No writes happen — purely listening. Battery cost is zero.",
        ],
        during=[
            f"Four {args.window:.0f}-second windows in sequence. Each prompts 'Press Enter when ready'.",
            "  rest      — sit still, ring on finger, no taps",
            "  wear      — at +5 s remove ring, at +30 s put it back on",
            "  motion    — keep your hand moving naturally",
            "  doubletap — perform 10 physical double-taps (look for 73 30)",
            "Script auto-tabulates 0x73 sub-codes per window — no grading needed.",
        ],
        success=(
            "✓ at least 1-2 sub-codes appear in each window\n"
            "✓ if 73 30 fires during doubletap → firmware-side double-tap available\n"
            "✓ if 73 3E fires during wear/motion → G-sensor still-tick available"
        ),
    )

    addr = args.mac
    if not addr:
        dev = await find_ring(args.scan_timeout)
        if dev is None: return 2
        addr = dev.address

    log = NotifyLog()
    log.csv_path = args.record
    # Stage 3 prints only "interesting" frames so the console isn't drowned
    log.print_filter = lambda d: d.interesting

    selected = args.windows.split(",") if args.windows else ["rest", "wear", "motion", "doubletap"]

    print(f"\n⌕ Connecting to {addr}…")
    async with BleakClient(addr) as client:
        print("  ✓ connected")
        await client.start_notify(NOTIFY_CHAR, log.attach_handler(client))
        print("  ✓ subscribed\n")
        await asyncio.sleep(0.5)

        results: dict[str, dict] = {}
        for window in selected:
            if window not in WINDOW_PROMPTS:
                print(f"  ! unknown window '{window}', skipping")
                continue
            results[window] = await run_window(client, log, window, WINDOW_PROMPTS[window],
                                               args.window)

        try: await client.stop_notify(NOTIFY_CHAR)
        except Exception: pass

    # Verdict
    notes = []
    union_subs = set()
    for w_name, r in results.items():
        notes.append(f"-- {w_name.upper()} ({r['total']} frames):")
        for sub, count in sorted(r["sub_counts"].items()):
            name = QRING_73_SUBS.get(sub, f"unknown-0x{sub:02x}")
            notes.append(f"     0x73 0x{sub:02x} {name}: {count}")
            union_subs.add(sub)
        for k, n in sorted(r["other_kinds"].items()):
            notes.append(f"     {k}: {n}")

    notes.append("")
    notes.append("R08 0x73 sub-code union across all windows:")
    for sub in sorted(union_subs):
        name = QRING_73_SUBS.get(sub, f"unknown-0x{sub:02x}")
        notes.append(f"  • 0x{sub:02x} {name}")

    print_verdict_block("3 — Passive 0x73 catalogue", verdicts=[], passive_notes=notes)
    log.flush_csv()
    return 0


def main():
    p = argparse.ArgumentParser(description="Stage 3: passive 0x73 sub-code observation")
    p.add_argument("--mac")
    p.add_argument("--scan-timeout", type=float, default=8.0)
    p.add_argument("--window", type=float, default=60.0,
                   help="Each window's duration in seconds (default: 60)")
    p.add_argument("--windows", default="",
                   help="Comma-separated subset of {rest,wear,motion,doubletap}; default = all four")
    p.add_argument("--record")
    args = p.parse_args()
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main() or 0)
