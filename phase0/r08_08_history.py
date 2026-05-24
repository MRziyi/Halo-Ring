#!/usr/bin/env python3
"""
Stage 8 — History multi-packet reads.

Tests QRing's history-fetch protocol for HR (0x15) / HRV (0x39) / stress (0x37) /
step-detail (0x43) / sleep (0x44). All are multi-packet streams terminated by either
`<cmd> FF` or `<cmd> <pktCount-1>`.

Usage:
  python r08_08_history.py --kind hr        # 0x15, today
  python r08_08_history.py --kind hrv
  python r08_08_history.py --kind stress
  python r08_08_history.py --kind steps     # 0x43
  python r08_08_history.py --kind sleep     # 0x44, today
"""

from __future__ import annotations

import argparse
import asyncio
import sys

from bleak import BleakClient
from r08_lib import (
    WRITE_CHAR, NOTIFY_CHAR, find_ring,
    NotifyLog, StageVerdict, print_stage_card, print_verdict_block,
    hr_history_read, hrv_history_read, stress_history_read,
    step_history_read, sleep_history_read,
    today_midnight_unix,
)


KIND_BUILDERS = {
    "hr":     (0x15, lambda: hr_history_read(today_midnight_unix())),
    "hrv":    (0x39, lambda: hrv_history_read(0)),
    "stress": (0x37, lambda: stress_history_read(today_midnight_unix())),
    "steps":  (0x43, lambda: step_history_read(0)),
    "sleep":  (0x44, lambda: sleep_history_read(0)),
}


async def run(args) -> int:
    if args.kind not in KIND_BUILDERS:
        print(f"✗ unknown kind '{args.kind}'; pick one of {list(KIND_BUILDERS)}")
        return 1
    cmd_code, builder = KIND_BUILDERS[args.kind]

    print_stage_card(
        stage_num=8, name=f"History reads ({args.kind})",
        time_min=args.timeout / 60.0 + 0.5, power_pct="0%",
        pre_flight=[
            "Ring on a finger or near it (just needs to be connected).",
            "For hr/stress/sleep: the more you've worn the ring TODAY, the more data exists.",
            "If you just got the ring, expect empty responses (terminator only).",
        ],
        during=[
            f"Script writes 0x{cmd_code:02x} once and then listens up to {args.timeout:.0f} s for the response stream.",
            "Multi-packet protocol: header packet (`<cmd> 00 <count> <range>`)",
            "then data packets, then terminator (`<cmd> FF` or last-pkt).",
            "Just watch the terminal — no interactive grading.",
        ],
        success=(
            "✓ header packet appears within ~1 s\n"
            "✓ packet_count in the header matches the number of data packets actually received\n"
            "✓ terminator (0xFF or last-numbered) closes the stream"
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

        packet = builder()
        print(f"→ history_read({args.kind}, 0x{cmd_code:02x}): {packet.hex(' ')}")
        start_idx = len(log.frames)
        await client.write_gatt_char(WRITE_CHAR, packet, response=False)

        # Drain frames until we see the terminator or hit timeout
        print(f"  → buffering response (up to {args.timeout:.0f} s)…")
        deadline = asyncio.get_running_loop().time() + args.timeout
        terminator_seen = False
        while asyncio.get_running_loop().time() < deadline and not terminator_seen:
            await asyncio.sleep(0.2)
            for _, raw, _ in log.frames[start_idx:]:
                if len(raw) >= 2 and (raw[0] & 0x7F) == cmd_code:
                    sub = raw[1]
                    if sub == 0xFF:
                        terminator_seen = True
                        break

        try: await client.stop_notify(NOTIFY_CHAR)
        except Exception: pass

    # Analyse
    rsp = [(ms, raw) for ms, raw, _ in log.frames[start_idx:]
           if len(raw) >= 2 and (raw[0] & 0x7F) == cmd_code]
    print(f"\n— history read summary —")
    print(f"  total {cmd_code:02x} packets: {len(rsp)}")
    if len(rsp) == 0:
        print(f"  (no response — kind may not be supported, or no data for the requested range)")
        notes = "(no response)"
    elif len(rsp) == 1 and rsp[0][1][1] == 0xFF:
        print(f"  → {cmd_code:02x} FF terminator only — ring has no data for this range")
        notes = "terminator-only (no data)"
    else:
        # Try to read header info
        header = rsp[0][1]
        print(f"  header packet: {header.hex(' ')}")
        if len(header) >= 4:
            print(f"    sub={header[1]:#04x}  pkt_count={header[2]}  bin_minutes/range={header[3]}")
        # Show last packet (terminator if seen)
        last = rsp[-1][1]
        print(f"  last packet:   {last.hex(' ')}")
        notes = f"{len(rsp)} packets, header_sub=0x{header[1]:02x}"

    verdicts = [
        StageVerdict(f"history({args.kind}) multi-packet protocol",
                     f"`{cmd_code:02x} 00 <count> <range>` header + N data + `{cmd_code:02x} FF` end",
                     "(not in 小猪)",
                     "q" if (len(rsp) > 0) else "?",
                     notes),
    ]
    print_verdict_block(f"8 — History reads ({args.kind})", verdicts)
    log.flush_csv()
    return 0


def main():
    p = argparse.ArgumentParser(description="Stage 8: history multi-packet reads")
    p.add_argument("--mac")
    p.add_argument("--scan-timeout", type=float, default=8.0)
    p.add_argument("--kind", required=True, choices=list(KIND_BUILDERS))
    p.add_argument("--timeout", type=float, default=10.0,
                   help="How long to wait for the response stream (default: 10 s)")
    p.add_argument("--record")
    args = p.parse_args()
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main() or 0)
