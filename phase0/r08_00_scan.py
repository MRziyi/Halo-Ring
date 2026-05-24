#!/usr/bin/env python3
"""
Stage 0 — Sanity (NO writes).

Verifies the GATT layout matches Doc/02 §2 + observes whether the ring pushes anything on its own.

Usage:
  python r08_00_scan.py
  python r08_00_scan.py --mac AA:BB:CC:DD:EE:FF
  python r08_00_scan.py --listen 30      # extend the passive listen window
"""

from __future__ import annotations

import argparse
import asyncio
import sys

from bleak import BleakClient
from r08_lib import (
    SERVICE_UUID, WRITE_CHAR, NOTIFY_CHAR, find_ring,
    NotifyLog, print_stage_card, print_verdict_block, StageVerdict,
)


async def run(args) -> int:
    print_stage_card(
        stage_num=0, name="Sanity (no writes)",
        time_min=5, power_pct="0%",
        pre_flight=[
            "Ring is awake (just tap it if it's been idle a while).",
            "Bluetooth on; this script's terminal has BT permission.",
        ],
        during=[
            "Script scans for the ring, connects, and prints the GATT service tree.",
            f"Then listens passively for {args.listen:.0f} s — no writes.",
            "Wear the ring naturally during the listen. Move your hand a bit.",
            "(Just watch — no prompts to answer.)",
        ],
        success=(
            "✓ scan finds R08_xxxx within ~8 s\n"
            "✓ service 6e40fff0-… + write/notify chars + CCCD present\n"
            "✓ at least a battery frame appears spontaneously"
        ),
    )

    addr = args.mac
    if not addr:
        dev = await find_ring(args.scan_timeout)
        if dev is None:
            print("\n✗ Could not find ring. If it was just removed from cradle, wait 5 s and retry.")
            return 2
        addr = dev.address

    log = NotifyLog()
    log.csv_path = args.record
    log.print_filter = None   # print everything in Stage 0 — we want to see all baseline frames

    print(f"\n⌕ Connecting to {addr}…")
    async with BleakClient(addr) as client:
        print("  ✓ connected")

        # Verify GATT layout
        svcs = list(client.services)
        ours = next((s for s in svcs if s.uuid.lower() == SERVICE_UUID), None)
        if ours is None:
            print(f"\n✗ Service {SERVICE_UUID} not found! Services seen:")
            for s in svcs:
                print(f"    {s.uuid}")
            return 3
        char_uuids = [c.uuid.lower() for c in ours.characteristics]
        print(f"\n  ✓ service {SERVICE_UUID}")
        print(f"    characteristics: {char_uuids}")

        has_write  = WRITE_CHAR.lower() in char_uuids
        has_notify = NOTIFY_CHAR.lower() in char_uuids
        print(f"    write char ({WRITE_CHAR}): {'✓' if has_write else '✗ MISSING'}")
        print(f"    notify char ({NOTIFY_CHAR}): {'✓' if has_notify else '✗ MISSING'}")
        if not (has_write and has_notify):
            return 4

        # Subscribe
        await client.start_notify(NOTIFY_CHAR, await_handler := log.attach_handler(client))
        print(f"  ✓ subscribed to notify")

        # Passive listen
        print(f"\n— Listening passively for {args.listen} s (no writes) —")
        print(f"  Wear the ring naturally. Move your hand occasionally.")
        await asyncio.sleep(args.listen)

        try: await client.stop_notify(NOTIFY_CHAR)
        except Exception: pass

    # Verdict block
    tally = log.tally()
    notes = [
        f"Service UUID present: ✓",
        f"Write char present:   ✓",
        f"Notify char present:  ✓",
        f"Frames observed during {args.listen}s passive listen:",
    ]
    for kind, n in sorted(tally.items()):
        notes.append(f"  - {kind}: {n}")
    if not tally:
        notes.append("  (silent — ring may be in deep auto-sleep; tap it once and re-run)")

    verdicts = [
        StageVerdict("GATT layout", expected_qring := "matches Doc/02 §2", answer="q",
                     notes="(automatically resolved)"),
    ]
    print_verdict_block("0 — Sanity", verdicts, passive_notes=notes)

    log.flush_csv()
    return 0


def main():
    p = argparse.ArgumentParser(description="Stage 0 sanity — GATT layout + baseline listen")
    p.add_argument("--mac", help="Skip scanning, connect directly to this MAC")
    p.add_argument("--scan-timeout", type=float, default=8.0)
    p.add_argument("--listen", type=float, default=10.0,
                   help="Passive listen window in seconds (default: 10)")
    p.add_argument("--record", help="Write every notify frame to this CSV")
    args = p.parse_args()
    try:
        return asyncio.run(run(args))
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main() or 0)
