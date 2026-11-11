package de.robv.android.xposed;

/**
 * Compile-time stub of {@code de.robv.android.xposed.XC_MethodHook}.
 * See {@link XposedBridge} for why this exists.
 */
public abstract class XC_MethodHook {

    protected XC_MethodHook() {
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    public static final class MethodHookParam {
        public java.lang.reflect.Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;
        private boolean returnEarly;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

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

    /** Returned by the hook* helpers; kept for API compatibility. */
    public final class Unhook {
        public java.lang.reflect.Member getHookedMethod() {
            return null;
        }

        public void unhook() {
        }
    }
}
