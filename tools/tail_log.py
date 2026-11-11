#!/usr/bin/env python3
"""Slice the module's logcat output.

    adb logcat -d -v time > all.log
    python tail_log.py all.log 12:30 dswap 200

Arguments: <file> [start-time] [filter] [limit]

Only lines containing ``XiaoBuUnlocker`` are considered; ``start-time`` is a
plain string compare against the ``HH:MM:SS.mmm`` timestamp, so ``12:30``
selects everything at or after 12:30.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

TS = re.compile(r"(\d\d:\d\d:\d\d\.\d+)")
TAG = "XiaoBuUnlocker"


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    path = Path(argv[0])
    start = argv[1] if len(argv) > 1 else "00:00"
    needle = argv[2] if len(argv) > 2 else ""
    limit = int(argv[3]) if len(argv) > 3 else 200

    rows = []
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        if TAG not in line:
            continue
        if needle and needle not in line:
            continue
        m = TS.search(line)
        stamp = m.group(1) if m else "??"
        message = line.split(TAG + ": ", 1)[-1]
        rows.append((stamp, message))

    selected = [r for r in rows if r[0] >= start]
    print(f"total {len(rows)}, after {start} {len(selected)}")
    for stamp, message in selected[:limit]:
        print(f"{stamp} | {message}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
