package io.github.cechang256.xiaobu;

import android.util.Log;

import io.github.cechang256.xiaobu.xp.Xp;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Modern (libxposed API) module entry point.
 *
 * <p>This is what {@code META-INF/xposed/java_init.list} points at. Using the
 * modern format is what allows {@code staticScope=true} in
 * {@code module.prop}: LSPosed hardcodes {@code staticScope = false} for legacy
 * modules, so the scope used to be a mere recommendation.
 *
 * <p>The rest of the module keeps its classic-API hook code; it runs on the
 * compatibility layer in {@code io.github.cechang256.xiaobu.xp}.
 */
public final class ModernEntry extends XposedModule {

    private static final String TAG = "XiaoBuUnlocker";

    /** Process name reported by the framework; {@code null} until known. */
    private static volatile String sProcessName;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        Xp.attach(this);
        try {
            sProcessName = param.getProcessName();
        } catch (Throwable ignored) {
        }
        try {
            log(Log.INFO, TAG, "module loaded, api=" + getApiVersion()
                    + " process=" + sProcessName
                    + " systemServer=" + param.isSystemServer());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        Xp.attach(this);
        String pkg = param.getPackageName();
        String process = sProcessName;
        if (process == null || process.length() == 0) {
            process = pkg;
        }
        try {
            ModuleInit.install(pkg, process, param.getDefaultClassLoader(), param.isFirstPackage());
        } catch (Throwable t) {
            try {
                log(Log.ERROR, TAG, "install failed: " + t);
            } catch (Throwable ignored) {
            }
        }
    }
}
