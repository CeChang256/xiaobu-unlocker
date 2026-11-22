package io.github.cechang256.xiaobu;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

import org.json.JSONObject;

/**
 * Bridge between this module's UI process and every hooked process.
 *
 * The hooks cannot read our private prefs file, and depending on a broadcast
 * would require the target process to be alive at the moment the user taps
 * "应用". A tiny exported provider avoids both problems.
 *
 * Methods:
 *   getLayout  -> {layout, enabled}       config snapshot (read)
 *   pushLog    -> {log}                   append diagnostics from a hooked process
 *   clearLog   -> {}                      drop the diagnostics file
 *   hello      -> {process, version}      record which processes loaded us
 *
 * This must read prefs / files directly and never call back into LayoutStore's
 * cached accessor, which is itself backed by this provider.
 */
public final class LayoutProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.cechang256.xiaobu.layout";
    public static final String METHOD_GET = "getLayout";
    public static final String METHOD_SET_COLUMN = "setColumn";
    /** 一次写入左右两栏（+ 可选 mode），避免两次读改写互相盖掉。 */
    public static final String METHOD_SET_COLUMNS = "setColumns";
    /** 写入桌面样式模式（"custom"/"native"，layout JSON 的 mode 键）。 */
    public static final String METHOD_SET_MODE = "setMode";
    /** 通用 KV：编辑器"上次选中的卡"持久化（进程被杀后恢复预览）。 */
    public static final String METHOD_PUT_EXTRA = "putExtra";
    public static final String METHOD_GET_EXTRA = "getExtra";
    public static final String METHOD_PUSH_LOG = "pushLog";
    public static final String METHOD_CLEAR_LOG = "clearLog";
    public static final String METHOD_HELLO = "hello";

    public static final String KEY_LAYOUT = "layout";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_LOG = "log";
    public static final String KEY_PROCESS = "process";
    public static final String KEY_VERSION = "version";

    static final String PREFS = "xiaobu_unlocker";
    static final String PREF_LAYOUT = "layout_json";
    static final String PREF_ENABLED = "enabled";
    static final String PREF_PROCESSES = "seen_processes";

    /**
     * 配置写入锁（round 52）。layout_json 是一个整串，每次写都是
     * "读整串 → 改 → 写回"，而 {@link #call} 跑在 Binder 线程池上，可以真并发：
     * 往返切 mode（查询线程）与编辑器保存（协程线程）交错时，后写的会把前一次
     * 刚写进去的列整段盖掉，表现为"保存后某一栏没变"且两次调用都成功。
     * 所有读改写路径必须串行。
     */
    private static final Object LAYOUT_LOCK = new Object();

    /** 允许调用本 provider 的 ums uid（缓存 60s，避免每次 call 都查包管理器）。 */
    private static volatile int sUmsUid = -2;
    private static volatile long sUmsUidAt;

    /**
     * 只有"自己 / root / system / 小布建议进程"能读写配置与日志。
     *
     * <p>这个 provider 是 exported 的（跨进程共享是设计需要），不加校验的话
     * 任何本机 App 都能改配置、甚至 clearLog 抹掉排查证据。
     */
    private boolean callerAllowed(Context ctx) {
        int uid = android.os.Binder.getCallingUid();
        if (uid == android.os.Process.myUid()
                || uid == 0
                || uid == android.os.Process.SYSTEM_UID) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (sUmsUid == -2 || now - sUmsUidAt > 60_000L) {
            try {
                sUmsUid = ctx.getPackageManager()
                        .getPackageUid("com.oplus.pantanal.ums", 0);
            } catch (Throwable t) {
                sUmsUid = -1;
            }
            sUmsUidAt = now;
        }
        return sUmsUid > 0 && uid == sUmsUid;
    }

    @Override
    public boolean onCreate() {
        // 1.34 首启补种（1.35 起只补 model/custom_tab，不再碰 dswap）：
        // 1.33 从不写 features，而当时的闸门是 feature(ctx,"dswap",false)，
        // 键缺失即为关（新机实测只剩 "skip: dswap off"）。1.35 闸门已改为
        // mode 驱动，这里保留只是为了把两项"默认 true"写实、让配置自解释。
        Context ctx = getContext();
        if (ctx != null) {
            try {
                synchronized (LAYOUT_LOCK) {
                    readSeeded(ctx);
                }
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        Context ctx = getContext();
        if (ctx == null) {
            return out;
        }
        if (!callerAllowed(ctx)) {
            out.putBoolean("ok", false);
            return out;
        }
        if (METHOD_GET.equals(method)) {
            // 读也补种：ums 进程第一次来取配置时就把 features 落地，
            // 哪怕用户此后一直不打开编辑器。
            synchronized (LAYOUT_LOCK) {
                out.putString(KEY_LAYOUT, readSeeded(ctx));
                out.putBoolean(KEY_ENABLED, prefs(ctx).getBoolean(PREF_ENABLED, true));
            }
        } else if (METHOD_SET_COLUMN.equals(method)) {
            // 由编辑器截流 hook（EditorHook）跨进程调用：
            // 把用户在原生编辑器里选的卡写入 tab_N.col_M（serviceId 为空 = 移除）。
            out.putBoolean("ok", setColumn(ctx, extras));
        } else if (METHOD_SET_COLUMNS.equals(method)) {
            // 一次写入左右两栏（可选 mode），单次读改写，见 LAYOUT_LOCK 注释。
            out.putBoolean("ok", setColumns(ctx, extras));
        } else if (METHOD_SET_MODE.equals(method)) {
            // 编辑器保存分流 hook 调用：记录当前生效样式。custom = 桌面由
            // 插件改写（col_0/col_1 生效）；native = 系统自带 tab 生效，
            // 插件改写全部让路，但 col_0/col_1 档案保留（随时切回）。
            //
            // roundtrip_at 与 mode 在同一次读改写里落地（round 52）：
            //   >0 写入时间戳 = 对调往返开始
            //   =0 删除该键   = 往返结束 / 用户主动选择
            // 两者必须原子，否则会出现"标记在、mode 不在"这类无法判定的中间态。
            boolean ok = false;
            if (extras != null) {
                String m = extras.getString("mode");
                long rt = extras.getLong("roundtrip_at", -1L);
                try {
                    synchronized (LAYOUT_LOCK) {
                        JSONObject root = readRoot(ctx);
                        if (!TextUtils.isEmpty(m)) {
                            root.put("mode", m);
                        }
                        if (rt == 0L) {
                            root.remove("roundtrip_at");
                        } else if (rt > 0L) {
                            root.put("roundtrip_at", rt);
                        }
                        // commit() 而不是 apply()：调用方（往返状态机）拿
                        // ok=true 就要认定"已持久"，apply 是异步的，返回时
                        // 数据还在内存里，进程被杀就丢了。
                        ok = writeRoot(ctx, root);
                    }
                } catch (Throwable ignored) {
                }
            }
            out.putBoolean("ok", ok);
        } else if (METHOD_PUT_EXTRA.equals(method)) {
            // 编辑器"上次选中的卡"持久化：进程被杀后重新打开编辑器时
            // 用它恢复左右预览，否则会退回系统默认卡（体验倒退）。
            if (extras != null) {
                String key = extras.getString("key");
                if (key != null && key.length() > 0) {
                    prefs(ctx).edit()
                            .putString("extra_" + key, extras.getString("value"))
                            .apply();
                }
            }
        } else if (METHOD_GET_EXTRA.equals(method)) {
            if (extras != null) {
                String key = extras.getString("key");
                if (key != null && key.length() > 0) {
                    out.putString("value",
                            prefs(ctx).getString("extra_" + key, null));
                }
            }
        } else if (METHOD_PUSH_LOG.equals(method)) {
            String payload = extras == null ? arg : extras.getString(KEY_LOG);
            Diag.appendFile(ctx, payload);
        } else if (METHOD_CLEAR_LOG.equals(method)) {
            Diag.clearFile(ctx);
        } else if (METHOD_HELLO.equals(method)) {
            noteProcess(ctx, extras);
        }
        return out;
    }

    /** 读 layout_json；损坏时返回空对象，绝不抛给调用方。 */
    private static JSONObject readRoot(Context ctx) {
        try {
            return new JSONObject(prefs(ctx).getString(PREF_LAYOUT, "{}"));
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /**
     * 1.34：读 layout_json 并补种 features（1.35 起只补 model/custom_tab），必要时落盘。
     *
     * <p>返回的文本保证"如果原本有 features 缺键，现在也补齐了"，
     * 调用方不会再把缺键配置读出去。调用方必须已持 {@link #LAYOUT_LOCK}。
     *
     * <p>不补 dswap：闸门由 mode 推断（{@link LayoutStore#dswapOn}），补一个显式
     * 值等于把实时选择冻成快照。
     */
    private static String readSeeded(Context ctx) {
        String raw = prefs(ctx).getString(PREF_LAYOUT, null);
        if (TextUtils.isEmpty(raw)) {
            return raw;
        }
        try {
            JSONObject root = new JSONObject(raw);
            if (!LayoutSeed.seed(root)) {
                return raw;
            }
            String fixed = root.toString();
            if (prefs(ctx).edit().putString(PREF_LAYOUT, fixed).commit()) {
                logSeed(ctx, "read");
                return fixed;
            }
            return raw;
        } catch (Throwable t) {
            return raw;
        }
    }

    /**
     * 1.34：所有保存路径的唯一出口 —— 写盘前补种 features（1.35 起只补
     * model/custom_tab，dswap 不再补种）。
     *
     * <p>这样 setColumns / setColumn / setMode 任一条路都不会再把
     * 缺 {features.model} 的配置写出去。调用方必须已持 {@link #LAYOUT_LOCK}。
     */
    private static boolean writeRoot(Context ctx, JSONObject root) {
        boolean seeded = LayoutSeed.seed(root);
        boolean ok = prefs(ctx).edit()
                .putString(PREF_LAYOUT, root.toString()).commit();
        if (ok && seeded) {
            logSeed(ctx, "save");
        }
        return ok;
    }

    /** 补种留痕，供设备侧 diag.log 核对（与 Diag 自身的时间戳格式一致）。 */
    private static void logSeed(Context ctx, String where) {
        try {
            Diag.appendFile(ctx, new java.text.SimpleDateFormat("HH:mm:ss.SSS",
                    java.util.Locale.US).format(new java.util.Date())
                    + " [" + android.os.Process.myPid() + "] config: seed features"
                    + " (model,custom_tab) (" + where + ")");
        } catch (Throwable ignored) {
        }
    }

    /** 把一栏写进 tab 对象（内部用，调用方必须已持锁）。 */
    private static void putColumn(JSONObject tabObj, String col, String sid,
            int type, int size, String name) throws org.json.JSONException {
        if (TextUtils.isEmpty(sid)) {
            tabObj.remove(col);
            return;
        }
        JSONObject c = new JSONObject();
        c.put("serviceId", sid);
        c.put("type", type);
        c.put("size", size);
        c.put("name", name == null ? "" : name);
        tabObj.put(col, c);
    }

    /**
     * Writes one column into the layout JSON (used by the editor interception
     * hook, which runs inside ums and can only reach us through this provider).
     * extras: tab, col, serviceId, type, size, name — an empty serviceId
     * removes the column override.
     */
    private static boolean setColumn(Context ctx, Bundle extras) {
        if (extras == null) {
            return false;
        }
        try {
            String tab = extras.getString("tab");
            String col = extras.getString("col");
            String sid = extras.getString("serviceId", "");
            if (TextUtils.isEmpty(tab) || TextUtils.isEmpty(col)) {
                return false;
            }
            synchronized (LAYOUT_LOCK) {
                JSONObject root = readRoot(ctx);
                JSONObject tabObj = root.optJSONObject(tab);
                if (tabObj == null) {
                    tabObj = new JSONObject();
                    root.put(tab, tabObj);
                }
                putColumn(tabObj, col, sid, extras.getInt("type", -1),
                        extras.getInt("size", 1), extras.getString("name", ""));
                return writeRoot(ctx, root);
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 一次写入左右两栏（+ 可选 mode）。两栏合成同一次读改写，消除"后写盖前写"
     * 的丢列窗口；这也是编辑器保存路径唯一应该用的入口。
     * extras: tab, l_serviceId/l_type/l_size/l_name, r_*, mode(可选)
     */
    private static boolean setColumns(Context ctx, Bundle extras) {
        if (extras == null) {
            return false;
        }
        String tab = extras.getString("tab");
        if (TextUtils.isEmpty(tab)) {
            return false;
        }
        try {
            synchronized (LAYOUT_LOCK) {
                JSONObject root = readRoot(ctx);
                JSONObject tabObj = root.optJSONObject(tab);
                if (tabObj == null) {
                    tabObj = new JSONObject();
                    root.put(tab, tabObj);
                }
                String lsid = extras.getString("l_serviceId");
                if (!TextUtils.isEmpty(lsid)) {
                    putColumn(tabObj, "col_0", lsid,
                            extras.getInt("l_type", -1), extras.getInt("l_size", 1),
                            extras.getString("l_name", ""));
                }
                String rsid = extras.getString("r_serviceId");
                if (!TextUtils.isEmpty(rsid)) {
                    putColumn(tabObj, "col_1", rsid,
                            extras.getInt("r_type", -1), extras.getInt("r_size", 1),
                            extras.getString("r_name", ""));
                }
                String mode = extras.getString("mode");
                if (!TextUtils.isEmpty(mode)) {
                    root.put("mode", mode);
                }
                return writeRoot(ctx, root);
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Records "this package loaded the module", keyed by process name. */    private static void noteProcess(Context ctx, Bundle extras) {
        if (extras == null) {
            return;
        }
        String process = extras.getString(KEY_PROCESS);
        String version = extras.getString(KEY_VERSION, "");
        if (TextUtils.isEmpty(process)) {
            return;
        }
        try {
            android.content.SharedPreferences p = prefs(ctx);
            JSONObject all;
            try {
                all = new JSONObject(p.getString(PREF_PROCESSES, "{}"));
            } catch (Throwable t) {
                all = new JSONObject();
            }
            JSONObject entry = new JSONObject();
            entry.put("at", System.currentTimeMillis());
            entry.put("version", version);
            all.put(process, entry);
            p.edit().putString(PREF_PROCESSES, all.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    static android.content.SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
