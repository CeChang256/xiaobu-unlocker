package io.github.cechang256.xiaobu;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

/**
 * 在 UMS 进程内通知 launcher 重拉卡序（round 56 的实现部分）。
 *
 * <p>为什么这一轮才做：2026-11-23 16:48 的对照实验——只 force-stop
 * {@code com.android.launcher}、UMS 进程 pid 不变，桌面立刻回正；而
 * 强停 UMS 后桌面仍反。结论：改写的数据本来就是对的，卡序是 launcher
 * 启动时读一次并缓存的，UMS 侧后续改写不触发重读。所以"点确定后桌面
 * 跟预览不一致"缺的不是改写逻辑，而是**一次能让 launcher 重读的通知**。
 *
 * <p>为什么旧的通知没用：{@link #notifyAll} 之外的旧实现只通知了 base URI，
 * 而桌面卡组的观察者注册在 {@link NotifyPlan#URI_AI_LAUNCHER} 上（见
 * NotifyPlan 类注释里的反编译证据）。
 */
final class SceneNotify {

    private static long sLastMs;
    private static String sLastReason;

    private SceneNotify() {
    }

    /** 常规通知：受 {@link NotifyPlan#RATE_MS} 节流。 */
    static void notifyAll(Context ctx, String reason) {
        notifyAll(ctx, reason, false);
    }

    /**
     * @param force true = 跳过节流（编辑器保存这种一次性动作必须发出去）
     */
    static void notifyAll(final Context ctx, final String reason, boolean force) {
        if (ctx == null) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (SceneNotify.class) {
            if (!force && !NotifyPlan.allow(now, sLastMs)) {
                return;
            }
            sLastMs = now;
            sLastReason = reason;
        }
        // 主通知同步发，补发在后台线程里做：launcher 的观察者在进程重启/
        // 主线程繁忙时可能还没就绪，单发一次会丢。
        int fired = fire(ctx, reason);
        Diag.log("dswap", "notify launcher (" + reason + ") uris=" + fired);
        final long[] delays = NotifyPlan.RETRY_MS;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 1; i < delays.length; i++) {
                    try {
                        Thread.sleep(delays[i]);
                    } catch (InterruptedException e) {
                        return;
                    }
                    int n = fire(ctx, reason + " retry" + i);
                    Diag.log("dswap", "notify launcher retry" + i
                            + " (" + reason + ") uris=" + n);
                }
            }
        }, "xiaobu-notify-" + reason);
        t.setDaemon(true);
        t.start();
    }

    private static int fire(Context ctx, String reason) {
        int fired = 0;
        try {
            ContentResolver cr = ctx.getContentResolver();
            for (int i = 0; i < NotifyPlan.URIS.length; i++) {
                try {
                    // 0 = 普通通知，符合注册在父 URI 上、notifyForDescendants=true
                    // 的观察者语义；原生代码自己用的是 32768（内部标志，传播规则
                    // 由系统按标志位决定，与 0 不同），这里两种都发一次，覆盖面最广。
                    cr.notifyChange(Uri.parse(NotifyPlan.URIS[i]), null, 0);
                    cr.notifyChange(Uri.parse(NotifyPlan.URIS[i]), null, 32768);
                    fired++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            Diag.always("dswap", "notify launcher fail (" + reason + "): " + t);
        }
        return fired;
    }

    /** 最近一次通知的原因（诊断用）。 */
    static String lastReason() {
        return sLastReason;
    }
}
