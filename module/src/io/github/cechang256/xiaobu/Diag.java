package io.github.cechang256.xiaobu;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Cross-process diagnostic buffer.
 *
 * The hooked processes cannot write this app's private files, so every line is
 * pushed through the exported provider and appended to diag.log in the app's own
 * data dir. Only the lines that matter for support reach the LSPosed log, which
 * the user can export from the manager without adb or root: the config change,
 * the desktop refresh within five seconds of it, and every failure. Everything
 * else stays in diag.log, so steady state still produces no logcat traffic.
 *
 * Logging must never throw and never recurse: pushing goes through a
 * ContentResolver call, which the assistantscreen hooks also observe, so those
 * hooks skip our own authority.
 */
public final class Diag {

    /** Log file name inside getFilesDir(). */
    public static final String FILE = "diag.log";
    /** Hard cap; the provider keeps the newest half when it is exceeded. */
    public static final int MAX_FILE_BYTES = 512 * 1024;

    private static final int MAX_PENDING = 800;
    private static final long FLUSH_INTERVAL_MS = 600L;
    private static final int MAX_MESSAGE = 2000;

    /** Minimum gap between provider pushes, so a chatty hook cannot spin IPC. */
    private static final long MIN_PUSH_GAP_MS = 1500L;
    /** Back-off when the provider cannot be reached (app not started yet). */
    private static final long FAIL_BACKOFF_MS = 30000L;

    private static final ArrayDeque<String> PENDING = new ArrayDeque<String>();
    /** Payload that could not be delivered yet; retried with the next flush. */
    private static String sRetry;
    private static long sLastFlush;
    private static long sNextPushAllowed;
    private static boolean sTimerRunning;
    private static String sLastMessage;
    private static int sRepeat;

    private static final ThreadLocal<Boolean> INSIDE = new ThreadLocal<Boolean>();

    private Diag() {
    }

    /** True when the calling thread is already inside the logging pipeline. */
    static boolean inside() {
        return Boolean.TRUE.equals(INSIDE.get());
    }

    /** 诊断窗口内最多镜像多少行，防止意外刷屏。 */
    private static final int MAX_WINDOW_LINES = 40;
    /** 窗口截止时间。 */
    private static volatile long sWindowUntil;
    private static int sWindowLines;

    /**
     * 打开一个诊断窗口：配置刚变化，接下来这几秒的桌面刷新值得记。
     * 窗口外的稳态一条都不写。
     */
    static void openWindow(long ms) {
        sWindowUntil = System.currentTimeMillis() + ms;
        sWindowLines = 0;
    }

    /** 配置变化与随之而来的桌面刷新：只在窗口内写进 LSPosed 日志。 */
    static void window(String tag, String message) {
        log(tag, message);
        if (System.currentTimeMillis() < sWindowUntil && sWindowLines < MAX_WINDOW_LINES) {
            sWindowLines++;
            mirror(tag, message);
        }
    }

    /** 保存设置与所有失败：不看窗口，一律进 LSPosed 日志。 */
    static void always(String tag, String message) {
        log(tag, message);
        mirror(tag, message);
    }

    private static void mirror(String tag, String message) {
        try {
            io.github.cechang256.xiaobu.xp.XposedBridge.log("[" + tag + "] " + message);
        } catch (Throwable ignored) {
        }
    }

    static void log(String tag, String message) {
        if (inside()) {
            return;
        }
        INSIDE.set(Boolean.TRUE);
        try {
            String msg = message == null ? "null" : message;
            if (msg.length() > MAX_MESSAGE) {
                msg = msg.substring(0, MAX_MESSAGE) + "...(truncated)";
            }
            String line = stamp() + " " + tag + ": " + msg;
            synchronized (Diag.class) {
                if (line.equals(sLastMessage)) {
                    sRepeat++;
                    return;
                }
                if (sRepeat > 0) {
                    PENDING.addLast(stamp() + " " + tag + ": (previous line repeated " + sRepeat + " more times)");
                    sRepeat = 0;
                }
                sLastMessage = line;
                PENDING.addLast(line);
                while (PENDING.size() > MAX_PENDING) {
                    PENDING.removeFirst();
                }
            }
            flushIfDue();
            scheduleFlush();
        } catch (Throwable ignored) {
        } finally {
            INSIDE.set(Boolean.FALSE);
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                + " [" + android.os.Process.myPid() + "]";
    }

    static void flush() {
        if (inside()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < sNextPushAllowed) {
            return;
        }
        String payload;
        synchronized (Diag.class) {
            if (sRepeat > 0) {
                PENDING.addLast(stamp() + " " + ModuleInit.TAG + ": (previous line repeated "
                        + sRepeat + " more times)");
                sRepeat = 0;
            }
            if (PENDING.isEmpty() && sRetry == null) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (String line : PENDING) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
            PENDING.clear();
            String fresh = sb.toString();
            if (sRetry != null) {
                payload = fresh.isEmpty() ? sRetry : sRetry + '\n' + fresh;
            } else {
                payload = fresh;
            }
            sLastFlush = now;
        }
        boolean ok = push(payload);
        synchronized (Diag.class) {
            // Keep undelivered lines so a provider that is not up yet (the app
            // process was killed) does not cost us the evidence.
            sRetry = ok ? null : trimHead(payload);
        }
        sNextPushAllowed = System.currentTimeMillis()
                + (ok ? MIN_PUSH_GAP_MS : FAIL_BACKOFF_MS);
    }

    /** Keeps the newest part of an undelivered payload. */
    private static String trimHead(String payload) {
        if (payload == null) {
            return null;
        }
        int max = 16000;
        return payload.length() <= max ? payload : payload.substring(payload.length() - max);
    }

    /**
     * Guarantees that buffered lines leave the process even when no further
     * event arrives: a one-shot timer, never more than one outstanding.
     */
    private static void scheduleFlush() {
        synchronized (Diag.class) {
            if (sTimerRunning || PENDING.isEmpty()) {
                return;
            }
            sTimerRunning = true;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(FLUSH_INTERVAL_MS);
                } catch (InterruptedException ignored) {
                }
                synchronized (Diag.class) {
                    sTimerRunning = false;
                }
                flush();
            }
        }, "xiaobu-diag").start();
    }

    private static void flushIfDue() {
        long now = System.currentTimeMillis();
        boolean due;
        synchronized (Diag.class) {
            due = PENDING.size() >= 12 || (now - sLastFlush) > FLUSH_INTERVAL_MS;
        }
        if (due) {
            flush();
        }
    }

    private static boolean push(String payload) {
        if (TextUtils.isEmpty(payload)) {
            return true;
        }
        Context ctx = AppContext.get();
        if (ctx == null) {
            return false;
        }
        try {
            Bundle in = new Bundle();
            in.putString(LayoutProvider.KEY_LOG, payload);
            ctx.getContentResolver().call(
                    android.net.Uri.parse("content://" + LayoutProvider.AUTHORITY),
                    LayoutProvider.METHOD_PUSH_LOG, null, in);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Appends one payload to diag.log, rotating when it grows too large. */
    static void appendFile(Context ctx, String payload) {
        if (ctx == null || TextUtils.isEmpty(payload)) {
            return;
        }
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), FILE);
            long remaining = MAX_FILE_BYTES - f.length();
            if (remaining < payload.length() + 64) {
                java.io.File old = new java.io.File(ctx.getFilesDir(), FILE + ".1");
                if (old.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                }
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(old);
            }
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            try {
                w.write(payload);
                w.write('\n');
                w.flush();
            } finally {
                w.close();
            }
        } catch (Throwable ignored) {
        }
    }

    static void clearFile(Context ctx) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), FILE);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            java.io.File old = new java.io.File(ctx.getFilesDir(), FILE + ".1");
            if (old.exists()) {
                //noinspection ResultOfMethodCallIgnored
                old.delete();
            }
        } catch (Throwable ignored) {
        }
    }
}
