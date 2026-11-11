package io.github.libxposed.api;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Compile-time stub of the libxposed API (Maven: io.github.libxposed:api:102.0.0).
 *
 * <p>Only the members this module references are declared, with the exact
 * descriptors of the real API. The real classes are supplied by LSPosed at
 * runtime, so this package is compiled separately and must <b>not</b> be
 * packaged into the APK.
 */
public interface XposedInterface {

    interface Chain {
        Executable getExecutable();

        Object getThisObject();

        List<Object> getArgs();

        Object getArg(int index);

        Object proceed() throws Throwable;

        Object proceed(Object[] args) throws Throwable;

        Object proceedWith(Object result) throws Throwable;

        Object proceedWith(Object result, Object[] args) throws Throwable;
    }

    interface Hooker {
        Object intercept(Chain chain) throws Throwable;
    }

    enum ExceptionMode {
        DEFAULT,
        PROTECTIVE,
        PASSTHROUGH
    }

    interface HookBuilder {
        HookBuilder setPriority(int priority);

        HookBuilder setExceptionMode(ExceptionMode mode);

        HookHandle intercept(Hooker hooker);

        HookBuilder setId(String id);
    }

    interface HookHandle {
        Executable getExecutable();

        void unhook();

        String getId();

        HookHandle replaceHook(Hooker hooker);
    }

    interface Invoker<T extends Invoker<T, U>, U extends Executable> {
        Object invoke(Object instance, Object... args)
                throws java.lang.reflect.InvocationTargetException,
                IllegalArgumentException, IllegalAccessException;
    }

    int getApiVersion();

    HookBuilder hook(Executable executable);

    Invoker<?, Method> getInvoker(Method method);

    void log(int priority, String tag, String message);
}
