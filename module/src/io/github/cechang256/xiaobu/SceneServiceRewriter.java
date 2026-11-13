package io.github.cechang256.xiaobu;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.List;

import io.github.cechang256.xiaobu.xp.XC_MethodHook;
import io.github.cechang256.xiaobu.xp.XposedBridge;
import io.github.cechang256.xiaobu.xp.XposedHelpers;

/**
 * Decision-layer rewrite of the 应用建议 (guaranteed) service, v2.
 *
 * Evidence (2026-11-07):
 *   - DecisionProvider ("com.pantanal.server.decision.result.DecisionProvider")
 *     receives ArrayList<ResultEntity> rows; the entry with serviceId 536877097
 *     (policyName DYNAMIC_SUBSTITUTE) becomes the right column of scene
 *     210000000. Probe log: "before guaranteed args=[ArrayList(2)[ResultEntity{
 *     action=pantanal.intent.applicationsuggestions.SUBSTITUTE, ...}]]".
 *   - Rewriting the DB rows directly is defeated by the intent pipeline
 *     (experiments E3/E5): the guaranteed list is rebuilt from the cloud
 *     intent config and reused from cache.
 *   - Rewriting the live ResultEntity here rewrites everything downstream:
 *     the persisted row, the notification to the launcher plugin, and the
 *     composed two-column scene. Because the entity keeps its
 *     applicationsuggestions identity slot, no scene-validity check applies
 *     (that check only rejects cards swapped in at the render layer).
 *
 * ResultEntity fields (javap, ums classes.dex): serviceId/hostPackage are
 * final (reflection still writes them on ART); the card info JSON lives in
 * "cardConfig"; the size list lives in "supportSize" ("1,2" for 应用建议).
 *
 * Replacement identity comes from layout config tab_0.col_1 (raw JSON, so
 * componentName/protocol/category/... are available), gated by feature
 * "dswap".
 */
final class SceneServiceRewriter {

    private static final String CLS =
            "com.pantanal.server.decision.result.DecisionProvider";
    private static final String STOCK_SID = "536877097";

    /**
     * 上一轮真正交给 launcher 的左右卡，只用于判断"这次保存是否只是对调"。
     *
     * <p>为什么需要这个判断：launcher/metis 只在**卡片集合发生变化**时重建
     * 界面，纯对调不改变集合，因此会被当成"无变化"跳过——这是"预览成功、
     * 桌面不动"的根因（数据层一直是对的）。对调必须走模式往返让两张卡
     * 离场再入场，见 {@link #beginRoundTrip}。
     */
    static volatile String sServedL;
    static volatile String sServedR;

    /**
     * 模式往返（round 40）：**照搬系统"切到系统工具+应用建议 → 切回自定义"
     * 的做法**，不再塞什么中转卡。
     *
     * 系统切 tab 时并没有插入额外卡片：切过去 = mode 变 native，模块停止
     * 改写，桌面显示系统卡（实时气象预警 / 应用建议），用户的两张自定义
     * 卡因此**自然离场**；切回来 = mode 变 custom，两张卡**重新入场**，
     * launcher 按新顺序重新排位。所以"离场"是模式切换的副作用。
     *
     * 纯对调时，本模块自动走一遍这个往返：
     *   ① mode=native + 通知 → 系统内容上台，自定义两张卡离场（约 700ms）
     *   ② mode=custom + 通知 → 两张卡按新档案重新入场 → 位置重排
     * 推进同样不依赖定时器：由数据查询次数 + 时间双条件驱动（见
     * rewriteSceneList 的 native 分支）。
     */
    static volatile boolean sRoundTrip;
    /** 写盘一直失败时的放弃阈值：超过就停止重试，避免每次查询都打一次 IPC。 */
    private static final long GIVE_UP_MS = 8000L;
    private static volatile long sRoundAt;
    private static volatile int sRoundServed;
    /** 场景查询总次数（单调递增）。用来判断翻转后 launcher 是否真的重拉。 */
    private static volatile int sSceneServed;
    static volatile Runnable sRoundNotify;

    /** 场景查询总次数；调用方记录翻转时的值，之后对比即可。 */
    static int sceneServed() {
        return sSceneServed;
    }

    static void beginRoundTrip() {
        sRoundTrip = true;
        sRoundAt = System.currentTimeMillis();
        sRoundServed = 0;
        XposedBridge.log(ModuleInit.TAG + ": dswap roundtrip begin (native first)");
    }

    /**
     * 往返第二段的推进器（round 47 抽出）：谁先看到"已经拉过一次数据 + 时间
     * 够了"就由谁切回 custom。
     *
     * <p>为什么不能只在场景查询里推进：01:30 实测那段往返里 launcher 只拉了
     * decision/services，**一次场景查询都没来**，状态机原地卡死——mode 停在
     * native，两张自定义卡再也没回来。所以 decision 查询钩子也调这里。
     */
    static synchronized void tickRoundTrip() {
        if (!sRoundTrip) {
            return;
        }
        Context ctx = AppContext.get();
        if (ctx == null) {
            return;
        }
        long held = System.currentTimeMillis() - sRoundAt;
        int holdMs = LayoutStore.featureInt(ctx, "roundms", 800);
        if (holdMs < 150) {
            holdMs = 150;
        }
        if (held < holdMs && held < 2000L) {
            return;
        }
        // round 52：**先写盘、成功才清状态**。上一版顺序是
        //   sRoundTrip = false;  →  setMode(custom);  →  无条件打印"-> custom"
        // 一次 provider 写失败就等于「状态机已放弃 + 日志报成功 + 桌面停在
        // 系统卡」，从日志上完全看不出来。现在失败就保留 sRoundTrip 重试。
        boolean ok = LayoutStore.endRoundTripMark(ctx);
        if (!ok) {
            if (held < GIVE_UP_MS) {
                XposedBridge.log(ModuleInit.TAG
                        + ": dswap roundtrip -> custom write failed, retry"
                        + " (held=" + held + "ms)");
                return;
            }
            // 一直写不进去：放弃重试，但读侧已按"标记过期"回 custom，
            // 桌面不会停在系统卡，这里只负责别再刷 IPC。
            XposedBridge.log(ModuleInit.TAG
                    + ": dswap roundtrip give up after " + held + "ms (write failed)");
        }
        sRoundTrip = false;
        XposedBridge.log(ModuleInit.TAG + ": dswap roundtrip -> custom (ok=" + ok
                + " served=" + sRoundServed + " held=" + held + "ms)");
        Runnable r = sRoundNotify;
        if (r != null) {
            try {
                r.run();
            } catch (Throwable t) {
                XposedBridge.log(ModuleInit.TAG + ": dswap roundtrip notify fail " + t);
            }
        }
    }

    private SceneServiceRewriter() {
    }

    static void install(ClassLoader cl) throws Throwable {
        Class<?> cls = XposedHelpers.findClass(CLS, cl);
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    handleArgs(param.args);
                } catch (Throwable t) {
                    Diag.log("dswap", "handle failed " + t);
                }
            }
        };
        int ok = 0;
        for (String name : new String[]{"guaranteed", "cardConfigDecision"}) {
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                if (!name.equals(m.getName())
                        || java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length < 1 || ps[0] != List.class) {
                    continue;
                }
                Object[] params = new Object[ps.length + 1];
                System.arraycopy(ps, 0, params, 0, ps.length);
                params[ps.length] = hook;
                try {
                    XposedHelpers.findAndHookMethod(cls, name, params);
                    ok++;
                } catch (Throwable t) {
                    Diag.log("dswap", "hook " + name + " failed " + t);
                }
            }
        }
        Diag.log("dswap", "installed on DecisionProvider, methods=" + ok);
        XposedBridge.log(ModuleInit.TAG + ": dswap installed on " + cls + " methods=" + ok);

        // Observation: log every query() with entrances + caller so we learn
        // which flow actually reaches the guaranteed() branch.
        XC_MethodHook qHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    // round 52：这条日志原先无条件执行，而它是**每次决策查询**
                    // 一条（含一次 getCallingPackage 的跨进程调用）。文档里写的
                    // "probe 关着不产生日志"与之不符，现在按 probe 开关走：
                    // 关着时连参数提取和 getCallingPackage 都省掉。
                    if (LayoutStore.feature(AppContext.get(), "probe", false)) {
                        Object uri = param.args.length > 0 ? param.args[0] : null;
                        Object sel = param.args.length > 3 ? param.args[3] : null;
                        String pkg = null;
                        try {
                            pkg = (String) XposedHelpers.callMethod(param.thisObject,
                                    "getCallingPackage");
                        } catch (Throwable ignored) {
                        }
                        XposedBridge.log(ModuleInit.TAG + ": dswap query uri=" + uri
                                + " args=" + (sel == null ? "null"
                                        : java.util.Arrays.toString((Object[]) sel))
                                + " caller=" + pkg);
                    }
                    // round 47：往返期间 launcher 可能**只**拉 decision/services、
                    // 一次场景查询都不发（01:30 实测），那就由这里推进第二段。
                    if (sRoundTrip) {
                        sRoundServed++;
                        tickRoundTrip();
                    }
                } catch (Throwable t) {
                    // never break the provider
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(cls, "query",
                    android.net.Uri.class, String[].class, String.class,
                    String[].class, String.class, qHook);
            XposedBridge.log(ModuleInit.TAG + ": dswap query hook installed");
        } catch (Throwable t) {
            XposedBridge.log(ModuleInit.TAG + ": dswap query hook failed " + t);
        }

        installScene(cl);
    }

    /**
     * Scene path hook. The launcher's two-column card group is served by
     * SceneDecisionProvider (evidence 00:34: last_query SP shows the launcher
     * queried the scene provider and received sceneId 210000000 /
     * serviceId 536877097 / supportSize 1,2). toCursor(List, String[]) is the
     * final serialization point for every scene result list, so rewriting the
     * 应用建议 entity here reaches the widget regardless of which upstream
     * branch (DB rows, switch-guaranteed, compensation) produced it.
     */
    private static void installScene(ClassLoader cl) throws Throwable {
        Class<?> cls = XposedHelpers.findClass(
                "com.pantanal.server.decision.result.SceneDecisionProvider", cl);
        int hooked = 0;
        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
            if (!"toCursor".equals(m.getName())) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 2 || ps[0] != List.class) {
                continue;
            }
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        // 原来这里每次查询都取 Binder.getCallingUid() 再调
                        // PackageManager.getNameForUid()（跨进程查询）只为打一行
                        // "scene query caller="。诊断目的已达成，常态下纯属白耗，
                        // 去掉后每次查询少一次 IPC。
                        rewriteSceneList(param.args[0]);
                    } catch (Throwable t) {
                        XposedBridge.log(ModuleInit.TAG + ": dswap scene fail " + t);
                    }
                }
            });
            hooked++;
        }
        XposedBridge.log(ModuleInit.TAG + ": dswap scene toCursor hooked n=" + hooked);
    }

    /** 左栏默认态可选卡的 sid 全集（CloudCardConfig 播种卡目录）。 */
    private static final java.util.Set<String> DEFAULT_CATALOG_SIDS = new java.util.HashSet<String>(
            java.util.Arrays.asList("536871060", "536878193", "536878390", "536878384",
                    "536870911", "536878636", "536875395", "536878567",
                    "536877064", "536877066", "536877063", "536877065",
                    "536878709"));
    /** 插件侧硬编码兜底，也是场景 210000000 的固定成员，不算左栏默认位。 */
    private static final String STOCK_SID2 = "536877738";

    /** 本次保存是否只是"两栏互换"（集合不变 → launcher 会跳过重绘）。 */
    static boolean isPureSwap(String newL, String newR) {
        String pl = sServedL, pr = sServedR;
        if (pl == null || pr == null || newL == null || newR == null) {
            // 不知道上一次交给桌面的是什么 → 一律按对调处理（宁可闪一下，
            // 也不要静默不刷新）。
            return true;
        }
        return newL.equals(pr) && newR.equals(pl);
    }

    /** 上一次看到的左右组合；变化即视为"用户刚改完设置"。 */
    private static String sLastPairKey;
    /** 上一次记录「查询了但一行都没改写」的时间，用于 60 秒节流。 */
    private static long sLastNoRewriteMs;

    private static String sidOf(JSONObject c) {
        return c == null ? "-" : c.optString("serviceId", "-");
    }

    private static String nameOf(JSONObject c) {
        return c == null ? "-" : c.optString("name", "-");
    }

    private static void rewriteSceneList(Object listObj) throws org.json.JSONException {
        if (!(listObj instanceof List)) {
            return;
        }
        // 每次场景查询都计数：对调往返"翻转回 custom"之后要判断 launcher
        // 有没有真的重拉一次，没拉就重发通知（sSceneServed 是单调的）。
        sSceneServed++;
        Context ctx = AppContext.get();
        if (ctx == null) {
            return;
        }
        boolean on = LayoutStore.enabled(ctx)
                && LayoutStore.feature(ctx, "dswap", false);
        String mode = LayoutStore.mode(ctx);
        JSONObject cfgR = on ? rightColumnConfig(ctx) : null;
        JSONObject cfgL = on ? leftColumnConfig(ctx) : null;
        // 开关 / mode / 左右组合任何一项变化（包括进程第一次看到）都开一个
        // 5 秒窗口：窗口内的桌面刷新会额外写进 LSPosed 日志，用户导出后
        // 就能定位"改完没生效"到底卡在哪一步。
        String pairKey = (on ? "on" : "off") + "|" + mode + "|"
                + sidOf(cfgL) + "|" + sidOf(cfgR);
        if (!pairKey.equals(sLastPairKey)) {
            sLastPairKey = pairKey;
            Diag.openWindow(5000);
            Diag.window("dswap", "config -> mode=" + mode + " dswap=" + (on ? "on" : "off")
                    + " L=" + nameOf(cfgL) + "(" + sidOf(cfgL) + ")"
                    + " R=" + nameOf(cfgR) + "(" + sidOf(cfgR) + ")");
        }
        // 三个编辑器 tab 互不干扰：mode=custom 时改写成自定义档案；
        // mode=native 时**反向工作**——把可能残留的自定义改写恢复成系统
        // 原生内容。不能简单"罢工"：custom 模式下 guaranteed 槽被改写过，
        // 上游管线缓存/在途列表可能还带着自定义卡，罢工后没人改回去，
        // 表现为"桌面切不回系统样式"、右栏身份与数据错配渲染成
        // "小布建议说明"（实测）。
        if (!on) {
            Diag.window("dswap", "skip: dswap off");
            return;
        }
        if (!LayoutStore.isCustomMode(mode)) {
            Diag.window("dswap", "skip: mode=" + mode);
            revertSceneList(ctx, (List<?>) listObj);
            // 模式往返的第一段（native）：系统内容已经上台，自定义两张卡
            // 已离场。等 launcher 真正拉过一次、且已过 roundms，就切回
            // custom 让两张卡按新档案重新入场（位置会被重排）。
            if (sRoundTrip) {
                sRoundServed++;
                tickRoundTrip();
            }
            return;
        }
        if (cfgR == null && cfgL == null) {
            Diag.window("dswap", "skip: no config");
            return;
        }
        // 左右同卡会让插件丢弃整个组合（E7 实证），此时整体不动最安全。
        if (cfgR != null && cfgL != null
                && cfgR.optString("serviceId", "").equals(cfgL.optString("serviceId", ""))) {
            Diag.window("dswap", "skip L==R (" + cfgL.optString("serviceId") + ")");
            return;
        }
        List<?> list = (List<?>) listObj;
        // 槽位映射（单侧实验定案，勿凭对调结果反推）：
        //   桌面左栏 = 场景 210000000 的第一张非固定卡行 → col_0
        //   桌面右栏 = 097 保证卡槽位行（policy=DYNAMIC_SUBSTITUTE）→ col_1
        // 教训：两栏同时互换时，两种映射互为镜像、显示结果完全相同，无法
        // 从对调结果反推；只能改单侧（另一侧保持）再看桌面，才不会误判。
        int nR = 0, nL = 0;
        boolean leftDone = false;
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            if (o == null) {
                continue;
            }
            String sid = getStr(o, "serviceId");
            String scene = getStr(o, "sceneId");

            // 右栏：保证卡槽位（536877097）→ col_1
            if (cfgR != null && STOCK_SID.equals(sid)) {
                if (!applySystemCard(ctx, o, cfgR) && !rewriteSceneEntity(o, cfgR)) {
                    // 两条路都失败：不改写也不计数。留下原卡总比留下半新半旧
                    // 的实体强（后者会污染 launcher 的位置记忆）。
                    Diag.always("dswap", "rewrite skipped R sid="
                            + cfgR.optString("serviceId"));
                    continue;
                }
                nR++;
                continue;
            }
            if (!"210000000".equals(scene)) {
                continue;
            }
            if (STOCK_SID2.equals(sid)) {
                continue;
            }
            if (cfgL != null && !leftDone) {
                // 左栏：第一张非固定卡行 → col_0
                if (!applySystemCard(ctx, o, cfgL) && !rewriteSceneEntity(o, cfgL)) {
                    Diag.always("dswap", "rewrite skipped L sid="
                            + cfgL.optString("serviceId"));
                    continue;
                }
                nL++;
                leftDone = true;
            }
        }
        if (cfgL != null && cfgR != null) {
            // 记录"这次真正交给桌面的左右卡"，供下一次保存判断是否只是对调。
            sServedL = cfgL.optString("serviceId", null);
            sServedR = cfgR.optString("serviceId", null);
        }
        if (nR > 0 || nL > 0) {
            // 常规改写不再写 logcat（这条每次查询都会命中，实测约 1.8 条/秒）。
            // 需要原始细节时看 diag.log；这里只留一条有节流的记录。
            Diag.window("dswap", "scene rewrote L" + nL + "/R" + nR
                    + " L=" + (cfgL == null ? "-" : cfgL.optString("name"))
                    + " R=" + (cfgR == null ? "-" : cfgR.optString("name")));
        } else {
            // 查询来了、却一行都没改写：说明列表里既没有保证卡槽位，也没有
            // 场景 210000000 的可用行（ROM 换过卡槽/字段时会这样）。用户看到
            // 的是"刷新了但卡片没变"，而这里原本静默——补一条永不丢弃的记录，
            // 一次查询只写一条，按 60 秒节流。
            long now = System.currentTimeMillis();
            if (now - sLastNoRewriteMs > 60000L) {
                sLastNoRewriteMs = now;
                Diag.always("dswap", "query without rewrite: rows=" + list.size()
                        + " want L=" + nameOf(cfgL) + "(" + sidOf(cfgL) + ")"
                        + " R=" + nameOf(cfgR) + "(" + sidOf(cfgR) + ")");
            }
        }
    }

    /**
     * native 模式：把残留的自定义改写恢复成系统原生内容。
     *
     *   - guaranteed 槽（097 行）：识别标识是 action 含
     *    "applicationsuggestions"（我们改写时 PRESERVE_FIELDS 保留了
     *     action/policyName，污染实体仍带着这个标识）——只要 sid 不是
     *     应用建议，就用系统工厂重建权威应用建议实体覆盖回去；
     *   - 另一槽（场景 210000000 第一张非固定卡）：原生配置里存了
     *     系统 tab 保存的选择卡时，与它不一致就重建为该卡；原生配置
     *     为空则不动（信任系统自己的默认构建）。
     *   两行身份识别与列名无关，恢复"系统原生内容"的目标不受
     *   round 28 槽位对调影响。
     */
    private static void revertSceneList(Context ctx, List<?> list) {
        String nativeSid = EditorHook.nativeLeftSidFresh();
        int nR = 0, nL = 0;
        boolean leftDone = false;
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            if (o == null) {
                continue;
            }
            String sid = getStr(o, "serviceId");
            String scene = getStr(o, "sceneId");
            if (STOCK_SID.equals(sid)) {
                continue; // guaranteed 槽本来就是应用建议，无需恢复
            }
            String action = getStr(o, "action");
            boolean guaranteed = action != null
                    && action.contains("applicationsuggestions");
            if (guaranteed) {
                JSONObject stock = new JSONObject();
                try {
                    stock.put("serviceId", STOCK_SID);
                } catch (Throwable ignored) {
                }
                if (applySystemCard(ctx, o, stock)) {
                    nR++;
                }
                continue;
            }
            if ("210000000".equals(scene) && !leftDone
                    && !STOCK_SID2.equals(sid)) {
                leftDone = true;
                if (nativeSid != null && nativeSid.length() > 0
                        && !nativeSid.equals(sid)) {
                    JSONObject cfg = new JSONObject();
                    try {
                        cfg.put("serviceId", nativeSid);
                    } catch (Throwable ignored) {
                    }
                    if (applySystemCard(ctx, o, cfg)) {
                        nL++;
                    }
                }
            }
        }
        if (nR > 0 || nL > 0) {
            XposedBridge.log(ModuleInit.TAG + ": dswap revert R" + nR
                    + "/L" + nL + " (mode=native, nativeLeft=" + nativeSid + ")");
            Diag.log("dswap", "revert R" + nR + "/L" + nL);
        }
    }

    /**
     * Rewrites a SceneResultEntity in place (field names per javap).
     *
     * <p>round 52：写入后**回读校验**，不一致就整实体还原并返回 false。
     * 原实现把所有 setF 的 boolean 丢掉、逐字段 catch ignored，只要某个字段
     * 被声明成 final（或反射访问被挡），就会出现"名字换成黄历、serviceId 还是
     * 应用建议"的半新半旧实体——渲染看着对，点进去空白。这种状态比"没改写"
     * 更糟，因为它会污染 launcher 的位置记忆。
     *
     * @return true = 关键词段（serviceId / name / cardConfig）已确认写入
     */
    private static boolean rewriteSceneEntity(Object entity, JSONObject cfg)
            throws org.json.JSONException {
        String sid = cfg.optString("serviceId", "");
        String name = cfg.optString("name", "");
        java.util.Map<Field, Object> snap = snapshotAll(entity);
        boolean okSid = setF(entity, "serviceId", sid);
        boolean okName = setF(entity, "name", name);
        // cardType 以系统抓取的模板为准（1=快应用, 2=native provider, 100=播种卡）
        setF(entity, "cardType", CardTemplates.cardType(sid, cfg.optInt("cardType", 100)));
        setF(entity, "supportSize", String.valueOf(cfg.optInt("size", 1)));
        setF(entity, "hostPackage", cfg.optString("packageName", ""));
        // 系统真实实体：快应用/native provider 卡 url 为空；仅播种卡族有 seedlingupk。
        setF(entity, "url", cfg.optString("url", ""));
        // 有 verbatim 模板（系统真实渲染数据）优先，否则走合成（日历播种卡族已验证可用）。
        String tpl = CardTemplates.cardConfig(sid);
        boolean okCfg = setF(entity, "cardConfig", tpl != null ? tpl : buildCardConfigsJson(cfg));
        // fallback 路径没有工厂样板，手动补 isUserSelected 标记，
        // 否则插件长按菜单"编辑当前卡片"不显示。
        stampUserSelected(entity);

        String gotSid = getStr(entity, "serviceId");
        String gotCfg = getStr(entity, "cardConfig");
        boolean good = okSid && okName && okCfg
                && sid.equals(gotSid)
                && gotCfg != null && gotCfg.length() > 0;
        if (!good) {
            restoreAll(entity, snap);
            XposedBridge.log(ModuleInit.TAG + ": dswap rewrite verify FAIL want=" + sid
                    + " got=" + gotSid + " sidOk=" + okSid + " nameOk=" + okName
                    + " cfgOk=" + okCfg + " -> rolled back");
            return false;
        }
        return true;
    }

    /** 实体全字段快照（校验失败时整实体还原，不留半新半旧状态）。 */
    private static java.util.Map<Field, Object> snapshotAll(Object o) {
        java.util.Map<Field, Object> m = new java.util.HashMap<Field, Object>();
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    m.put(f, f.get(o));
                } catch (Throwable ignored) {
                }
            }
        }
        return m;
    }

    private static void restoreAll(Object o, java.util.Map<Field, Object> snap) {
        for (java.util.Map.Entry<Field, Object> e : snap.entrySet()) {
            try {
                e.getKey().setAccessible(true);
                e.getKey().set(o, e.getValue());
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 给实体 sgExtraInfo（ServiceInfo extras 的来源）补打 isUserSelected 标记。
     * 与 ums 工厂 mv.t.a(SceneResultEntity, long) 行为一致：
     * 写入 isUserSelected=true + userSelectedTimestamp，已有 settingUrl 保留。
     * 插件 PopupHelper 侧"编辑当前卡片"菜单以此为前提（sa.p.s）。
     */
    private static void stampUserSelected(Object entity) {
        try {
            String extra = getStr(entity, "sgExtraInfo");
            JSONObject jo = (extra == null || extra.length() == 0)
                    ? new JSONObject() : new JSONObject(extra);
            if (!jo.optBoolean("isUserSelected", false)) {
                jo.put("isUserSelected", true);
                jo.put("userSelectedTimestamp", System.currentTimeMillis());
                setF(entity, "sgExtraInfo", jo.toString());
            }
        } catch (Throwable t) {
            XposedBridge.log(ModuleInit.TAG + ": dswap stampUserSelected fail " + t);
        }
    }

    /** Builds the card_configs JSON (same shape as the E3 experiment). */
    private static String buildCardConfigsJson(JSONObject cfg) throws org.json.JSONException {
        String name = cfg.optString("name", "");
        JSONObject fci = new JSONObject();
        fci.put("category", cfg.optInt("category", 100));
        fci.put("componentName", cfg.optString("componentName", ""));
        fci.put("desc", cfg.optString("desc", ""));
        fci.put("displayArea", 3);
        fci.put("instantCardUrl", cfg.optString("instantCardUrl", ""));
        fci.put("maxUpdateFrequency", 1);
        fci.put("minActiveUpdateInterval", cfg.optInt("minActiveUpdateInterval", -1));
        fci.put("name", name);
        fci.put("packageName", cfg.optString("packageName", ""));
        fci.put("protocol", cfg.optInt("protocol", 2));
        fci.put("serviceId", cfg.optString("serviceId", ""));
        fci.put("size", cfg.optInt("size", 1));
        fci.put("supportActiveUpdate", false);
        fci.put("supportSuperChannel", false);
        fci.put("type", cfg.optInt("type", 0));
        fci.put("updatePriority", 1);
        JSONArray cardConfigs = new JSONArray();
        JSONObject cc = new JSONObject();
        cc.put("size", cfg.optInt("size", 1));
        cc.put("type", cfg.optInt("type", 0));
        cc.put("full_card_info", fci.toString());
        cardConfigs.put(cc);
        return cardConfigs.toString();
    }

    private static void handleArgs(Object[] args) throws org.json.JSONException {
        if (args == null || args.length == 0 || !(args[0] instanceof java.util.List)) {
            return;
        }
        java.util.List<?> list = (java.util.List<?>) args[0];
        // 这条路径每次决策查询都会走（实测 1-2 次/秒）。原来无条件拼 sid 串
        // 再写两条 logcat，属于常态白耗；现在只有真的改写了才留一条 diag 记录，
        // 早退分支静默——要排查为什么不改写，看 diag.log 里的 mode/feature 即可。
        Context ctx = AppContext.get();
        if (ctx == null) {
            return;
        }
        if (!LayoutStore.enabled(ctx)
                || !LayoutStore.feature(ctx, "dswap", false)) {
            return;
        }
        String mode = LayoutStore.mode(ctx);
        if (!LayoutStore.isCustomMode(mode)) {
            return;
        }
        JSONObject cfg = rightColumnConfig(ctx);
        if (cfg == null) {
            return;
        }
        int n = 0;
        for (Object o : list) {
            if (o != null && STOCK_SID.equals(getStr(o, "serviceId"))) {
                rewriteEntity(o, cfg);
                n++;
            }
        }
        if (n > 0) {
            Diag.window("dswap", "rewrote " + n + " entity(ies) -> "
                    + cfg.optString("serviceId") + " (" + cfg.optString("name") + ")");
        }
    }

    private static void rewriteEntity(Object entity, JSONObject cfg) throws org.json.JSONException {
        String sid = cfg.optString("serviceId", "");
        String name = cfg.optString("name", "");
        setF(entity, "serviceId", sid);
        setF(entity, "name", name);
        setF(entity, "cardType", cfg.optInt("cardType", 100));
        setF(entity, "supportSize", String.valueOf(cfg.optInt("size", 1)));
        setF(entity, "hostPackage", cfg.optString("packageName", ""));
        setF(entity, "url", cfg.optString("url", ""));

        JSONObject fci = new JSONObject();
        fci.put("category", cfg.optInt("category", 100));
        fci.put("componentName", cfg.optString("componentName", ""));
        fci.put("desc", cfg.optString("desc", ""));
        fci.put("displayArea", 3);
        fci.put("instantCardUrl", cfg.optString("instantCardUrl", ""));
        fci.put("maxUpdateFrequency", 1);
        fci.put("minActiveUpdateInterval", cfg.optInt("minActiveUpdateInterval", -1));
        fci.put("name", name);
        fci.put("packageName", cfg.optString("packageName", ""));
        fci.put("protocol", cfg.optInt("protocol", 2));
        fci.put("serviceId", sid);
        fci.put("size", cfg.optInt("size", 1));
        fci.put("supportActiveUpdate", false);
        fci.put("supportSuperChannel", false);
        fci.put("type", cfg.optInt("type", 0));
        fci.put("updatePriority", 1);
        JSONArray cardConfigs = new JSONArray();
        JSONObject cc = new JSONObject();
        cc.put("size", cfg.optInt("size", 1));
        cc.put("type", cfg.optInt("type", 0));
        cc.put("full_card_info", fci.toString());
        cardConfigs.put(cc);
        setF(entity, "cardConfig", cardConfigs.toString());
        setF(entity, "timestamp", System.currentTimeMillis());
    }

    private static String getStr(Object o, String field) {
        Object v = readF(o, field);
        return v == null ? null : String.valueOf(v);
    }

    private static Object readF(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean setF(Object o, String name, Object value) {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(o, value);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * 系统工厂类候选（按 ums 版本混淆名不同）：nv.p = 2026-11 实测版本，
     * mv.t = 本地分析样本版本。签名一致：d(Application,String,int,long,boolean)→String
     * 与 b(String,long,String,SceneResultEntity,boolean)→SceneResultEntity。
     */
    private static final String[] FACTORY_CLASSES = {
            "com.oplus.sdp.nv.p", "com.oplus.sdp.mv.t"
    };

    /**
     * 用系统自己的工厂（d + b）对任意 sid 构造权威实体，再原位覆盖目标实体。
     * 只需要 cfg 里有 serviceId——卡的一切技术字段由系统数据源提供。
     * 成功返回 true；失败（类缺失/查不到卡）返回 false，由调用方走合成 fallback。
     */
    private static boolean applySystemCard(Context ctx, Object target, JSONObject cfg) {
        String sid = cfg.optString("serviceId", "");
        if (sid.length() == 0) {
            return false;
        }
        try {
            // 用目标实体自己的 classloader —— 工厂类与 SceneResultEntity 同属
            // ums 的 dex；ctx.getClassLoader() 在部分进程里解析不到它们。
            ClassLoader cl = target.getClass().getClassLoader();
            Class<?> entityCls = XposedHelpers.findClass(
                    "com.pantanal.server.repository.db.service_govern_db.entity.SceneResultEntity", cl);
            android.app.Application app = (android.app.Application) ctx.getApplicationContext();
            long ts = System.currentTimeMillis();
            Class<?> t = null;
            java.lang.reflect.Method d = null, b = null;
            for (String name : FACTORY_CLASSES) {
                try {
                    Class<?> c = XposedHelpers.findClass(name, cl);
                    java.lang.reflect.Method dd = c.getMethod("d",
                            android.app.Application.class, String.class, int.class,
                            long.class, boolean.class);
                    java.lang.reflect.Method bb = c.getMethod("b",
                            String.class, long.class, String.class, entityCls, boolean.class);
                    t = c;
                    d = dd;
                    b = bb;
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (t == null) {
                XposedBridge.log(ModuleInit.TAG + ": dswap syscard factory class not found");
                return false;
            }
            String fci = (String) d.invoke(null, app, sid, 1, ts, false);
            if (fci == null || fci.length() == 0) {
                XposedBridge.log(ModuleInit.TAG + ": dswap syscard d() empty for " + sid);
                return false;
            }
            // 末位 boolean = isUserSelected 开关：true 时工厂会把
            // isUserSelected/userSelectedTimestamp/settingUrl 写入 sgExtraInfo，
            // 插件长按菜单"编辑当前卡片"以 isUserSelected==true 为前提。
            Object sample = b.invoke(null, fci, ts, "", null, true);
            if (sample == null) {
                return false;
            }
            // round 52：先快照，再覆盖，再回读校验。overwriteFromTemplate 内部
            // 逐字段 catch ignored，被 final/访问控制挡住的字段会被静默跳过，
            // 结果就是"卡名换了、serviceId 还是原来那张"——渲染看似正常，
            // 点进去空白。这里发现不一致就整实体还原，交回合成 fallback。
            java.util.Map<Field, Object> snap = snapshotAll(target);
            overwriteFromTemplate(target, sample);
            String gotSid = getStr(target, "serviceId");
            String gotCfg = getStr(target, "cardConfig");
            if (!sid.equals(gotSid) || gotCfg == null || gotCfg.length() == 0) {
                restoreAll(target, snap);
                Diag.always("dswap", "syscard verify FAIL want="
                        + sid + " got=" + gotSid + " cfgEmpty=" + (gotCfg == null
                        || gotCfg.length() == 0) + " -> rolled back");
                return false;
            }
            stampUserSelected(target); // 双保险：确保 isUserSelected 存在
            // 每次改写都会走到这里，降为 diag（有节流），不再写 logcat。
            Diag.window("dswap", "syscard applied sid=" + sid + " via " + t.getName());
            return true;
        } catch (Throwable tt) {
            Diag.always("dswap", "syscard fail sid=" + sid + " " + tt);
            return false;
        }
    }

    /** 槽位语义字段：覆盖实体时必须保留目标原值（插件按它们分左右栏/决策来源）。 */
    private static final String[] PRESERVE_FIELDS = {
            "sceneId", "sceneCardSleeveType", "sceneCreateTime", "sceneUpdateTime",
            "sceneWeight", "sceneStatus", "sceneScore", "sceneShouldFocus", "isSceneFocus",
            "intentPosition", "policyName", "score", "timestamp", "createTime",
            "homeFlag", "expectSceneCnt", "forceGuaranteed", "combinationStrategy",
            "intentSrc", "capabilityId", "rebootClean", "expireTime", "focusTime",
            "encryptMark", "useTemplate", "action", "domain"
    };

    /** 把样板实体的全部字段值覆盖到目标实体（PRESERVE_FIELDS 保留目标原值）。 */
    private static void overwriteFromTemplate(Object target, Object sample) {
        java.util.Map<String, Object> saved = new java.util.HashMap<String, Object>();
        for (String k : PRESERVE_FIELDS) {
            saved.put(k, readF(target, k));
        }
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    f.set(target, f.get(sample));
                } catch (Throwable ignored) {
                }
            }
        }
        for (String k : PRESERVE_FIELDS) {
            // round 52：原来用 `if (v != null)` 跳过，导致**目标本来就是 null**
            // 的字段保留样板的非 null 值（典型：intentPosition/policyName），
            // 槽位语义被样板污染。现在按"快照里有就写回"，null 也写。
            if (saved.containsKey(k)) {
                setF(target, k, saved.get(k));
            }
        }
    }

    /** Reads tab_0.col_1 raw (all keys, not just Column's four). */
    private static JSONObject rightColumnConfig(Context ctx) {
        return columnConfig(ctx, "col_1");
    }

    /** Reads tab_0.col_0 (left column override; absent = leave slot 0 alone). */
    private static JSONObject leftColumnConfig(Context ctx) {
        return columnConfig(ctx, "col_0");
    }

    private static JSONObject columnConfig(Context ctx, String key) {
        try {
            String raw = LayoutStore.rawLayout(ctx);
            if (TextUtils.isEmpty(raw)) {
                return null;
            }
            JSONObject tab = new JSONObject(raw).optJSONObject("tab_0");
            if (tab == null) {
                return null;
            }
            JSONObject col = tab.optJSONObject(key);
            if (col == null || TextUtils.isEmpty(col.optString("serviceId", ""))) {
                return null;
            }
            return col;
        } catch (Throwable t) {
            return null;
        }
    }
}
