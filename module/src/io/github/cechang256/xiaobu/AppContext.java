package io.github.cechang256.xiaobu;

import android.content.Context;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Resolves a usable Context from inside the hooked process.
 *
 * The Context is only ever handed to the module's own ContentProvider and its
 * prefs, so even the system Context (uid 1000) is enough for a lookup: the
 * provider is exported and the call is not privileged.
 *
 * Resolution order:
 *   1. the Application, captured by the Application#onCreate hook (see install)
 *   2. ActivityThread.currentApplication(), for processes that already run one
 *   3. ActivityThread.getSystemContext(), which exists before the Application
 *
 * Android 17 removed Application#attach(Context), so nothing here may rely on
 * it; the onCreate hook is the version-proof way to catch the Application.
 */
final class AppContext {

    private static volatile Context cached;
    private static volatile boolean sLogged;

    private AppContext() {
    }

    static void set(Context ctx) {
        if (ctx != null) {
            cached = ctx.getApplicationContext() != null
                    ? ctx.getApplicationContext() : ctx;
        }
    }

    /**
     * Hooks Application#onCreate so the Context arrives as early as possible.
     *
     * Application#attachBaseContext(Context) is not resolvable on ColorOS 17
     * (NoSuchMethodError even for the exact API signature), so only onCreate is
     * used; it always runs before the process touches card data.
     */
    static void install(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", cl,
                    "onCreate", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Context) {
                                set((Context) param.thisObject);
                                Diag.flush();
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(ModuleInit.TAG + ": Application#onCreate hook failed " + t);
        }
    }

    /**
     * True once the Application exists. A system Context is enough for provider
     * calls but NOT for registerReceiver, which fails with
     * "caller package android is not running in process".
     */
    static boolean hasApplication() {
        if (cached != null) {
            return true;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            return at.getMethod("currentApplication").invoke(null) instanceof Context;
        } catch (Throwable t) {
            return false;
        }
    }

    static Context get() {
        Context c = cached;
        if (c != null) {
            return c;
        }
        Class<?> at;
        try {
            at = Class.forName("android.app.ActivityThread");
        } catch (Throwable t) {
            logOnce("ActivityThread missing: " + t);
            return null;
        }
        // 1. Normal case: the application exists.
        try {
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                cached = ((Context) app).getApplicationContext();
                return cached;
            }
        } catch (Throwable t) {
            logOnce("currentApplication failed: " + t);
        }
        // 2. Early startup: only the system Context exists, which is enough for
        //    a ContentResolver call to our exported provider.
        try {
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            if (thread != null) {
                Object sys = at.getMethod("getSystemContext").invoke(thread);
                if (sys instanceof Context) {
                    return (Context) sys;
                }
            }
        } catch (Throwable t) {
            logOnce("systemContext failed: " + t);
        }
        return null;
    }

    private static void logOnce(String message) {
        if (sLogged) {
            return;
        }
        sLogged = true;
        XposedBridge.log(ModuleInit.TAG + ": context " + message);
    }
}
