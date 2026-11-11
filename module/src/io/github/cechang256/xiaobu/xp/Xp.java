package io.github.cechang256.xiaobu.xp;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * Holds the {@link XposedInterface} handed to us by the framework.
 *
 * <p>The hook code in this module predates the libxposed API and talks to the
 * small compatibility layer in this package ({@link XposedHelpers},
 * {@link XposedBridge}, {@link XC_MethodHook}); everything funnels through here
 * so the rest of the module stays unchanged.
 */
public final class Xp {

    public static final String TAG = "XiaoBuUnlocker";

    private static volatile XposedInterface sInterface;

    private Xp() {
    }

    /** Called once from the module entry point. */
    public static void attach(XposedInterface xposedInterface) {
        if (xposedInterface != null) {
            sInterface = xposedInterface;
        }
    }

    static XposedInterface iface() {
        return sInterface;
    }

    public static void log(String message) {
        XposedInterface i = sInterface;
        if (i != null) {
            try {
                i.log(Log.INFO, TAG, message);
                return;
            } catch (Throwable ignored) {
                // fall through to plain logcat
            }
        }
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) {
        }
    }
}
