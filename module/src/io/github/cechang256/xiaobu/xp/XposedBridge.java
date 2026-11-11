package io.github.cechang256.xiaobu.xp;

import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;

/**
 * Legacy-API-compatible static helpers, implemented on top of libxposed.
 *
 * <p>Only the members this module uses are provided. The semantics follow the
 * classic {@code XposedBridge} closely enough for the existing hook code:
 * {@code before} runs first, {@code setResult} skips the original method, and
 * {@code after} runs with the produced result.
 */
public final class XposedBridge {

    private XposedBridge() {
    }

    public static void log(String text) {
        Xp.log(text);
    }

    public static void log(Throwable t) {
        Xp.log(android.util.Log.getStackTraceString(t));
    }

    /** Hooks one constructor/method. */
    public static void hookMethod(Member hookMethod, XC_MethodHook callback) {
        if (!(hookMethod instanceof Executable) || callback == null) {
            Xp.log("skip hook: " + hookMethod);
            return;
        }
        XposedInterface i = Xp.iface();
        if (i == null) {
            Xp.log("skip hook (not attached): " + hookMethod);
            return;
        }
        final XC_MethodHook cb = callback;
        try {
            i.hook((Executable) hookMethod)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            return dispatch(cb, chain);
                        }
                    });
        } catch (Throwable t) {
            Xp.log("hook failed " + hookMethod + ": " + t);
        }
    }

    /** Hooks every method with that name (declared in the class or its superclasses). */
    public static Set<XC_MethodHook> hookAllMethods(Class<?> hookClass, String methodName,
                                                    XC_MethodHook callback) {
        Set<XC_MethodHook> hooked = new HashSet<>();
        if (hookClass == null || callback == null) {
            return hooked;
        }
        for (Class<?> c = hookClass; c != null && c != Object.class; c = c.getSuperclass()) {
            Method[] methods;
            try {
                methods = c.getDeclaredMethods();
            } catch (Throwable t) {
                continue;
            }
            for (Method m : methods) {
                if (m.getName().equals(methodName)) {
                    hookMethod(m, callback);
                    hooked.add(callback);
                }
            }
        }
        return hooked;
    }

    /** Calls the original, unhooked, implementation. */
    public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
            throws Throwable {
        XposedInterface i = Xp.iface();
        if (i == null) {
            throw new IllegalStateException("module not attached to the framework");
        }
        if (!(method instanceof Method)) {
            throw new IllegalArgumentException("not a method: " + method);
        }
        Object[] real = args == null ? new Object[0] : args;
        return i.getInvoker((Method) method).invoke(thisObject, real);
    }

    private static Object dispatch(XC_MethodHook cb, XposedInterface.Chain chain) throws Throwable {
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.method = chain.getExecutable();
        param.thisObject = chain.getThisObject();
        List<Object> list = chain.getArgs();
        param.args = list == null ? new Object[0] : new ArrayList<>(list).toArray();

        cb.beforeHookedMethod(param);
        if (param.returnEarly) {
            return param.getResultOrThrowable();
        }

        param.result = chain.proceed(param.args);
        cb.afterHookedMethod(param);
        if (param.returnEarly) {
            return param.getResultOrThrowable();
        }
        return param.result;
    }
}
