package io.github.cechang256.xiaobu.xp;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Reflection helpers with the legacy {@code XposedHelpers} signatures.
 *
 * <p>The libxposed API has no helper layer, so the module carries its own. Only
 * what the hook code calls is implemented; every lookup walks the superclass
 * chain like the original did.
 */
public final class XposedHelpers {

    private XposedHelpers() {
    }

    /** Thrown when a class cannot be resolved; the module catches it per hook. */
    public static final class ClassNotFoundError extends RuntimeException {
        public ClassNotFoundError(String name) {
            super("class not found: " + name);
        }
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        if (className == null) {
            throw new ClassNotFoundError("null");
        }
        if (classLoader != null) {
            try {
                return Class.forName(className, false, classLoader);
            } catch (Throwable ignored) {
            }
        }
        try {
            return Class.forName(className);
        } catch (Throwable ignored) {
        }
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null) {
            try {
                return Class.forName(className, false, ctx);
            } catch (Throwable ignored) {
            }
        }
        throw new ClassNotFoundError(className);
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
            }
        }
        throw new NoSuchFieldError(clazz.getName() + "#" + fieldName);
    }

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).get(obj);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            findField(obj.getClass(), fieldName).set(obj, value);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static int getIntField(Object obj, String fieldName) {
        try {
            return findField(obj.getClass(), fieldName).getInt(obj);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void setIntField(Object obj, String fieldName, int value) {
        try {
            findField(obj.getClass(), fieldName).setInt(obj, value);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        Object[] real = args == null ? new Object[0] : args;
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            if (c.getParameterCount() != real.length) {
                continue;
            }
            if (!matches(c.getParameterTypes(), real)) {
                continue;
            }
            try {
                c.setAccessible(true);
                return c.newInstance(real);
            } catch (Throwable ignored) {
            }
        }
        throw new RuntimeException("no matching constructor: " + clazz.getName());
    }

    public static Object callMethod(Object obj, String methodName, Object... args) {
        if (obj == null) {
            throw new NullPointerException("callMethod on null: " + methodName);
        }
        return invoke(obj.getClass(), obj, methodName, args);
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        return invoke(clazz, null, methodName, args);
    }

    private static Object invoke(Class<?> clazz, Object receiver, String methodName, Object[] args) {
        Object[] real = args == null ? new Object[0] : args;
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName) || m.getParameterCount() != real.length) {
                    continue;
                }
                if (!matches(m.getParameterTypes(), real)) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    return m.invoke(Modifier.isStatic(m.getModifiers()) ? null : receiver, real);
                } catch (InvocationTargetException e) {
                    throw new RuntimeException(e.getCause());
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            }
        }
        throw new NoSuchMethodError(clazz.getName() + "#" + methodName);
    }

    /** Hooks a method: {@code (Class, String, Class..., XC_MethodHook)}. */
    public static void findAndHookMethod(Class<?> clazz, String methodName,
                                         Object... parameterTypesAndCallback) {
        Method m = resolve(clazz, methodName, parameterTypesAndCallback);
        XposedBridge.hookMethod(m, callbackOf(parameterTypesAndCallback));
    }

    /** Hooks a method by class name: {@code (String, ClassLoader, String, Class..., XC_MethodHook)}. */
    public static void findAndHookMethod(String className, ClassLoader classLoader,
                                         String methodName, Object... parameterTypesAndCallback) {
        findAndHookMethod(findClass(className, classLoader), methodName, parameterTypesAndCallback);
    }

    private static Method resolve(Class<?> clazz, String methodName, Object[] spec) {
        int count = spec.length - 1;
        Class<?>[] wanted = new Class<?>[Math.max(count, 0)];
        for (int i = 0; i < count; i++) {
            Object o = spec[i];
            if (!(o instanceof Class)) {
                throw new IllegalArgumentException("parameter type expected at " + i);
            }
            wanted[i] = (Class<?>) o;
        }
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName) || m.getParameterCount() != count) {
                    continue;
                }
                if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                    continue;
                }
                if (sameTypes(m.getParameterTypes(), wanted)) {
                    return m;
                }
            }
        }
        throw new NoSuchMethodError(clazz.getName() + "#" + methodName);
    }

    private static XC_MethodHook callbackOf(Object[] spec) {
        if (spec.length == 0 || !(spec[spec.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("missing XC_MethodHook callback");
        }
        return (XC_MethodHook) spec[spec.length - 1];
    }

    private static boolean sameTypes(Class<?>[] declared, Class<?>[] wanted) {
        if (declared.length != wanted.length) {
            return false;
        }
        for (int i = 0; i < declared.length; i++) {
            if (!declared[i].equals(wanted[i])) {
                return false;
            }
        }
        return true;
    }

    /** Loose match used for reflective calls: exact or assignable. */
    private static boolean matches(Class<?>[] declared, Object[] args) {
        if (declared.length != args.length) {
            return false;
        }
        for (int i = 0; i < declared.length; i++) {
            Class<?> d = box(declared[i]);
            Object a = args[i];
            if (a == null) {
                if (d.isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (!d.isInstance(a)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> box(Class<?> c) {
        if (!c.isPrimitive()) {
            return c;
        }
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == boolean.class) return Boolean.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        if (c == short.class) return Short.class;
        if (c == float.class) return Float.class;
        if (c == double.class) return Double.class;
        return c;
    }
}
