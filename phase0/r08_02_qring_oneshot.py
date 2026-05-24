#!/usr/bin/env python3
"""
Stage 2 — QRing one-shot active queries (0x48, 0x50 AA AA, 0x08 01).

Safe, single-write each. Grade what happens.

Usage:
  python r08_02_qring_oneshot.py
  python r08_02_qring_oneshot.py --skip-reboot     # don't test 0x08 01
"""

from __future__ import annotations

import argparse
import asyncio
import sys

from bleak import BleakClient
from r08_lib import (
    NOTIFY_CHAR, find_ring,
    NotifyLog, send_and_grade, grade_prompt, StageVerdict,
    print_stage_card, print_verdict_block,
    GET_STEP_TODAY, FIND_DEVICE_QRING, REBOOT_QRING,
)


async def run(args) -> int:
    print_stage_card(
        stage_num=2, name="QRing one-shot active queries",
        time_min=5, power_pct="<0.01%",
        pre_flight=[
            "Ring on a finger (or at least off the cradle).",
            "Quiet environment so you can hear / feel vibration during the 0x50 AA AA test.",
        ],
        during=[
            "Three queries in sequence:",
            "  1) 0x48 GET_STEP_TODAY → terminal prints a 14-byte response",
            "  2) 0x50 AA AA → watch the ring for vibration + LED blink",
            "  3) 0x08 01 REBOOT (gated YES/SKIP) → ring should briefly disconnect",
            "After each: grade prompt (q / ? + free-text note).",
        ],
        success=(
            "✓ 0x48 → 14-byte response decoded as 'steps=N run=N cal=N dist=Nm dur=N'\n"
            "✓ 0x50 AA AA → felt vibration AND saw LED blink within ~3 s\n"
            "✓ 0x08 01 → ring disconnects + reconnects within ~5 s (skip is fine)"
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
        loop = asyncio.get_running_loop()

        # 1. 0x48 today totals
        await send_and_grade(client, "0x48 GET_STEP_TODAY", GET_STEP_TODAY, log, listen_s=2.5)
        ans, notes = await grade_prompt(
            "0x48 today totals",
            expected_qring="`48 <14 bytes BE: steps, run-steps, cal, dist(m), dur(min)>`",
            expected_xiaozhu="(not in 小猪 — 小猪 only sees 73 12 push)",
        )
        verdicts.append(StageVerdict("0x48 today totals", "48 + 14-byte BE",
                                     "(not in 小猪)", ans, notes))

        # 2. 0x50 AA AA find-device
        print("\n— Watch the ring for vibration + LED blink in the next 5 s —")
        await send_and_grade(client, "0x50 AA AA ANTI_LOST_RATE", FIND_DEVICE_QRING, log,
                             listen_s=5.0)
        ans, notes = await grade_prompt(
            "0x50 AA AA find-device",
            expected_qring="ring vibrates + LED blinks",
            expected_xiaozhu="(not in 小猪)",
        )
        verdicts.append(StageVerdict("0x50 AA AA find-device", "vibrate + LED blink",
                                     "(not in 小猪)", ans, notes))

        # 3. 0x08 01 reboot
        if not args.skip_reboot:
            print("\n!" * 72)
            print("⚠  0x08 01 RE_BOOT")
            print("  QRing claims this is a soft reboot — ring disconnects, then reconnects in ~3 s.")
            print("  If you see no disconnect, the byte does something else.")
            print("  Type YES to send, anything else to skip:")
            confirm = await loop.run_in_executor(None, sys.stdin.readline)
            if confirm.strip() == "YES":
                await send_and_grade(client, "0x08 01 RE_BOOT", REBOOT_QRING, log, listen_s=5.0)
                ans, notes = await grade_prompt(
                    "0x08 01 reboot",
                    expected_qring="ring disconnects briefly then reconnects",
                    expected_xiaozhu="(not in 小猪)",
                )
                verdicts.append(StageVerdict("0x08 01 reboot", "soft reboot",
                                             "(not in 小猪)", ans, notes))
            else:
                print("  (skipped reboot)")
                verdicts.append(StageVerdict("0x08 01 reboot", "soft reboot",
                                             "(not in 小猪)", "?", "(skipped)"))

        try: await client.stop_notify(NOTIFY_CHAR)
        except Exception: pass

    print_verdict_block("2 — QRing one-shots", verdicts)
    log.flush_csv()
    return 0


def main():
    p = argparse.ArgumentParser(description="Stage 2: QRing one-shots (0x48, 0x50 AA AA, 0x08 01)")
    p.add_argument("--mac")
    p.add_argument("--scan-timeout", type=float, default=8.0)
    p.add_argument("--skip-reboot", action="store_true",
                   help="Skip the 0x08 01 reboot probe")
    p.add_argument("--record")
    args = p.parse_args()
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main() or 0)
