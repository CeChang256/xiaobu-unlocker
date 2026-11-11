#!/usr/bin/env python3
"""Read or edit the module's layout config inside the SharedPreferences XML.

The module stores everything in one JSON string (key ``layout_json``) inside
``/data/data/com.xiaobu.unlocker/shared_prefs/xiaobu_unlocker.xml``. Editing it
by hand is error-prone because the JSON is HTML-escaped; this script does the
round-trip for you.

Examples
--------
    # show the current config
    python layout_prefs.py show prefs.xml

    # set both columns (serviceId / name / type / size)
    python layout_prefs.py set-cols prefs.xml --left 536878636 运动计步 \
        --right 536878384 天气时钟

    # flip a feature flag (bool / int / string are auto-detected)
    python layout_prefs.py set-feature prefs.xml roundms=1200

    # write into a different file
    python layout_prefs.py set-cols prefs.xml -o out.xml --right 536875395 月历

Pull / push around the edit (the running app would otherwise overwrite it):

    adb shell su -c 'am force-stop com.xiaobu.unlocker'
    adb pull /data/data/com.xiaobu.unlocker/shared_prefs/xiaobu_unlocker.xml prefs.xml
    python layout_prefs.py set-cols prefs.xml --left 536878636 运动计步
    adb push prefs.xml /data/local/tmp/prefs.xml
    adb shell su -c 'cp /data/local/tmp/prefs.xml \
        /data/data/com.xiaobu.unlocker/shared_prefs/xiaobu_unlocker.xml'
    adb shell su -c 'chown u0_a999:u0_a999 \
        /data/data/com.xiaobu.unlocker/shared_prefs/xiaobu_unlocker.xml'
    adb shell su -c 'am force-stop com.oplus.pantanal.ums'
"""
from __future__ import annotations

import argparse
import html
import json
import re
import sys
from pathlib import Path

KEY = "layout_json"
PATTERN = re.compile(r'(<string name="' + KEY + r'">)(.*?)(</string>)', re.S)
DEFAULT_TYPE = {"col_0": 100790, "col_1": 100515}


def read_xml(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def load_layout(xml: str) -> tuple[dict, re.Match]:
    m = PATTERN.search(xml)
    if not m:
        sys.exit("layout_json not found in this file")
    return json.loads(html.unescape(m.group(2))), m


def write_xml(xml: str, m: re.Match, layout: dict, out: Path) -> None:
    body = json.dumps(layout, ensure_ascii=False, separators=(",", ":"))
    escaped = (body.replace("&", "&amp;").replace("<", "&lt;")
               .replace(">", "&gt;").replace('"', "&quot;"))
    out.write_text(xml[:m.start(2)] + escaped + xml[m.end(2):],
                   encoding="utf-8", newline="")


def column(sid: str, name: str, type_code: int, size: int) -> dict:
    return {"serviceId": str(sid), "name": name, "type": type_code, "size": size}


def parse_feature(raw: str):
    key, _, value = raw.partition("=")
    if not key:
        sys.exit("feature must look like key=value: " + raw)
    low = value.lower()
    if low in ("true", "false"):
        return key, low == "true"
    if re.fullmatch(r"-?\d+", value):
        return key, int(value)
    return key, value


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="edit the module's layout_json")
    ap.add_argument("command", choices=["show", "set-cols", "set-feature", "set-mode"])
    ap.add_argument("prefs", type=Path, help="xiaobu_unlocker.xml")
    ap.add_argument("-o", "--out", type=Path, help="write here instead of in place")
    ap.add_argument("--left", nargs=2, metavar=("SID", "NAME"))
    ap.add_argument("--right", nargs=2, metavar=("SID", "NAME"))
    ap.add_argument("--type-left", type=int, default=DEFAULT_TYPE["col_0"])
    ap.add_argument("--type-right", type=int, default=DEFAULT_TYPE["col_1"])
    ap.add_argument("--size", type=int, default=1)
    ap.add_argument("--mode", choices=["custom", "native"])
    ap.add_argument("features", nargs="*", help="key=value pairs for set-feature")
    args = ap.parse_args(argv)

    xml = read_xml(args.prefs)
    layout, m = load_layout(xml)

    if args.command == "show":
        print(json.dumps(layout, ensure_ascii=False, indent=2))
        return 0

    tab = layout.setdefault("tab_0", {})

    if args.command == "set-cols":
        if not args.left and not args.right:
            sys.exit("set-cols needs --left and/or --right")
        if args.left:
            tab["col_0"] = column(args.left[0], args.left[1], args.type_left, args.size)
        if args.right:
            tab["col_1"] = column(args.right[0], args.right[1], args.type_right, args.size)
        # two identical cards make the host drop the whole group; refuse early.
        l_sid = (tab.get("col_0") or {}).get("serviceId")
        r_sid = (tab.get("col_1") or {}).get("serviceId")
        if l_sid and l_sid == r_sid:
            sys.exit("left and right are the same serviceId; the host would drop the group")

    elif args.command == "set-feature":
        if not args.features:
            sys.exit("set-feature needs at least one key=value")
        features = layout.setdefault("features", {})
        for raw in args.features:
            k, v = parse_feature(raw)
            features[k] = v

    elif args.command == "set-mode":
        if not args.mode:
            sys.exit("set-mode needs --mode custom|native")
        layout["mode"] = args.mode

    out = args.out or args.prefs
    write_xml(xml, m, layout, out)
    print("written", out)
    print(json.dumps(layout, ensure_ascii=False, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
