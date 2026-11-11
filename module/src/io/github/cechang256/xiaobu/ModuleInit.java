package io.github.cechang256.xiaobu;

import android.content.Context;
import android.os.Bundle;

import io.github.cechang256.xiaobu.xp.XposedBridge;

/**
 * Module installation entry.
 *
 * <p>Only one target process is needed: {@code com.oplus.pantanal.ums}
 * (decision engine + ranking + editor UI). The scope is declared statically in
 * {@code META-INF/xposed/scope.list} together with {@code staticScope=true} in
 * {@code module.prop}, so the manager cannot widen it.
 *
 * <p>This class is no longer the Xposed entry point; {@link ModernEntry} is.
 * It stays responsible for wiring the hooks so the rest of the module does not
 * have to know about the framework flavour.
 */
public final class ModuleInit {

    static final String TAG = "XiaoBuUnlocker";
    static final String TARGET = "com.oplus.pantanal.ums";

    private ModuleInit() {
    }

    /** Version string, used in logs and the process registry. */
    static String version() {
        try {
            return BuildInfo.version(AppContext.get());
        } catch (Throwable t) {
            return BuildInfo.versionOrFallback();
        }
    }

    /** Log helper usable from any class without importing the Xposed API. */
    static void log(String msg) {
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Installs every hook for one loaded package.
     *
     * @param pkg         package name of the process
     * @param processName process name ({@code pkg} for the main process)
     * @param cl          the package's class loader
     * @param first       true for the first application of the process
     */
    static void install(String pkg, String processName, ClassLoader cl, boolean first) {
        if (pkg == null) {
            return;
        }
        if (!TARGET.equals(pkg)) {
            // With a static scope this must not happen; log it as evidence that
            // the scope really is narrow.
            XposedBridge.log(TAG + ": loaded into " + pkg + " process=" + processName
                    + " (out of scope, ignored)");
            return;
        }
        XposedBridge.log(TAG + ": loaded into " + pkg + " process=" + processName
                + " first=" + first);
        try {
            AppContext.install(cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": context hook failed " + t);
        }
        installModel(cl);
        try {
            SceneServiceRewriter.install(cl);
        } catch (Throwable t) {
            Diag.log("dswap", "install failed " + t);
        }
        try {
            EditorHook.install(cl);
        } catch (Throwable t) {
            Diag.log("editor", "install failed " + t);
        }
        try {
            welcome(pkg, processName);
        } catch (Throwable ignored) {
        }
    }

    private static void installModel(ClassLoader cl) {
        try {
            DefaultServiceHook.install(cl);
        } catch (Throwable t) {
            Diag.log("init", "model hook failed " + t);
        }
    }

    /**
     * Tells the UI which processes actually loaded the module.
     *
     * <p>Package loading can happen before the Application exists, so the
     * Context may still be null here; retry on a short-lived thread instead of
     * giving up, otherwise the process registry stays empty until restart.
     */
    private static void welcome(final String pkg, final String processName) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                // A system Context is NOT good enough: a provider call
                // attributed to package "android" fails the same way
                // registerReceiver does on Android 17. Wait for the Application.
                for (int i = 0; i < 120; i++) {
                    if (AppContext.hasApplication()) {
                        Context c = AppContext.get();
                        if (c != null) {
                            try {
                                sendHello(c, pkg, processName);
                            } catch (Throwable t) {
                                Diag.log("init", "hello failed " + t);
                            }
                            afterApplication(pkg, processName);
                            return;
                        }
                    }
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "xiaobu-hello").start();
    }

    /**
     * Work that needs a real Application Context rather than the system one.
     */
    private static void afterApplication(String pkg, String processName) {
        if (!TARGET.equals(pkg) || !AppContext.hasApplication()) {
            return;
        }
        // Boot-time refresh only in the main process: the child processes
        // (aiflow / cardconfig) do not push decision notifications, so a second
        // notification would only add boot load. Main process = process name
        // equals the package name.
        if (processName == null || processName.equals(pkg)) {
            try {
                healInterruptedRoundTrip();
            } catch (Throwable t) {
                Diag.log("init", "self-heal failed " + t);
            }
            try {
                EditorHook.bootKickNotify();
            } catch (Throwable t) {
                Diag.log("init", "boot kick failed " + t);
            }
        }
    }

    /**
     * Startup self-heal for an interrupted swap round-trip.
     *
     * <p>The swap writes {@code roundtrip_at} and flips the mode to native so
     * the custom cards leave; the data path then flips it back. If the process
     * dies in between (the editor lives in ums, so closing it right after a
     * save is the risky moment), the native state would stick forever.
     *
     * <ul>
     *   <li><b>Read side</b>: {@link LayoutStore#mode} returns custom on its own
     *       once the marker is older than the hold window.</li>
     *   <li><b>Startup</b>: this method removes a leftover marker so the stored
     *       state is clean again.</li>
     * </ul>
     *
     * <p>The decision is based on the marker being present, not on the mode
     * value: a user who picks the stock tab has {@code mode=native} without the
     * marker and is never touched here.
     */
    private static void healInterruptedRoundTrip() {
        Context c = AppContext.get();
        if (c == null) {
            return;
        }
        long at = LayoutStore.roundTripAt(c);
        log("heal check: roundtrip_at=" + at + " mode=" + LayoutStore.storedMode(c));
        if (at <= 0) {
            return;
        }
        boolean ok = LayoutStore.endRoundTripMark(c);
        log("self-heal: 上次往返未收尾(at=" + at + ") -> "
                + (ok ? "mode=custom" : "写盘失败，读侧按过期规则仍走 custom"));
    }

    private static void sendHello(Context ctx, String pkg, String processName) {
        Bundle b = new Bundle();
        b.putString(LayoutProvider.KEY_PROCESS, pkg + ":" + processName);
        b.putString(LayoutProvider.KEY_VERSION, version());
        ctx.getContentResolver().call(
                android.net.Uri.parse("content://" + LayoutProvider.AUTHORITY),
                LayoutProvider.METHOD_HELLO, null, b);
        Diag.log("init", "v" + version() + " in " + pkg);
        Diag.flush();
    }
}
