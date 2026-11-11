package io.github.cechang256.xiaobu;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * 读取本模块的 versionName。
 *
 * 必须独立成类，且**绝对不能引用任何 Xposed 类型**：本模块没有自己的 UI 进程
 * （纯 hook 模块，无入口 Activity），任何在非 LSPosed 注入进程里触发加载
 * 实现了 IXposedHookLoadPackage 的 ModuleInit 的行为，都会抛
 * NoClassDefFoundError 让整个进程崩溃
 * （v7.5 第一版就是这样：一打开就闪退）。
 */
public final class BuildInfo {

    /** 读不到 PackageManager 时的兜底，构建时更新。 */
    static final String FALLBACK = "1.29";

    private static final String SELF = "io.github.cechang256.xiaobu";

    private static volatile String sCached;

    private BuildInfo() {
    }

    /** 在 UI 进程里用：传入 ApplicationContext。 */
    public static String version(Context ctx) {
        String v = sCached;
        if (v != null) {
            return v;
        }
        if (ctx != null) {
            try {
                v = ctx.getPackageManager().getPackageInfo(SELF, 0).versionName;
            } catch (Throwable ignored) {
            }
        }
        if (v == null || v.length() == 0) {
            v = FALLBACK;
        }
        sCached = v;
        return v;
    }

    /** 在被注入进程里用（拿不到 Context 时也安全，返回兜底值）。 */
    static String versionOrFallback() {
        return sCached != null ? sCached : FALLBACK;
    }
}
