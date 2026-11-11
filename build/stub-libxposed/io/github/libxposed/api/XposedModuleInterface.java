package io.github.libxposed.api;

/**
 * Compile-time stub of {@code io.github.libxposed.api.XposedModuleInterface}:
 * the callbacks the framework invokes on a modern module.
 */
public interface XposedModuleInterface {

    interface ModuleLoadedParam {
        boolean isSystemServer();

        String getProcessName();
    }

    interface PackageLoadedParam {
        String getPackageName();

        android.content.pm.ApplicationInfo getApplicationInfo();

        boolean isFirstPackage();

        ClassLoader getDefaultClassLoader();
    }

    interface PackageReadyParam {
        String getPackageName();
    }

    interface SystemServerStartingParam {
    }

    interface HotReloadingParam {
    }

    interface HotReloadedParam {
    }

    default void onModuleLoaded(ModuleLoadedParam param) {
    }

    default void onPackageLoaded(PackageLoadedParam param) {
    }

    default void onPackageReady(PackageReadyParam param) {
    }

    default void onSystemServerStarting(SystemServerStartingParam param) {
    }

    default boolean onHotReloading(HotReloadingParam param) {
        return true;
    }

    default void onHotReloaded(HotReloadedParam param) {
    }
}
