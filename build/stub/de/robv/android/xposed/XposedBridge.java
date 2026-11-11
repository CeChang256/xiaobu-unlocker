package de.robv.android.xposed;

/**
 * Compile-time stub of the Xposed API (https://api.xposed.info, group
 * de.robv.android.xposed:api:82).
 *
 * Only what this module actually calls is declared here, so the build needs no
 * network and no Maven. At runtime the real classes come from LSPosed; never
 * package these stubs into the APK.
 */
public final class XposedBridge {

    private XposedBridge() {
    }

    public static void log(String text) {
        throw new UnsupportedOperationException("stub");
    }

    public static void log(Throwable t) {
        throw new UnsupportedOperationException("stub");
    }

    /** Generic method hook; overloads exist in the real API, this one suffices. */
    public static Object hookMethod(java.lang.reflect.Member hookMethod,
                                    XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }

    public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(
            Class<?> hookClass, String methodName, XC_MethodHook callback) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object invokeOriginalMethod(java.lang.reflect.Member method,
                                              Object thisObject, Object[] args) {
        throw new UnsupportedOperationException("stub");
    }
}
