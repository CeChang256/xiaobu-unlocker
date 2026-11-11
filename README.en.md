# XiaoBu Unlocker (LSPosed)

Replace the two columns of the **OPPO / OnePlus 「小布建议」** (Breeno Suggestions)
home-screen card with any card from the system's own card pool.

No custom UI: the module hooks the built-in **“Edit default style”** editor, so you
pick cards exactly the way the system intends — the module just makes the *second*
column (normally a fixed “App suggestions” card) editable, and lets you swap the
two columns.

- Package: `com.xiaobu.unlocker`
- Version: **1.24 (18)**
- Hooked process: `com.oplus.pantanal.ums` only (the decision engine) — the launcher is **not** injected
- Verified on: OnePlus PLG110 · Android 17 · ColorOS `PLG110_17.0.0.105(CN01)` · LSPosed 2.1.1

## Features

| | |
|---|---|
| Independent left/right column | pick any card per column |
| Swap columns | exchanges both columns (a ~1s system card is visible; inherent to the mechanism) |
| Restore system style | save from the stock tab; your custom profile is kept |
| Editor UX | selection ring follows the column you edit; pool title shows “Left · card” / “Right · card” |
| Crash-safe | if the decision process is killed mid-swap, the desktop does not stay stuck on system cards |

## Install

1. Root + LSPosed (2.1.1 tested);
2. install the APK and enable the module — the scope is **statically declared**, so no manual picking;
3. reload the hooked process, otherwise the old dex keeps running:

   ```bash
   adb shell su -c 'am force-stop com.oplus.pantanal.ums'
   ```

4. Long-press the card → **Edit default style** → use the extra “系统工具+xxx” tab.

## Docs

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — data flow, the two hook points, the round-trip mechanism
- [docs/HARD-LESSONS.md](docs/HARD-LESSONS.md) — five conclusions that cost days; read before changing anything
- [docs/KNOWN-ISSUES.md](docs/KNOWN-ISSUES.md) — limits + the five pitfalls that waste a day of debugging
- [docs/CHANGELOG.md](docs/CHANGELOG.md)
- [build/README.md](build/README.md) — build without Gradle

## Licence

MIT. Not affiliated with OPPO / OnePlus / LSPosed. Modifying system decision data — use at your own risk.
