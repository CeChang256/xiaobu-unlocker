package io.github.cechang256.xiaobu.xp;

import java.lang.reflect.Member;

/**
 * Drop-in replacement for the legacy {@code XC_MethodHook} shape.
 *
 * <p>Subclasses only override {@code before}/{@code after} and the bodies are the
 * same code the module used with the classic Xposed API; {@link XposedBridge}
 * adapts them to the libxposed {@code Hooker}/{@code Chain} model.
 */
public abstract class XC_MethodHook {

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;

        Object result;
        Throwable throwable;
        boolean returnEarly;

        public Object getResult() {
            return result;
        }

        /** Skip the original method and return {@code result}. */
        public void setResult(Object result) {
            this.result = result;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        /** Skip the original method and throw {@code throwable}. */
        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.returnEarly = true;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) {
                throw throwable;
            }
            return result;
        }
    }
}
