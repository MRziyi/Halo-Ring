#!/usr/bin/env python3
"""
Stage 1 — QRing connect recipe (0x01 SetTime + 0x3C DeviceFunctionSupport).

Both writes are single-shot and trivial cost (~2 × 16 bytes). The 0x3C capability bitmap gates
which features the rest of phase-0 can usefully test, so this stage runs first after sanity.

Also probes whether the 0x03 BATTERY response carries the QRing-claimed `isCharging` byte
(by comparing one reading with the ring on the cradle and another off).

Usage:
  python r08_01_qring_connect.py
  python r08_01_qring_connect.py --mac AA:BB:...
  python r08_01_qring_connect.py --skip-charging   # don't ask about cradle on/off
"""

from __future__ import annotations

import argparse
import asyncio
import sys

from bleak import BleakClient
from r08_lib import (
    WRITE_CHAR, NOTIFY_CHAR, find_ring,
    NotifyLog, send_and_grade, grade_prompt, StageVerdict,
    print_stage_card, print_verdict_block,
    set_time_now_cmd, DEVICE_FUNCTION_SUPP, BATTERY_QUERY,
)


async def run(args) -> int:
    print_stage_card(
        stage_num=1, name="QRing connect recipe",
        time_min=10, power_pct="<0.01%",
        pre_flight=[
            "Ring on a finger AND not on the cradle (for the first battery reading).",
            "If you can put the ring on/off the cradle quickly, the charging-byte probe is more useful.",
        ],
        during=[
            "Script writes 0x01 SetTime, then 0x3C DeviceFunctionSupport.",
            "After each write, watch the terminal for the response frame + grade prompt.",
            "At the grade prompt type a single letter and press Enter:",
            "  q  = ring responded as QRing predicted (e.g. 9-byte bitmap)",
            "  ?  = no response / different response (add free-text note after the letter)",
            "Then the battery probe: 1st reading off-cradle, 2nd reading on-cradle.",
            "Look at the response bytes printed in the terminal — count them.",
        ],
        success=(
            "✓ 0x01 → ACK frame `01 …` appears within 2 s\n"
            "✓ 0x3C → response is 11 bytes (`3C` + 9 bytes payload + 1 byte checksum-or-padding)\n"
            "✓ 0x03 battery → payload is 3 bytes (level + charging), and byte[2] flips on cradle"
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
        # small settle
        await asyncio.sleep(0.5)

        verdicts: list[StageVerdict] = []
        loop = asyncio.get_running_loop()

        # 1. SetTime
        set_time_pkt = set_time_now_cmd()
        await send_and_grade(client, "0x01 SetTime", set_time_pkt, log, listen_s=2.0)
        ans, notes = await grade_prompt(
            "0x01 SetTime",
            expected_qring="ACK frame `01 …` (capability echo, up to 15 bytes)",
            expected_xiaozhu="(not in 小猪)",
        )
        verdicts.append(StageVerdict("0x01 SetTime", "01 ACK", "(not in 小猪)", ans, notes))

        # 2. DeviceFunctionSupport
        await send_and_grade(client, "0x3C DeviceFunctionSupport", DEVICE_FUNCTION_SUPP, log,
                             listen_s=2.0)
        ans, notes = await grade_prompt(
            "0x3C DeviceFunctionSupport",
            expected_qring="`3C <9 bytes capability bitmap>` (decoded in Doc/17 §6)",
            expected_xiaozhu="(not in 小猪)",
        )
        verdicts.append(StageVerdict("0x3C DeviceFunctionSupport", "3C + 9-byte bitmap",
                                     "(not in 小猪)", ans, notes))

        # 3. Battery — first reading
        print("\n— Battery probe: first reading (note ring state — cradle? on finger?) —")
        await send_and_grade(client, "0x03 BATTERY_QUERY", BATTERY_QUERY, log, listen_s=2.0)
        ans1, notes1 = await grade_prompt(
            "0x03 Battery response (first reading)",
            expected_qring="`03 <level%> <isCharging>`  (3 bytes payload)",
            expected_xiaozhu="`03 <level%>`  (2 bytes payload, no charging byte)",
        )
        verdicts.append(StageVerdict("0x03 Battery payload shape", "3 bytes (level + charging)",
                                     "2 bytes (level only)", ans1, notes1))

        # 4. Battery — second reading after state change
        if not args.skip_charging:
            print("\n— Battery probe: second reading. Change the ring state (put on cradle or remove). —")
            print("  Then press Enter:")
            await loop.run_in_executor(None, sys.stdin.readline)
            await send_and_grade(client, "0x03 BATTERY_QUERY (2nd)", BATTERY_QUERY, log,
                                 listen_s=2.0)
            print("\n  → Did the charging byte (if present) flip?")
            ans2, notes2 = await grade_prompt(
                "0x03 charging-byte responsiveness",
                expected_qring="byte [2] changes between cradle-on and cradle-off",
                expected_xiaozhu="(no charging byte to flip)",
            )
            verdicts.append(StageVerdict("0x03 charging-byte responsive", "yes, byte [2] changes",
                                         "no charging byte", ans2, notes2))

        try: await client.stop_notify(NOTIFY_CHAR)
        except Exception: pass

    print_verdict_block("1 — QRing connect recipe", verdicts)
    log.flush_csv()
    return 0


def main():
    p = argparse.ArgumentParser(description="Stage 1: QRing connect recipe (0x01, 0x3C, 0x03)")
    p.add_argument("--mac")
    p.add_argument("--scan-timeout", type=float, default=8.0)
    p.add_argument("--skip-charging", action="store_true",
                   help="Skip the cradle-on/off charging-byte probe")
    p.add_argument("--record", help="Write every frame to this CSV")
    args = p.parse_args()
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main() or 0)
