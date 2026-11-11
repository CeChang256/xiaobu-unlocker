#!/system/bin/sh
# Make a freshly installed/enabled module take effect without rebooting.
#
# Why this is enough: LSPosed injects at process start, so the hooked process
# must be recreated. com.oplus.pantanal.ums is a normal (non-persistent) app:
# force-stop kills every one of its subprocesses (:cardconfig, :uiSetting,
# :aiflow, :analyzer, ...), and returning to the launcher makes the widget bind
# again, which starts the decision process with the new module config.
#
# Usage (root shell):
#   sh refresh.sh            # ums only
#   sh refresh.sh --launcher # also restart the launcher (only if the card still
#                            # shows stale content; it clears the binding cache)
set -u

PKG=com.oplus.pantanal.ums

echo "before:"
pidof $PKG 2>/dev/null | tr ' ' '\n' | sed 's/^/  pid /'

am force-stop $PKG 2>/dev/null
sleep 1

if [ "${1:-}" = "--launcher" ]; then
    LP=$(pidof com.android.launcher)
    if [ -n "$LP" ]; then
        # kill, do not force-stop: force-stop would drop the HOME role on
        # ColorOS and the desktop would not come back by itself.
        kill -9 $LP 2>/dev/null
        echo "launcher restarted (was $LP)"
    fi
fi

# Bring the launcher to front; the group-card binding restarts the decision
# process on its own.
input keyevent KEYCODE_HOME 2>/dev/null
sleep 3

echo "after:"
pidof $PKG 2>/dev/null | tr ' ' '\n' | sed 's/^/  pid /'
echo "done — check the hook log:"
echo "  su -c 'tail -5 /data/data/io.github.cechang256.xiaobu/files/diag.log'"
