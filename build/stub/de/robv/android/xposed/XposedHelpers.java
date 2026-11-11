package de.robv.android.xposed;

/**
 * Compile-time stub of {@code de.robv.android.xposed.XposedHelpers}.
 * Only the members this module uses are declared.
 */
public final class XposedHelpers {

    private XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object getObjectField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        throw new UnsupportedOperationException("stub");
    }

    public static int getIntField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static void setIntField(Object obj, String fieldName, int value) {
        throw new UnsupportedOperationException("stub");
    }

    public static java.lang.reflect.Field findField(Class<?> clazz, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static XC_MethodHook.Unhook findAndHookMethod(Class<?> clazz, String methodName,
                                                         Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("stub");
    }

    public static XC_MethodHook.Unhook findAndHookMethod(String className,
                                                         ClassLoader classLoader,
                                                         String methodName,
                                                         Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("stub");
    }
}
