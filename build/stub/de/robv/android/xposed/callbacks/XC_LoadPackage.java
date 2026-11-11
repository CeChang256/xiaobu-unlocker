package de.robv.android.xposed.callbacks;

/**
 * Compile-time stub of the load-package callback used by
 * {@link de.robv.android.xposed.IXposedHookLoadPackage}.
 */
public abstract class XC_LoadPackage {

    public static final class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}
