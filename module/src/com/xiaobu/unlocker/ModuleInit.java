package com.xiaobu.unlocker;

import android.content.Context;
import android.os.Bundle;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Module entry point.
 *
 * 只有一个目标进程：com.oplus.pantanal.ums（模型 + 排序 + 编辑器）。
 * 作用域在 AndroidManifest 里用 xposedscope 声明为**静态作用域**，
 * 桌面进程不再需要——对调/自定义全部在 ums 的数据层完成，launcher
 * 只是消费方，不需要注入。
 */
public final class ModuleInit implements IXposedHookLoadPackage {

    static final String TAG = "XiaoBuUnlocker";
    static final String TARGET = "com.oplus.pantanal.ums";

    /** 版本号统一由 BuildInfo 提供（不含 Xposed 依赖，UI 进程也能安全使用）。 */
    static String version() {
        // round 52：原来直接走 versionOrFallback()，而 version(Context) 没有任何
        // 调用方 → sCached 永远是 null → 所有进程都报 FALLBACK（7.6），
        // 与真实版本（1.10）不符，排查时会误导。这里给出 Context 让它真正读一次。
        try {
            return BuildInfo.version(AppContext.get());
        } catch (Throwable t) {
            return BuildInfo.versionOrFallback();
        }
    }

    /** Log helper usable from any class without importing XposedBridge. */
    static void log(String msg) {
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (lp == null || lp.packageName == null) {
            return;
        }
        String pkg = lp.packageName;
        if (!TARGET.equals(pkg)) {
            // 静态作用域下不应该走到这里；真走到说明 LSPosed 仍把别的包
            // 注入进来了。留一行日志当"作用域是否真的收窄"的实测证据
            //（每进程启动一条，成本可忽略）。
            XposedBridge.log(TAG + ": loaded into " + pkg
                    + " process=" + lp.processName + " (out of scope, ignored)");
            return;
        }
        XposedBridge.log(TAG + ": loaded into " + pkg + " process=" + lp.processName
                + " first=" + lp.isFirstApplication);
        try {
            AppContext.install(lp.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": context hook failed " + t);
        }
        if (TARGET.equals(pkg)) {
            installModel(lp);
            try {
                SceneServiceRewriter.install(lp.classLoader);
            } catch (Throwable t) {
                Diag.log("dswap", "install failed " + t);
            }
            try {
                EditorHook.install(lp.classLoader);
            } catch (Throwable t) {
                Diag.log("editor", "install failed " + t);
            }
        }
        try {
            welcome(lp);
        } catch (Throwable ignored) {
        }
    }

    private static void installModel(XC_LoadPackage.LoadPackageParam lp) {
        try {
            DefaultServiceHook.install(lp.classLoader);
        } catch (Throwable t) {
            Diag.log("init", "model hook failed " + t);
        }
    }

    /**
     * Tells the UI which processes actually loaded the module.
     *
     * handleLoadPackage can run before the Application exists, so the Context
     * may still be null here; retry on a short-lived thread instead of giving
     * up, otherwise the process registry stays empty until the next restart.
     */
    private static void welcome(final XC_LoadPackage.LoadPackageParam lp) {
        final String pkg = lp.packageName;
        new Thread(new Runnable() {
            @Override
            public void run() {
                // A system Context is NOT good enough here: a provider call
                // attributed to package "android" fails the same way
                // registerReceiver does on Android 17. Wait for the Application.
                for (int i = 0; i < 120; i++) {
                    if (AppContext.hasApplication()) {
                        Context c = AppContext.get();
                        if (c != null) {
                            try {
                                sendHello(c, pkg, lp.processName);
                            } catch (Throwable t) {
                                Diag.log("init", "hello failed " + t);
                            }
                            afterApplication(lp);
                            return;
                        }
                    }
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "xiaobu-hello").start();
    }

    /**
     * Work that needs a real Application Context rather than the system one.
     *
     * registerReceiver with a system Context throws
     * "Given caller package android is not running in process" on Android 17,
     * so the push receiver is only registered once the app exists. It is still
     * only a latency optimisation: {@link LayoutStore} polls the provider.
     */
    private static void afterApplication(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET.equals(lp.packageName) || !AppContext.hasApplication()) {
            return;
        }
        // round 52：删掉了 LayoutReceiver。它注册的 ACTION_PUSH 广播全工程
        // **没有发送方**（无 sendBroadcast），只是一个永远不会被触发的接收器；
        // 配置变更本来就是靠 LayoutStore 的 3s TTL 缓存 + 保存后主动 invalidate。

        // 开机快刷只在主进程做：子进程（aiflow/cardconfig）不发决策通知，
        // 重复通知没有意义还可能放大开机负载。主进程判定 = processName 与
        // 包名相同（ums 的子进程名带 :aiflow 等后缀）。
        if (lp.processName == null || lp.processName.equals(lp.packageName)) {
            try {
                healInterruptedRoundTrip();
            } catch (Throwable t) {
                Diag.log("init", "self-heal failed " + t);
            }
            try {
                EditorHook.bootKickNotify();
            } catch (Throwable t) {
                Diag.log("init", "boot kick failed " + t);
            }
        }
    }

    /**
     * 启动自愈（round 52）。
     *
     * <p>对调的"模式往返"要把 mode 切成 native 让自定义卡离场，再由数据查询
     * 推回 custom。如果进程在往返途中被杀（编辑器就住在 ums 里，保存后立刻
     * 关页时最容易发生），那次切换永远不会执行——落盘的 native 就成了永久
     * 状态，桌面退回系统卡，用户以为配置丢了。
     *
     * <p>现在状态是可判定的：往返开始时写 `roundtrip_at`（与 mode 同一次
     * 读改写），结束/用户主动选择时删除该键。于是
     * <ul>
     *   <li><b>读侧</b>：{@link LayoutStore#mode} 看到标记过期就直接按 custom
     *       返回，不需要任何回调或启动钩子；</li>
     *   <li><b>启动侧</b>：本方法把残留标记真正清掉，让磁盘状态干净（否则
     *       getLayout 永远带着一个过期时间戳，排查时会被误导）。</li>
     * </ul>
     *
     * <p>关键：判定依据是**标记存在与否**，不是 mode 的值。用户主动切到系统
     * 样式时 mode=native 但没有标记，所以不会被这里误伤。
     */
    private static void healInterruptedRoundTrip() {
        Context c = AppContext.get();
        if (c == null) {
            return;
        }
        long at = LayoutStore.roundTripAt(c);
        log("heal check: roundtrip_at=" + at + " mode=" + LayoutStore.storedMode(c));
        if (at <= 0) {
            return;
        }
        // 进程刚启动 ⇒ 不可能存在"进行中的往返"（往返状态全部活在进程内），
        // 所以看到标记一定是上一次没走完留下的。无条件清掉，mode 回 custom。
        boolean ok = LayoutStore.endRoundTripMark(c);
        log("self-heal: 上次往返未收尾(at=" + at + ") -> "
                + (ok ? "mode=custom" : "写盘失败，读侧按过期规则仍走 custom"));
    }

    private static void sendHello(Context ctx, String pkg, String processName) {
        Bundle b = new Bundle();
        b.putString(LayoutProvider.KEY_PROCESS, pkg + ":" + processName);
        b.putString(LayoutProvider.KEY_VERSION, version());
        ctx.getContentResolver().call(
                android.net.Uri.parse("content://" + LayoutProvider.AUTHORITY),
                LayoutProvider.METHOD_HELLO, null, b);
        Diag.log("init", "v" + version() + " in " + pkg);
        Diag.flush();
    }
}
