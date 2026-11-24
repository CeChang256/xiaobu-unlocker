package io.github.cechang256.xiaobu;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        // round 55：出口 observable —— 真正交给宿主的 cursor 行序。
                        logCursorRows(param.getResult());
                    } catch (Throwable ignored) {
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
    /** 上一次 pairKey 时是不是 custom：区分"第一次查询"与"真的从别的模式切过来"。 */
    private static boolean sLastPairKeyCustom;
    /** 1.41 B：上一次做过 compose 重排的 pairKey（同一组合只做一次；只在内存里）。 */
    private static String sLastRealignSig;
    /**
     * 上一次在非 custom 模式服务时，原生卡组里出现过的 cardType 集合（1.39 阶段 1）。
     *
     * <p>只用于回答一个判断：切到 custom 之后，如果自定义两张卡的 cardType 与原生
     * 卡组重合，launcher 的 identity 很可能命中缓存 → 扰动白闪一次也不会重建，
     * 此时应当记 `perturb useless` 而不是去扰动。
     */
    private static Set<String> sNativeTypes;

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
        // 1.35：闸门由 mode 驱动（features.dswap 只是可选覆盖）。enabled 仍是总开关；
        // 即使它关掉也求值一次 gate，只为让 diag 能写出"来源"而不是含糊的 off。
        boolean enabled = LayoutStore.enabled(ctx);
        boolean[] viaExplicit = new boolean[1];
        boolean gate = LayoutStore.dswapOn(ctx, viaExplicit);
        boolean on = enabled && gate;
        String mode = LayoutStore.mode(ctx);
        JSONObject cfgR = on ? rightColumnConfig(ctx) : null;
        JSONObject cfgL = on ? leftColumnConfig(ctx) : null;
        // 开关 / mode / 闸门来源 / 左右组合任何一项变化（包括进程第一次看到）都开一个
        // 5 秒窗口：窗口内的桌面刷新会额外写进 LSPosed 日志，用户导出后
        // 就能定位"改完没生效"到底卡在哪一步。
        String pairKey = (on ? "on" : "off") + "|" + mode + "|"
                + (viaExplicit[0] ? "explicit" : "mode") + "|"
                + sidOf(cfgL) + "|" + sidOf(cfgR);
        boolean justOpened = false;
        // "刚切到 custom"：必须之前就见过一个**非 custom** 的 pairKey，否则进程
        // 第一次查询（本来就在 custom）也会被当成切换，凭空扰动一次。
        boolean switchedToCustom = false;
        if (!pairKey.equals(sLastPairKey)) {
            boolean custom = LayoutStore.isCustomMode(mode);
            switchedToCustom = sLastPairKey != null && custom && !sLastPairKeyCustom;
            sLastPairKeyCustom = custom;
            sLastPairKey = pairKey;
            justOpened = true;
            Diag.openWindow(5000);
            Diag.window("dswap", "config -> mode=" + mode
                    + " dswap=" + (enabled ? SwapGate.label(on, viaExplicit[0])
                            : (gate ? "on(layout)" : "off(layout)"))
                    + " L=" + nameOf(cfgL) + "(" + sidOf(cfgL) + ")"
                    + " R=" + nameOf(cfgR) + "(" + sidOf(cfgR) + ")");
        }

        // ---- 1.39：模式扰动 ------------------------------------------------
        // 阶段 1（默认开，零副作用）：只在"刚切到 custom"这一轮判定并记日志，
        //   告诉我们"换成原生卡组再换回来"到底有没有机会换来一次重建。
        // 阶段 2（features.perturb_rebuild，默认关）：窗内一行都不改写，让宿主
        //   原生卡组原样出去；窗到再正常改写 + 通知。layout_json 全程不动，
        //   窗内被杀/断电，下一次查询读到的 until 已过期 → 直接正确态。
        long nowMs = System.currentTimeMillis();
        PerturbGate.State pState = LayoutStore.perturbState(ctx);
        int pPhase = PerturbGate.phase(pState, nowMs);
        if (pPhase == PerturbGate.PHASE_NATIVE) {
            Diag.always("dswap", "perturb serve native, " + (pState.until - nowMs) + "ms left");
            return;
        }
        if (pPhase == PerturbGate.PHASE_RESTORE) {
            LayoutStore.savePerturbState(ctx, PerturbGate.expire(pState));
            Diag.always("dswap", "perturb restore: serve custom");
            SceneNotify.notifyAll(ctx, "perturb-restore", true);
        }
        if (switchedToCustom) {
            String lt = cfgL == null ? "-1" : String.valueOf(cfgL.optInt("type", -1));
            String rt = cfgR == null ? "-1" : String.valueOf(cfgR.optInt("type", -1));
            boolean sameType = sNativeTypes != null
                    && (sNativeTypes.contains(lt) || sNativeTypes.contains(rt));
            if (sameType) {
                // 坑：原生态里已经有同 cardType 的卡 → launcher 很可能命中缓存，
                // 扰动只会白闪一次。这里明着记，不要拿它当"已修复"。
                Diag.always("dswap", "perturb useless: same card types L=" + lt
                        + " R=" + rt + " native=" + sNativeTypes);
            } else if (on && cfgL != null && cfgR != null) {
                Diag.always("dswap", "perturb candidate: would rebuild L="
                        + sidOf(cfgL) + "/" + lt + " R=" + sidOf(cfgR) + "/" + rt
                        + " native=" + (sNativeTypes == null ? "?" : sNativeTypes));
                PerturbGate.Decision pd = LayoutStore.startPerturb(ctx, pairKey);
                if (pd.serveNative) {
                    Diag.always("dswap", "perturb " + pd.reason);
                    SceneNotify.notifyAll(ctx, "perturb-native", true);
                    return;
                }
                Diag.always("dswap", "perturb " + pd.reason);
            }
        }
        // ---- 1.41 B：切到 custom 时按 col_0/col_1 重排 compose 双栏 -------------
        // 用户"系统样式 → 自定义、不点开始编辑直接确定"时，保存路径读到的 compose
        // 可能还是上一轮的旧卡（预览同步没触发/卡池里没有）；这里在切模式这一刻按
        // 配置重排一次，再发一次与「确定」等价的通知，launcher 拿到的就是那对卡。
        // 同一 pairKey 只做一次；只改内存里的 compose，不写 prefs、不碰 layout_json。
        if (switchedToCustom) {
            boolean feat = LayoutStore.feature(ctx, RealignGate.FEATURE, true);
            if (RealignGate.shouldRun(feat, pairKey, sLastRealignSig)) {
                sLastRealignSig = pairKey;
                try {
                    int n = DefaultServiceHook.realignCompose("switch-custom");
                    if (n > 0) {
                        SceneNotify.notifyAll(ctx, "realign", true);
                    }
                } catch (Throwable t) {
                    // 失败只留一行，不打断本轮改写
                    Diag.always("dswap", "compose realign failed: " + t);
                }
            } else {
                Diag.window("dswap", "compose realign "
                        + RealignGate.reason(feat, pairKey, sLastRealignSig));
            }
        }

        // 三个编辑器 tab 互不干扰：mode=custom 时改写成自定义档案；
        // mode=native 时**反向工作**——把可能残留的自定义改写恢复成系统
        // 原生内容。不能简单"罢工"：custom 模式下 guaranteed 槽被改写过，
        // 上游管线缓存/在途列表可能还带着自定义卡，罢工后没人改回去，
        // 表现为"桌面切不回系统样式"、右栏身份与数据错配渲染成
        // "小布建议说明"（实测）。
        if (!on) {
            // 1.39 修复：native 模式走的正是这条 `on=false` 路径（gate 由 mode 驱动），
            // 所以在 return 之前**只读**记一次原生卡组的 cardType。不改写、不 notify、
            // 不动任何数据；只在"刚切换/还没记录"时解析一次，不给每次查询加开销。
            if (PerturbGate.shouldNoteNative(LayoutStore.isCustomMode(mode), justOpened,
                    sNativeTypes != null)) {
                sNativeTypes = typesOf((List<?>) listObj);
            }
            Diag.window("dswap", "skip: dswap off");
            return;
        }
        if (!LayoutStore.isCustomMode(mode)) {
            Diag.window("dswap", "skip: mode=" + mode);
            // 1.39 阶段 1：记下"原生卡组长什么样"，供下一次切到 custom 时判断
            // 自定义两张卡的 cardType 是否与它重合（重合 → 扰动没用）。
            // 同样只在"刚切换/还没记录"时解析（explicit dswap 覆盖时会走到这里）。
            if (PerturbGate.shouldNoteNative(true, justOpened, sNativeTypes != null)) {
                sNativeTypes = typesOf((List<?>) listObj);
            }
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
        // 槽位 → 列：**按两行在 list 里的先后定左右**，不再按行身份硬编码。
        // 依据：宿主 SceneDecisionProvider.setLeftRight（UMS 17.22.8 反编译
        // :919-957）自己就是按下标组列——i==0 → list=[home,2x2] 且
        // home.intentPosition=0；i==1 → list=[2x2,home] 且 home.intentPosition=1，
        // 即 下标 == intentPosition == 视觉列，toCursor(:1048-1061) 按 list 顺序出行。
        // 旧实现（"097 行 = 右栏、210000000 首张非固定卡 = 左栏"）只在宿主把
        // 097 行排在后面时成立；native→custom 切一轮之后宿主把它排到了前面，
        // 于是 col_1 被送到左栏、桌面与预览左右相反（设备实测 2026-11-23）。
        // 按位置分配在旧顺序下与旧逻辑完全等价；在新顺序下自动跟着宿主换列。
        int idxStock = -1;
        int idxHome = -1;
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            if (o == null) {
                continue;
            }
            String sid = getStr(o, "serviceId");
            if (idxStock < 0 && STOCK_SID.equals(sid)) {
                idxStock = i;
                continue;
            }
            if (idxHome < 0 && "210000000".equals(getStr(o, "sceneId"))
                    && !STOCK_SID2.equals(sid)) {
                idxHome = i;
            }
        }
        int stockCol = SceneSlots.stockColumn(idxStock, idxHome);
        // 两个槽位都在时按位置分配；只看得到一行时沿用旧的身份映射
        // （097 → col_1、home → col_0），避免单边列表突然换列。
        JSONObject cfgStock = stockCol == 0 ? cfgL : cfgR;
        JSONObject cfgHome = stockCol == 0 ? cfgR : cfgL;
        if (justOpened) {
            Diag.window("dswap", "scene rows " + describeRows(list)
                    + " -> 097@" + idxStock + " home@" + idxHome
                    + " col(097)=" + (stockCol == SceneSlots.UNKNOWN
                            ? "?" : String.valueOf(stockCol)));
        }
        // round 55：设备证据要求"改写前后各一条完整 rows"。形态（前 4 行的
        // sid/scene/ip/policy/ts）一旦变化才落一条，常态 1.8 条/秒的查询不刷屏。
        noteShape("before", list);
        int nR = 0, nL = 0;
        boolean okStock = idxStock >= 0 && cfgStock != null
                && applySlot(ctx, list.get(idxStock), cfgStock,
                        stockCol == SceneSlots.UNKNOWN ? 1 : stockCol);
        if (okStock) {
            if (cfgStock == cfgL) {
                nL++;
            } else {
                nR++;
            }
        }
        boolean okHome = idxHome >= 0 && cfgHome != null
                && applySlot(ctx, list.get(idxHome), cfgHome,
                        SceneSlots.homeColumn(stockCol));
        if (okHome) {
            if (cfgHome == cfgL) {
                nL++;
            } else {
                nR++;
            }
        }
        if (okStock && okHome) {
            // 1.38：先抓住两行实体，再做定序/注入——normalizePair 可能交换下标，
            // 但实体引用不变，标记两行都要打到。
            Object rowStock = list.get(idxStock);
            Object rowHome = list.get(idxHome);
            normalizePair(list, idxStock, idxHome, stockCol);
            noteShape("after", list);
            injectRebuildFlag(ctx, pairKey, rowStock, rowHome);
        } else {
            // round 56：这条以前完全静默——只看得到 "scene rewrote L1/R1"，
            // 看不出"两行都改了、但定序没摆"。现在缺哪一步都留痕。
            Diag.window("dswap", "normalize skipped: okStock=" + okStock
                    + " okHome=" + okHome + " stockCol=" + stockCol
                    + " idxStock=" + idxStock + " idxHome=" + idxHome);
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
            // round 56：桌面把卡序缓存在 launcher 进程里，改写对了也不会自己
            // 重读（2026-11-23 16:48 强停 launcher 立刻回正 = 铁证）。配置/模式
            // 变了（pairKey 变）或者本进程第一次成功改写时，主动通知它重拉。
            // 只在 pairKey 变化时发，避免"通知→查询→通知"的循环。
            if (!pairKey.equals(sNotifiedPairKey)) {
                sNotifiedPairKey = pairKey;
                SceneNotify.notifyAll(ctx, "rewrite");
            }
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
                        + " R=" + nameOf(cfgR) + "(" + sidOf(cfgR) + ")"
                        + " rowsDetail=" + describeRows(list));
            }
        }
    }

    /**
     * 给一个槽位行套上配置里的卡，并把 intentPosition 校准成它最终所在的列
     * （宿主 invariant：下标 == intentPosition == 视觉列）。
     *
     * @return true = 这次真的改写了这一行
     */
    private static boolean applySlot(Context ctx, Object row, JSONObject cfg, int col)
            throws org.json.JSONException {
        if (!applySystemCard(ctx, row, cfg) && !rewriteSceneEntity(row, cfg)) {
            // 两条路都失败：不改写也不计数。留下原卡总比留下半新半旧
            // 的实体强（后者会污染 launcher 的位置记忆）。
            Diag.always("dswap", "rewrite skipped " + (col == 0 ? "L" : "R")
                    + " sid=" + cfg.optString("serviceId"));
            return false;
        }
        syncColumn(row, col);
        return true;
    }

    /** 把槽位列号写回 intentPosition（字段类型可能是 int/long/short，按现值类型跟随）。 */
    private static void syncColumn(Object row, int col) {
        Object cur = readF(row, "intentPosition");
        Object v;
        if (cur instanceof Long) {
            v = Long.valueOf(col);
        } else if (cur instanceof Short) {
            v = Short.valueOf((short) col);
        } else {
            v = Integer.valueOf(col);
        }
        setF(row, "intentPosition", v);
    }

    // ---- 1.38：让 launcher 重建卡片槽位的注入 -------------------------

    /** 上一次"注入已用尽"的签名，只为把这条日志从每次查询压到一次。 */
    private static String sInjectDoneSig;

    /**
     * 给两行 cardConfig 打 isUserSelected 标记。
     *
     * <p>为什么是这条路：卡片落位（layoutGravity / order）由 launcher 进程在
     * <b>建视图时</b>算一次（childcard/o0.java H() → GroupChildCardWrapper k0），
     * 常规刷新走 na/l.r0 的 "no need to load scene!" 直接返回，所以改写数据再多
     * 也不会重排。而 GCP 的 sa/p.s(ServiceInfo) 为真时，na/h.z1()（由
     * “user unlocked changed to true, restart to load scene” 触发）会
     * setForceRebuild(true) 并整场景重载 → 子卡按当前行序重建 → 左右摆正。
     * sa/p.s() 在 g0.j() 分支读的正是 cardConfig 里的 isUserSelected。
     *
     * <p>闸门（签名 + 次数上限 + 落 prefs）见 LayoutStore.decideInject；这里只管
     * “判定通过就把两行都打上标记并留一条 diag”。
     */
    private static void injectRebuildFlag(Context ctx, String pairKey,
            Object rowStock, Object rowHome) {
        InjectGate.Decision d;
        try {
            d = LayoutStore.decideInject(ctx, pairKey);
        } catch (Throwable t) {
            Diag.always("dswap", "inject decision failed: " + t);
            return;
        }
        if (!d.inject) {
            if (d.reason.startsWith("done") && !d.sig().equals(sInjectDoneSig)) {
                sInjectDoneSig = d.sig();
                Diag.always("dswap", "inject done, stops here: " + d.reason
                        + " (features." + InjectGate.FEATURE + "=false 可立即关掉)");
            }
            return;
        }
        int rows = 0;
        if (stampRow(rowStock, d.state.lastAt)) {
            rows++;
        }
        if (stampRow(rowHome, d.state.lastAt)) {
            rows++;
        }
        Diag.always("dswap", "inject isUserSelected " + d.reason + " count="
                + d.state.count + " rows=" + rows + "/2 sig=" + d.sig());
    }

    /** 单行打标记；cardConfig 坏到重建不出来时保持原值并留痕（绝不抛）。 */
    private static boolean stampRow(Object row, long now) {
        try {
            String sid = getStr(row, "serviceId");
            String cur = getStr(row, "cardConfig");
            int size = 1;
            try {
                String sz = getStr(row, "supportSize");
                if (sz != null && sz.trim().length() > 0) {
                    size = Integer.parseInt(sz.trim());
                }
            } catch (Throwable ignored) {
            }
            String next = CardConfigStamp.stamp(cur, sid, size, now);
            if (next == null) {
                Diag.always("dswap", "inject skipped (cardConfig unrebuildable) sid=" + sid);
                return false;
            }
            setF(row, "cardConfig", next);
            return true;
        } catch (Throwable t) {
            Diag.always("dswap", "inject failed: " + t);
            return false;
        }
    }

    /** 场景列表的行序摘要（只在前 4 行内），给 diag 定位"宿主把哪行排在哪"。 */
    private static String describeRows(List<?> list) {
        StringBuilder sb = new StringBuilder("[");
        int n = Math.min(list.size(), 4);
        for (int i = 0; i < n; i++) {
            Object o = list.get(i);
            if (i > 0) {
                sb.append(' ');
            }
            if (o == null) {
                sb.append(i).append("=null");
                continue;
            }
            sb.append(i).append('=').append(getStr(o, "serviceId"))
                    .append("(scene=").append(getStr(o, "sceneId"))
                    .append(",ip=").append(getStr(o, "intentPosition"))
                    .append(",policy=").append(getStr(o, "policyName"))
                    .append(",ts=").append(getStr(o, "timestamp"))
                    .append(')');
        }
        if (list.size() > n) {
            sb.append(" +").append(list.size() - n);
        }
        return sb.append(']').toString();
    }

    /**
     * 一组行里出现过的 cardType 集合（1.39 阶段 1 用）。
     *
     * <p>cardType 取自行的 cardConfig JSON 的第一段（宿主下发形态：
     * `[{"type":10000492,…}]`）。解析不出来就跳过该行——这里只服务于一条
     * 诊断日志，绝不因为脏数据抛进改写主链路。
     */
    private static Set<String> typesOf(List<?> list) {
        Set<String> out = new LinkedHashSet<>();
        if (list == null) {
            return out;
        }
        int n = Math.min(list.size(), 6);
        for (int i = 0; i < n; i++) {
            Object o = list.get(i);
            if (o == null) {
                continue;
            }
            try {
                String raw = getStr(o, "cardConfig");
                if (TextUtils.isEmpty(raw)) {
                    continue;
                }
                JSONArray arr = new JSONArray(raw);
                for (int k = 0; k < arr.length(); k++) {
                    JSONObject e = arr.optJSONObject(k);
                    if (e != null && e.has("type")) {
                        out.add(String.valueOf(e.optInt("type", -1)));
                    }
                }
            } catch (Throwable ignored) {
                // 一行解析失败不影响其余行
            }
        }
        return out;
    }

    /** 上一次记录的"改写前/改写后"行形态，用于只在形态变化时落一条 diag。 */
    private static String sLastShapeBefore;
    private static String sLastShapeAfter;
    private static String sLastSlots;
    /** 到这一刻之前，即使槽位结果没变也要落一条 "scene slots"（round 56）。 */
    private static volatile long sSlotLogForceUntil;
    /** 上一次因为"配置/模式变化"通知过 launcher 的 pairKey（round 56）。 */
    private static String sNotifiedPairKey;

    /**
     * round 56（task-15 A）：强制下一轮改写把槽位结果写进 diag.log。
     *
     * <p>背景：编辑器点确定之后，launcher 会立刻重新查询一次，那一轮的槽位
     * 结果通常与上一轮**完全相同**，于是被 {@code sLastSlots} 去重吞掉，
     * 设备侧只看得到 "scene rewrote"、看不到 "scene slots"，无法判断
     * normalizePair 到底走没走。改成"确认后必定留一条"，并标注是否与上次相同。
     */
    static void requestSlotLog(String why) {
        sSlotLogForceUntil = System.currentTimeMillis() + 8000L;
        Diag.log("dswap", "slot log forced (" + why + ")");
    }

    private static void noteShape(String tag, List<?> list) {
        String s = describeRows(list);
        if ("before".equals(tag)) {
            if (!s.equals(sLastShapeBefore)) {
                sLastShapeBefore = s;
                Diag.always("dswap", "scene before " + s);
            }
        } else if (!s.equals(sLastShapeAfter)) {
            sLastShapeAfter = s;
            Diag.always("dswap", "scene after " + s);
        }
    }

    /**
     * 两行都改写完之后，把"谁是左、谁是右"在**所有可能被下游读到的定序键**上
     * 摆正一次：列表下标 / intentPosition / policyName / timestamp / createTime。
     *
     * <p>为什么四条一起摆（round 55）：2026-11-23 的设备证据里，正确态与错误态
     * 的列表下标（home@0、097@1）、intentPosition（0/1）完全相同，桌面前后却
     * 相反；而 launcher（17.5.4）里既没有 intentPosition 的读者，也没有
     * policyName 的读者，只有"按 cursor 行序建卡组"这一条路，说明真正定序的
     * 字段是被 {@link #PRESERVE_FIELDS} 原样留下的某一个（policyName 或
     * timestamp/createTime）。只押一条会再错一轮，所以四条一起对齐；对下游
     * 只用其中一条的情况下，其余三条是无害的冗余。
     */
    private static void normalizePair(List<?> list, int idxStock, int idxHome, int stockCol) {
        if (stockCol != 0 && stockCol != 1) {
            return;
        }
        Object stockRow = list.get(idxStock);
        Object homeRow = list.get(idxHome);
        // 槽位语义（宿主自己的词表）：保证卡槽 = DYNAMIC_SUBSTITUTE，
        // 用户自定义槽 = SUBSTITUTE（设备正确态下 home 行就是它）。已有非空
        // policy 一律保留，只补空值，避免覆盖宿主自己的策略标记。
        setF(stockRow, "policyName", SceneOrder.policyFor(getStr(stockRow, "policyName"), true));
        setF(homeRow, "policyName", SceneOrder.policyFor(getStr(homeRow, "policyName"), false));

        Object left = stockCol == 0 ? stockRow : homeRow;
        Object right = stockCol == 0 ? homeRow : stockRow;
        int leftIdx = stockCol == 0 ? idxStock : idxHome;
        int rightIdx = stockCol == 0 ? idxHome : idxStock;
        boolean changed = false;

        changed |= orderLong(left, right, "timestamp");
        changed |= orderLong(left, right, "createTime");

        // 行序：左行放回 index 0。宿主 toCursor 按 list 顺序出行，桌面按行序建卡组；
        // 万一下游确实看下标，这一步让下标与 ip 一致。
        if (leftIdx > rightIdx) {
            java.util.Collections.swap(list, leftIdx, rightIdx);
            changed = true;
        }
        String slots = "scene slots L=" + getStr(left, "serviceId") + "@0"
                + "(policy=" + getStr(left, "policyName") + ",ts=" + getStr(left, "timestamp") + ")"
                + " R=" + getStr(right, "serviceId") + "@1"
                + "(policy=" + getStr(right, "policyName") + ",ts=" + getStr(right, "timestamp") + ")"
                + (changed ? " (order keys realigned)" : "");
        boolean same = slots.equals(sLastSlots);
        boolean force = System.currentTimeMillis() < sSlotLogForceUntil;
        if (!same || force) {
            sLastSlots = slots;
            Diag.always("dswap", slots + (same ? " (unchanged, forced)" : ""));
            if (force) {
                sSlotLogForceUntil = 0L;
            }
        }
    }

    /**
     * 让左行严格早于右行：左旧右新。两行值相同时左行减 1ms（只动毫秒级，
     * 不影响宿主的时效判断）。
     *
     * @return true = 真的写过了
     */
    private static boolean orderLong(Object left, Object right, String field) {
        long tl = getLong(left, field);
        long tr = getLong(right, field);
        if (tl == Long.MIN_VALUE || tr == Long.MIN_VALUE) {
            return false;
        }
        if (!SceneOrder.needsSwap(tl, tr)) {
            return false;
        }
        if (tl == tr) {
            setLongLike(left, field, SceneOrder.leftWhenEqual(tl));
            return true;
        }
        setLongLike(left, field, tr);
        setLongLike(right, field, tl);
        return true;
    }

    /** 读 long 字段；个别 ROM 的实体把它声明成 int/Integer/String。 */
    private static long getLong(Object o, String field) {
        Object v = readF(o, field);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong((String) v);
            } catch (Throwable ignored) {
                return Long.MIN_VALUE;
            }
        }
        return Long.MIN_VALUE;
    }

    /** 按现值类型写回 long（int / short / long / String 都能跟）。 */
    private static void setLongLike(Object o, String field, long value) {
        Object cur = readF(o, field);
        if (cur instanceof Integer) {
            setF(o, field, Integer.valueOf((int) value));
        } else if (cur instanceof Short) {
            setF(o, field, Short.valueOf((short) value));
        } else if (cur instanceof String) {
            setF(o, field, String.valueOf(value));
        } else {
            setF(o, field, Long.valueOf(value));
        }
    }

    /**
     * toCursor 出口处把**真正交出去的 cursor 行序**记下来（round 55 的新增可观测量）。
     * 之前只有"改写前后 rows"，看不到宿主最终拿到的那一份；这条与
     * "scene after" 一起，能区分"改写没落到宿主"和"宿主自己又重排"。
     */
    private static String sLastCursorShape;

    private static void logCursorRows(Object result) {
        if (!(result instanceof android.database.Cursor)) {
            return;
        }
        android.database.Cursor c = (android.database.Cursor) result;
        try {
            int pos = c.getPosition();
            StringBuilder sb = new StringBuilder("[");
            int n = 0;
            int iSid = c.getColumnIndex("serviceId");
            int iIp = c.getColumnIndex("intentPosition");
            int iPol = c.getColumnIndex("policyName");
            int iTs = c.getColumnIndex("timestamp");
            boolean first = true;
            if (c.moveToFirst()) {
                do {
                    if (!first) {
                        sb.append(' ');
                    }
                    first = false;
                    sb.append(n).append('=');
                    sb.append(iSid >= 0 ? c.getString(iSid) : "?");
                    sb.append("(ip=").append(iIp >= 0 ? c.getString(iIp) : "?");
                    sb.append(",policy=").append(iPol >= 0 ? c.getString(iPol) : "?");
                    sb.append(",ts=").append(iTs >= 0 ? c.getString(iTs) : "?");
                    sb.append(')');
                    n++;
                } while (n < 4 && c.moveToNext());
            }
            sb.append(']');
            c.moveToPosition(pos);
            String s = sb.toString();
            if (!s.equals(sLastCursorShape)) {
                sLastCursorShape = s;
                Diag.always("dswap", "cursor out " + s);
            }
        } catch (Throwable ignored) {
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
        // 1.35：与 rewriteSceneList 同一道闸门（mode=custom 即开，features.dswap 可选覆盖）。
        if (!LayoutStore.enabled(ctx) || !LayoutStore.dswapOn(ctx, null)) {
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
     * 系统工厂类候选（按 ums 版本混淆名不同）：17.24.8 起宿主去混淆，
     * 等价类是 com.pantanal.server.decision.util.n（原 com.oplus.sdp.nv.p）；
     * mv.t 只存在于更早的分析样本。签名一致：
     * d(Application,String,int,long,boolean)→String 与
     * b(String,long,String,SceneResultEntity,boolean)→SceneResultEntity。
     */
    private static final String[] FACTORY_CLASSES = HookTargets.SCENE_FACTORY;

    /** 工厂类全缺失的告警只打一次（否则每张卡刷一条 logcat）。 */
    private static volatile boolean sFactoryWarned;

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
                if (!sFactoryWarned) {
                    sFactoryWarned = true;
                    Diag.always("dswap", HookTargets.noClassMessage(
                            "syscard factory", FACTORY_CLASSES));
                }
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
