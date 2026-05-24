#!/usr/bin/env python3
"""
Stage 9 — Contested opcodes 0x06 / 0x10.

These bytes live in R08Protocol.kt as heritage from R08-Dev.md but appear in NEITHER 小猪 nor
QRing source. This script tests what they actually do on R08.

  - 0x06: heritage says FIND_DEVICE (LED 10 s). QRing names it CMD_MUTE (DnD). Low risk.
  - 0x10: heritage says BLINK_TWICE (quick 2-blink). QRing names it CMD_BIND_SUCCESS (silent ACK). Low risk.

⚠ `0x0F` (heritage says SHUTDOWN; QRing says TO_OTA bootloader) IS NOT TESTED HERE. There is no
known-good R08 firmware backup, so OTA mode would brick the ring. The opcode stays documented in
Doc/02 §6 as "DO NOT TEST" until / unless we have a working OTA recovery path. See Doc/16 §2.

Usage:
  python r08_09_contested.py --probe 0x06
  python r08_09_contested.py --probe 0x10
"""

from __future__ import annotations

import argparse
import asyncio
import sys

from bleak import BleakClient
from r08_lib import (
    NOTIFY_CHAR, find_ring,
    NotifyLog, StageVerdict, print_stage_card, print_verdict_block,
    cmd, send_and_grade, grade_prompt,
)


CONTESTED = {
    "0x06": {
        "packet":   cmd(0x06),
        "listen":   12.0,
        "heritage": "FIND_DEVICE — LED blinks for ~10 s",
        "qring":    "CMD_MUTE — ring enters DnD (notifications suppressed)",
    },
    "0x10": {
        "packet":   cmd(0x10),
        "listen":   3.0,
        "heritage": "BLINK_TWICE — quick 2-blink LED",
        "qring":    "CMD_BIND_SUCCESS — silent ACK (no visible action)",
    },
}


async def run(args) -> int:
    if args.probe not in CONTESTED:
        print(f"✗ unknown probe '{args.probe}'; pick one of {list(CONTESTED)}")
        return 1
    info = CONTESTED[args.probe]

    print_stage_card(
        stage_num=9, name=f"Contested {args.probe}",
        time_min=3, power_pct="<0.01%",
        pre_flight=[
            "Wear the ring on a finger.",
            "Have a clear view of the LED (look at the ring directly).",
        ],
        during=[
            f"Script sends {args.probe} once.",
            f"Watch the ring for the next {info['listen']:.0f} seconds.",
            "Note: LED blink? Long blink? Notifications muted? Vibration? Nothing?",
            "Type one letter at the grade prompt:",
            "  q = matches QRing's prediction",
            "  h = matches heritage prediction",
            "  ? = neither / something else (add free-text note)",
        ],
        success=(
            f"Heritage predicts: {info['heritage']}\n"
            f"  QRing predicts:    {info['qring']}"
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

        await send_and_grade(client, args.probe, info["packet"], log, listen_s=info["listen"])

        ans, notes = await grade_prompt(
            args.probe,
            expected_qring=info["qring"],
            expected_xiaozhu="(not in 小猪)",
        )

        try: await client.stop_notify(NOTIFY_CHAR)
        except Exception: pass

    print_verdict_block(f"9 — Contested {args.probe}", [
        StageVerdict(args.probe, info["qring"], info["heritage"], ans, notes),
    ])
    log.flush_csv()
    return 0


def main():
    p = argparse.ArgumentParser(
        description="Stage 9: contested opcodes (0x06 + 0x10 only — 0x0F intentionally omitted)",
    )
    p.add_argument("--mac")
    p.add_argument("--scan-timeout", type=float, default=8.0)
    p.add_argument("--probe", required=True, choices=list(CONTESTED),
                   help="Which contested opcode to test (0x0F is intentionally excluded; see Doc/16 §2).")
    p.add_argument("--record")
    args = p.parse_args()
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main() or 0)
