package io.github.cechang256.xiaobu;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;

import org.json.JSONObject;

/**
 * Reads/writes the configuration the hooks act on.
 *
 * All hooked processes (com.oplus.pantanal.ums etc.) cannot read the module's
 * private prefs file, so they ask the module's exported ContentProvider
 * ({@link LayoutProvider}) instead; the provider path is preferred whenever it
 * answers, which keeps behaviour identical in every process. Writes go through
 * LayoutProvider.METHOD_SET_COLUMN (editor interception hook) or are seeded by
 * adb into the prefs file the provider serves.
 *
 * Config shape (all keys optional):
 *
 *   {
 *     "features": { "model": true, "decision": true, "assist": false },
 *     "tab_0":    { "col_0": {"serviceId":"...","type":1,"size":1,"name":"..."},
 *                   "col_1": {...} },
 *     "decision": { ... }             // kept for the ranking hook
 *   }
 */
final class LayoutStore {

    // round 52：原 ACTION_PUSH / EXTRA_LAYOUT 广播协议已删除——全工程没有
    // 发送方（唯一的接收器 LayoutReceiver 也一并删除），配置变更走 TTL 缓存
    // 加保存后 invalidate。

    /** Feature switches（主线只剩这两个，其余实验开关已随代码删除）。 */
    static final String FEATURE_MODEL = "model";
    /** 编辑器里追加"系统工具+月历"页签。 */
    static final String FEATURE_CUSTOM_TAB = "custom_tab";

    /** How long a provider snapshot is reused before asking again. */
    /**
     * Broadcast push is unreliable on this ROM (registerReceiver with a system
     * Context throws on Android 17), so the cache is short enough that a config
     * change is picked up within a few seconds on its own.
     */
    private static final long CACHE_TTL_MS = 3000L;

    private static volatile String sCachedRaw;
    private static volatile boolean sCachedEnabled = true;
    private static volatile long sCachedAt;

    /** One of the two columns of a group-card tab. */
    static final class Column {
        String serviceId = "";
        int type = -1;
        int size = 1;
        String name = "";

        boolean isSet() {
            return !TextUtils.isEmpty(serviceId);
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("serviceId", serviceId);
                o.put("type", type);
                o.put("size", size);
                o.put("name", name);
            } catch (Throwable ignored) {
            }
            return o;
        }

        static Column fromJson(JSONObject o) {
            if (o == null) {
                return null;
            }
            Column c = new Column();
            c.serviceId = o.optString("serviceId", "");
            c.type = o.optInt("type", -1);
            c.size = o.optInt("size", 1);
            c.name = o.optString("name", "");
            return c.isSet() ? c : null;
        }

        @Override
        public String toString() {
            return serviceId + "/" + type + "/" + size + "/" + name;
        }
    }

    private LayoutStore() {
    }

    static void invalidate() {
        synchronized (LayoutStore.class) {
            sCachedRaw = null;
            sCachedAt = 0L;
        }
    }

    /**
     * Raw layout JSON, or null when nothing has been customised. When the
     * provider cannot be reached we fall back to local prefs, which is the same
     * store the provider serves, so our own process keeps working.
     */
    static String rawLayout(Context ctx) {
        long now = System.currentTimeMillis();
        synchronized (LayoutStore.class) {
            if (sCachedRaw != null && now - sCachedAt < CACHE_TTL_MS) {
                return sCachedRaw.isEmpty() ? null : sCachedRaw;
            }
        }
        String raw = null;
        boolean enabled = true;
        Bundle b = callProvider(ctx);
        if (b != null && b.containsKey(LayoutProvider.KEY_LAYOUT)) {
            raw = b.getString(LayoutProvider.KEY_LAYOUT);
            enabled = b.getBoolean(LayoutProvider.KEY_ENABLED, true);
        } else {
            android.content.SharedPreferences p = localPrefs(ctx);
            raw = p.getString(LayoutProvider.PREF_LAYOUT, null);
            enabled = p.getBoolean(LayoutProvider.PREF_ENABLED, true);
        }
        synchronized (LayoutStore.class) {
            sCachedRaw = raw == null ? "" : raw;
            sCachedEnabled = enabled;
            sCachedAt = now;
        }
        return raw;
    }

    static boolean enabled(Context ctx) {
        rawLayout(ctx); // refresh the cache
        synchronized (LayoutStore.class) {
            return sCachedEnabled;
        }
    }

    /** 桌面样式模式：custom = 插件改写生效（col_0/col_1）；native = 系统自带 tab。 */
    static final String MODE_CUSTOM = "custom";
    static final String MODE_NATIVE = "native";
    /** 是否 custom 模式（col_0/col_1 生效）。 */
    static boolean isCustomMode(String m) {
        return m != null && m.startsWith("custom");
    }

    /**
     * 往返标记（round 52）：对调走"模式往返"时写入的毫秒时间戳，与 mode 在
     * **同一次读改写**里落盘。
     *
     * <p>为什么不用进程内静态量：rewriter 读 mode 是走 provider 的跨进程调用，
     * 纯内存态只在"写的人"与"读的人"同进程时成立，进程被杀后彻底丢失；而落盘
     * 的 mode=native 又没有线索区分"用户主动选的"与"往返留下的"。
     *
     * <p>时间戳把中间态变成可判定的三态：
     * <ul>
     *   <li>有标记且未过期 → 往返中间态，按 native 生效（自定义卡离场）；</li>
     *   <li>有标记但已过期 → 往返没走完（进程被杀 / 期间无查询），按 custom
     *       生效；这就是自愈，不依赖任何进程内状态或启动时序；</li>
     *   <li>没有标记 → mode 是用户选择，原样生效（"切回系统样式"不受影响）。</li>
     * </ul>
     */
    static final String KEY_ROUNDTRIP_AT = "roundtrip_at";

    /** 往返标记（0 = 没有进行中的往返）。 */
    static long roundTripAt(Context ctx) {
        try {
            String raw = rawLayout(ctx);
            if (TextUtils.isEmpty(raw)) {
                return 0L;
            }
            return new JSONObject(raw).optLong(KEY_ROUNDTRIP_AT, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 当前生效样式。三个编辑器 tab 共用同一个桌面小组件，谁最后保存谁生效；
     * col_0/col_1 是自定义 tab 的常驻档案，native 模式下只是不应用，不删除
     * ——这样系统 tab 和自定义 tab 互不干扰，可随意切回。
     */
    static String mode(Context ctx) {
        // 一次解析同时取出 mode / roundtrip_at / roundms：本方法在每次场景查询
        // 都会走，原来拆成三次读会多解析两遍同一串 JSON。
        try {
            String raw = rawLayout(ctx);
            if (!TextUtils.isEmpty(raw)) {
                JSONObject o = new JSONObject(raw);
                long at = o.optLong(KEY_ROUNDTRIP_AT, 0L);
                if (at > 0) {
                    int holdMs = 1200;
                    JSONObject f = o.optJSONObject("features");
                    if (f != null) {
                        holdMs = f.optInt("roundms", 1200);
                    }
                    if (holdMs < 150) {
                        holdMs = 150;
                    }
                    if (System.currentTimeMillis() - at > holdMs + 3000L) {
                        // 标记过期 = 上次往返没走完：按 custom 生效。不改落盘值，
                        // 清理由启动自愈或下一次保存完成。
                        return MODE_CUSTOM;
                    }
                }
                String m = o.optString("mode", "");
                if (m.length() > 0) {
                    return m;
                }
                // 向后兼容：从没写过 mode 的老配置，按 col_1 推断
                JSONObject tab = o.optJSONObject("tab_0");
                if (tab != null && tab.optJSONObject("col_1") != null) {
                    return MODE_CUSTOM;
                }
            }
        } catch (Throwable ignored) {
        }
        return MODE_NATIVE;
    }

    /** 只读落盘值，忽略进程内临时覆盖（往返收尾时要和它比较）。 */
    static String storedMode(Context ctx) {
        try {
            String raw = rawLayout(ctx);
            if (!TextUtils.isEmpty(raw)) {
                String m = new JSONObject(raw).optString("mode", "");
                if (m.length() > 0) {
                    return m;
                }
                // 向后兼容：从没写过 mode 的老配置，按 col_1 推断
                JSONObject tab = new JSONObject(raw).optJSONObject("tab_0");
                if (tab != null && tab.optJSONObject("col_1") != null) {
                    return MODE_CUSTOM;
                }
            }
        } catch (Throwable ignored) {
        }
        return MODE_NATIVE;
    }

    /**
     * 用户选择样式（"切到系统 tab"或"切回自定义"）——**顺带清掉往返标记**，
     * 这样任何一次真实保存都会让上一次残留的中间态失效。
     */
    static boolean setMode(Context ctx, String mode) {
        return writeMode(ctx, mode, 0L);
    }

    /** 开始往返：mode=native + 时间戳，单次读改写，原子。 */
    static boolean beginRoundTripMark(Context ctx) {
        return writeMode(ctx, MODE_NATIVE, System.currentTimeMillis());
    }

    /** 结束往返：mode=custom + 清标记，单次读改写，原子。 */
    static boolean endRoundTripMark(Context ctx) {
        return writeMode(ctx, MODE_CUSTOM, 0L);
    }

    /**
     * 写 mode / 往返标记。
     *
     * @param mode        非空才写 mode（null = 只动标记）
     * @param roundTripAt &gt;0 写时间戳；0 = 删除该键
     * @return 是否落盘成功；调用方（往返状态机）必须据此决定是否保留状态重试
     */
    static boolean writeMode(Context ctx, String mode, long roundTripAt) {
        if (ctx == null) {
            return false;
        }
        try {
            Bundle b = new Bundle();
            if (mode != null && mode.length() > 0) {
                b.putString("mode", mode);
            }
            b.putLong("roundtrip_at", roundTripAt);
            Bundle out = ctx.getContentResolver().call(
                    android.net.Uri.parse("content://" + LayoutProvider.AUTHORITY),
                    LayoutProvider.METHOD_SET_MODE, null, b);
            boolean ok = out != null && out.getBoolean("ok", false);
            if (!ok) {
                ModuleInit.log("set mode write failed mode=" + mode
                        + " rt=" + roundTripAt);
            }
            invalidate();
            return ok;
        } catch (Throwable t) {
            ModuleInit.log("set mode fail " + t);
            return false;
        }
    }

    /**
     * 一次写入两栏档案（round 52）。
     *
     * <p>原来左、右两栏各发一次 provider 调用，每次都是"读整串 JSON → 改 →
     * 写回"。两次调用之间如果插进别的写（例如往返切 mode、或另一线程的
     * setMode），后写的那次会把前一次刚写的列盖掉——丢列，而且两次调用都
     * 返回成功，日志看不出来。合成一次调用 = 一次读改写，从根上消除这个窗口。
     *
     * @param mode 非空时顺带写 mode（null = 不动）
     */
    static void setColumns(Context ctx, String tab,
            String lSid, int lType, int lSize, String lName,
            String rSid, int rType, int rSize, String rName, String mode) {
        try {
            if (ctx == null) {
                return;
            }
            Bundle b = new Bundle();
            b.putString("tab", tab);
            if (lSid != null && lSid.length() > 0) {
                b.putString("l_serviceId", lSid);
                b.putInt("l_type", lType);
                b.putInt("l_size", lSize);
                b.putString("l_name", lName == null ? "" : lName);
            }
            if (rSid != null && rSid.length() > 0) {
                b.putString("r_serviceId", rSid);
                b.putInt("r_type", rType);
                b.putInt("r_size", rSize);
                b.putString("r_name", rName == null ? "" : rName);
            }
            if (mode != null && mode.length() > 0) {
                b.putString("mode", mode);
            }
            Bundle out = ctx.getContentResolver().call(
                    android.net.Uri.parse("content://" + LayoutProvider.AUTHORITY),
                    LayoutProvider.METHOD_SET_COLUMNS, null, b);
            if (out != null && !out.getBoolean("ok", true)) {
                ModuleInit.log("set columns rejected/失败");
            }
            invalidate();
        } catch (Throwable t) {
            ModuleInit.log("set columns fail " + t);
        }
    }

    /**
     * 数值型特性开关（layout_json.features 里以整数读取）。
     *
     * <p>对调往返的"系统卡停留时长"用这个读，便于用 adb 改配置做 A/B，
     * 不必每次重新编译装机。
     */
    static int featureInt(Context ctx, String name, int def) {
        try {
            String raw = rawLayout(ctx);
            if (TextUtils.isEmpty(raw)) {
                return def;
            }
            JSONObject features = new JSONObject(raw).optJSONObject("features");
            if (features == null || !features.has(name)) {
                return def;
            }
            return features.optInt(name, def);
        } catch (Throwable t) {
            return def;
        }
    }

    static boolean feature(Context ctx, String name, boolean def) {        try {
            String raw = rawLayout(ctx);
            if (TextUtils.isEmpty(raw)) {
                return def;
            }
            JSONObject features = new JSONObject(raw).optJSONObject("features");
            if (features == null || !features.has(name)) {
                return def;
            }
            return features.optBoolean(name, def);
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 桌面决策层改写（dswap）的闸门（1.35 起）。
     *
     * <p>语义：{@code features.dswap} 是<strong>可选覆盖</strong>——显式 true/false 都
     * 照办；键缺失时由 {@link #mode} 决定（custom → 开，native → 关）。用户选中第三个
     * 「自定义」并保存后 mode 就是 custom，于是第一次查询即生效，不需要任何键、
     * 也不需要"先打开一次编辑器再保存"来点亮配置。
     *
     * <p>注意 {@code mode()} 自带两条兼容逻辑，这里刻意复用它而不是直接读 JSON 的
     * "mode" 字段：①从没写过 mode 的老配置按 col_1 推断 custom；②往返标记过期按
     * custom 生效（自愈）。闸门若绕过它们，"切回自定义后不复写"就会重现。
     *
     * @param viaExplicit 非 null 且长度 ≥1 时写回来源：true = 由显式键决定，
     *                    false = 由 mode 推断（只有 diag 用，便于现场判断）
     */
    static boolean dswapOn(Context ctx, boolean[] viaExplicit) {
        Boolean explicit = SwapGate.explicit(rawLayout(ctx));
        if (viaExplicit != null && viaExplicit.length > 0) {
            viaExplicit[0] = explicit != null;
        }
        return SwapGate.on(explicit, mode(ctx));
    }

    // ---- 1.38：让 launcher 重建卡片槽位的注入闸门 ----------------

    /** 闸门状态单独存一份 prefs，不写进 layout_json（避免污染用户配置）。 */
    private static final String PREFS_INJECT = "xiaobu_unlocker_inject";
    private static final String K_INJECT_SIG = "sig";
    private static final String K_INJECT_COUNT = "inject_count";
    private static final String K_INJECT_AT = "last_injected_at";

    /**
     * 判定这一次场景查询要不要给两行 cardConfig 打 isUserSelected 标记。
     *
     * <p>开关：{@code layout_json.features.rebuild_inject}（<b>默认开</b>，写 false 即
     * 立刻停掉注入，不需要退版本）。次数：{@code features.rebuild_inject_max}
     * （默认 3）。配置签名由 {@code mode} + 本次左右组合 {@code pairKey} 组成，
     * 签名一变就重新武装；计数落 prefs，进程重启不会从 0 重来。
     *
     * <p>只在“这次真的注入”时写盘（一次配置变化最多 3 次），常态查询是纯内存读。
     */
    static InjectGate.Decision decideInject(Context ctx, String pairKey) {
        boolean on = feature(ctx, InjectGate.FEATURE, true);
        int max = featureInt(ctx, InjectGate.FEATURE_MAX, InjectGate.LIMIT);
        String sig = InjectGate.signature(mode(ctx), pairKey);
        android.content.SharedPreferences p = injectPrefs(ctx);
        InjectGate.State prev = InjectGate.parse(
                p.getString(K_INJECT_SIG, null),
                p.getInt(K_INJECT_COUNT, 0),
                p.getLong(K_INJECT_AT, 0L));
        InjectGate.Decision d = InjectGate.next(prev, sig, on, max,
                System.currentTimeMillis());
        if (d.inject) {
            try {
                p.edit()
                        .putString(K_INJECT_SIG, d.state.sig)
                        .putInt(K_INJECT_COUNT, d.state.count)
                        .putLong(K_INJECT_AT, d.state.lastAt)
                        .apply();
            } catch (Throwable ignored) {
            }
        }
        return d;
    }

    private static android.content.SharedPreferences injectPrefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS_INJECT, Context.MODE_PRIVATE);
    }

    // ---- 1.39：模式扰动（阶段 1 检测 / 阶段 2 扰动）----------------

    /** 扰动状态也单独存一份 prefs：窗内被杀/断电，下次读到的 until 已过期即正确态。 */
    private static final String PREFS_PERTURB = "xiaobu_unlocker_perturb";
    private static final String K_PERTURB_SIG = "sig";
    private static final String K_PERTURB_UNTIL = "until";
    private static final String K_PERTURB_ARMED = "armed_at";
    private static final String K_PERTURB_COUNT = "count";
    private static final String K_PERTURB_SINCE = "since";

    static PerturbGate.State perturbState(Context ctx) {
        try {
            android.content.SharedPreferences p = perturbPrefs(ctx);
            return PerturbGate.parse(
                    p.getString(K_PERTURB_SIG, null),
                    p.getLong(K_PERTURB_UNTIL, 0L),
                    p.getLong(K_PERTURB_ARMED, 0L),
                    p.getInt(K_PERTURB_COUNT, 0),
                    p.getLong(K_PERTURB_SINCE, 0L));
        } catch (Throwable t) {
            // 读不出来就当没有窗口：宁可这次不扰动，也不能抛进改写主链路。
            return PerturbGate.State.EMPTY;
        }
    }

    static void savePerturbState(Context ctx, PerturbGate.State s) {
        if (s == null) {
            return;
        }
        try {
            perturbPrefs(ctx).edit()
                    .putString(K_PERTURB_SIG, s.sig)
                    .putLong(K_PERTURB_UNTIL, s.until)
                    .putLong(K_PERTURB_ARMED, s.armedAt)
                    .putInt(K_PERTURB_COUNT, s.count)
                    .putLong(K_PERTURB_SINCE, s.since)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 判定这一次“刚切到 custom”要不要开扰动窗口。
     *
     * <p>开关 {@code features.perturb_rebuild} 默认 <b>false</b>：1.39 先把检测
     * （{@code perturb candidate}/{@code perturb useless}）跑起来，真扰动要等
     * 真机数据证明“换成原生卡组真的会换来一次 createChildCardView”后再打开。
     */
    static PerturbGate.Decision startPerturb(Context ctx, String pairKey) {
        boolean on = feature(ctx, PerturbGate.FEATURE, false);
        PerturbGate.Decision d = PerturbGate.start(perturbState(ctx),
                PerturbGate.signature(pairKey), on, System.currentTimeMillis());
        if (d.serveNative) {
            savePerturbState(ctx, d.state);
        }
        return d;
    }

    private static android.content.SharedPreferences perturbPrefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS_PERTURB, Context.MODE_PRIVATE);
    }

    /** tabIndex -> [col0, col1]; null entries mean "leave the ROM default". */
    static Column[] columnsFor(Context ctx, int tabIndex) {
        try {
            String raw = rawLayout(ctx);
            if (TextUtils.isEmpty(raw)) {
                return null;
            }
            JSONObject tab = new JSONObject(raw).optJSONObject("tab_" + tabIndex);
            if (tab == null) {
                return null;
            }
            Column[] out = new Column[2];
            out[0] = Column.fromJson(tab.optJSONObject("col_0"));
            out[1] = Column.fromJson(tab.optJSONObject("col_1"));
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bundle callProvider(Context ctx) {
        try {
            return ctx.getContentResolver().call(
                    android.net.Uri.parse("content://" + LayoutProvider.AUTHORITY),
                    LayoutProvider.METHOD_GET, null, null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static android.content.SharedPreferences localPrefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(LayoutProvider.PREFS, Context.MODE_PRIVATE);
    }
}
