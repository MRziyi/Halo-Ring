#!/usr/bin/env python3
"""
Stage 6 — Auto-monitor settings (0x16 HR / 0x2C SpO2 / 0x36 stress / 0x38 HRV).

Confirms the "ring is the cadence master" mental model: phone tells the ring "auto-measure
HR every N min", ring measures internally, ring emits `0x73 0x01` sync trigger when ready.

This stage is mostly READ-test in practice — fully verifying the cadence requires waiting
N minutes for the trigger to fire. Use --observe to keep listening after the write.

Usage:
  python r08_06_auto_monitor.py --kind hr           # read current HR auto-monitor setting
  python r08_06_auto_monitor.py --kind hr --set 5   # set 5-min cadence, then watch 6 min for trigger
  python r08_06_auto_monitor.py --kind spo2
  python r08_06_auto_monitor.py --kind stress --set on
"""

from __future__ import annotations

import argparse
import asyncio
import sys
import time

from bleak import BleakClient
from r08_lib import (
    WRITE_CHAR, NOTIFY_CHAR, find_ring,
    NotifyLog, StageVerdict, print_stage_card, print_verdict_block,
    auto_monitor_read, auto_monitor_write, send_and_grade, grade_prompt,
    PREFIX_RING,
)


KIND_CMDS = {
    "hr":     (0x16, "HR auto-monitor",
               "write payload `{enable, intervalMin, startHr, low, high, mainSwitch}`",
               0x01),   # 0x73 sub-code emitted on new HR record
    "spo2":   (0x2C, "SpO2 auto-monitor",
               "write payload `{enable[, intervalMin]}`",
               0x03),   # 0x73 sub-code for new SpO2
    "stress": (0x36, "Stress/pressure auto-monitor",
               "write payload `{enable}`",
               0x2C),   # 0x73 sub-code for new stress
    "hrv":    (0x38, "HRV auto-monitor",
               "write payload `{enable}`",
               0x2B),   # 0x73 sub-code for new HRV
}


def build_write_payload(kind: str, set_arg: str) -> bytes:
    """Translate user-friendly --set arg into a write payload body (excluding the leading 0x02)."""
    if kind == "hr":
        # intervalMin from --set
        try:
            interval = int(set_arg)
        except ValueError:
            interval = 30
        # {enable=1, intervalMin, startHr=8, low=50, high=200, mainSwitch=1}
        return bytes([1, interval, 8, 50, 200, 1])
    else:
        # spo2 / stress / hrv: simple on/off
        enable = 1 if set_arg.lower() in ("on", "1", "true", "yes") else 0
        if kind == "spo2":
            return bytes([enable, 30])   # 30-min interval default
        return bytes([enable])


async def run(args) -> int:
    if args.kind not in KIND_CMDS:
        print(f"✗ unknown kind '{args.kind}'; pick one of {list(KIND_CMDS)}")
        return 1
    cmd_code, label, write_doc, trigger_sub = KIND_CMDS[args.kind]

    during = [
        f"1) Script reads current {label} settings (0x{cmd_code:02x} READ).",
        f"   Watch the response payload format — that's the schema for writes.",
        f"   At the grade prompt, type q if the response decoded sensibly.",
    ]
    if args.set is not None:
        during.append(f"2) Script writes new settings: {label} → {args.set}")
        during.append(f"   Watch for a 0x{cmd_code:02x} ACK frame.")
        if args.observe > 0:
            mm = int(args.observe // 60)
            during.append(f"3) Script listens for {args.observe:.0f} s ({mm} min)")
            during.append(f"   Looking for `0x73 0x{trigger_sub:02x}` sync triggers (ring's auto-measurement push).")
    else:
        during.append("(Pass --set <value> to also write a new config and observe triggers.)")

    print_stage_card(
        stage_num=6, name=f"Auto-monitor settings ({args.kind})",
        time_min=2 if args.observe == 0 else (2 + args.observe / 60.0),
        power_pct="<0.01%",
        pre_flight=[
            "Ring on a finger (or near you — auto-monitor needs the ring active).",
            f"For HR/SpO2/stress: --set <interval-min> writes 30-min default if omitted.",
        ],
        during=during,
        success=(
            f"✓ 0x{cmd_code:02x} READ → response with the configured settings\n"
            f"✓ 0x{cmd_code:02x} WRITE → ACK\n"
            f"✓ if --observe > 0 : at least one `0x73 0x{trigger_sub:02x}` trigger fires"
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

        # 1. Read current settings
        read_pkt = auto_monitor_read(cmd_code)
        await send_and_grade(client, f"0x{cmd_code:02x} READ — {label}", read_pkt, log,
                             listen_s=2.0)
        ans, notes = await grade_prompt(
            f"0x{cmd_code:02x} read response",
            expected_qring=f"`{cmd_code:02x} <settings echo>`  ({write_doc})",
            expected_xiaozhu="(not in 小猪)",
        )
        verdicts.append(StageVerdict(f"0x{cmd_code:02x} READ {args.kind}",
                                     "settings echo received", "(not in 小猪)", ans, notes))

        # 2. Write
        if args.set is not None:
            body = build_write_payload(args.kind, args.set)
            write_pkt = auto_monitor_write(cmd_code, body)
            await send_and_grade(client, f"0x{cmd_code:02x} WRITE — body={body.hex(' ')}",
                                 write_pkt, log, listen_s=2.0)
            ans, notes = await grade_prompt(
                f"0x{cmd_code:02x} write ACK",
                expected_qring=f"ACK frame `{cmd_code:02x} …`",
                expected_xiaozhu="(not in 小猪)",
            )
            verdicts.append(StageVerdict(f"0x{cmd_code:02x} WRITE {args.kind}",
                                         "write ACK received", "(not in 小猪)", ans, notes))

            # 3. If observe requested, wait for the 0x73 trigger
            if args.observe > 0:
                print(f"\n— OBSERVING for {args.observe} s — looking for `0x73 0x{trigger_sub:02x}` sync trigger —")
                start_idx = len(log.frames)
                await asyncio.sleep(args.observe)
                seen = [
                    (ms, raw) for ms, raw, _ in log.frames[start_idx:]
                    if len(raw) >= 2 and (raw[0] & 0x7F) == PREFIX_RING and raw[1] == trigger_sub
                ]
                print(f"  → saw {len(seen)} × `73 0x{trigger_sub:02x}` trigger")
                verdicts.append(StageVerdict(f"0x73 0x{trigger_sub:02x} cadence trigger",
                                             f"fires within configured interval",
                                             "(not in 小猪)",
                                             "q" if seen else "?",
                                             f"observed {len(seen)} triggers in {args.observe}s"))

        try: await client.stop_notify(NOTIFY_CHAR)
        except Exception: pass

    print_verdict_block(f"6 — Auto-monitor settings ({args.kind})", verdicts)
    log.flush_csv()
    return 0


def main():
    p = argparse.ArgumentParser(description="Stage 6: auto-monitor settings (0x16/0x2C/0x36/0x38)")
    p.add_argument("--mac")
    p.add_argument("--scan-timeout", type=float, default=8.0)
    p.add_argument("--kind", required=True, choices=list(KIND_CMDS),
                   help="Which auto-monitor to read/write")
    p.add_argument("--set", default=None,
                   help="hr: interval in minutes (5/10/30/60). spo2/stress/hrv: 'on'/'off'")
    p.add_argument("--observe", type=float, default=0.0,
                   help="After writing, listen this many seconds for the 0x73 sync trigger")
    p.add_argument("--record")
    args = p.parse_args()
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main() or 0)
