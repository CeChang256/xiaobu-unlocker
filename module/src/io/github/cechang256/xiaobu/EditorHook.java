package io.github.cechang256.xiaobu;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.cechang256.xiaobu.xp.XC_MethodHook;
import io.github.cechang256.xiaobu.xp.XposedBridge;
import io.github.cechang256.xiaobu.xp.XposedHelpers;

/**
 * 借用系统"编辑默认样式"界面实现双栏自定义（用户方案 v3）。
 *
 * 编辑器里第三个选项（本模块追加的"系统工具+xxx"自定义 tab）才是模块的入口：
 *
 *  - 左右两个系统自带 tab 完全不碰：在它们上面保存 = 系统原生行为，
 *    同时清掉本模块的 col_0/col_1 覆盖（恢复正常显示）。
 *  - 自定义 tab 的预览卡片下方注入一行互斥选项【左栏 | 右栏】（只在这个
 *    tab 显示），下面的工具卡网格完全复用系统自己的。
 *  - 选卡后：
 *      左栏 → 放行系统原生保存（系统自己把卡应用到左栏，与原生完全一致）；
 *      右栏 → 拦截保存：serviceId 写入模块配置 tab_0.col_1（由
 *             SceneServiceRewriter 用系统工厂换到右栏槽位），并吞掉原生
 *             写入，保证左栏不被改动。
 *  - 右栏预览：系统的 updateComposeServiceConfigInfo 硬编码只更新左栏
 *    compose[0]，这里把自定义 tab 的选卡重定向到 compose[1]。
 *
 * 标签用进程内静态变量传递：编辑器与保存调用都在 ums 主进程。
 */
final class EditorHook {

    /** 自定义 tab 里的编辑目标："col_0"= 左栏，"col_1" = 右栏（配合 editActive）。 */
    private static volatile String pendingColumn = null;
    /** 是否已点"编辑"标签进入编辑状态；初始未激活，两侧都只是待命。 */
    private static volatile boolean editActive = false;
    /** 本会话里刚选进右栏的卡（预览重定向成功时记录），供模型层重建 tab 时用。 */
    private static volatile String inFlightRightSid = null;
    /** 最近一次选中的右栏 bean（网格点击的原生 bean，字段完整，跨会话保留）。 */
    private static volatile Object lastRightBean = null;
    /** lastRightBean 对应的 serviceId。 */
    private static volatile String lastRightSid = null;
    /** 最近一次原生保存（= 左栏选择）的 serviceId，用于左右同卡守卫。 */
    private static volatile String lastNativeLeftSid = null;
    /** 模块右栏配置的 serviceId（右栏保存时更新），用于左栏同卡守卫。 */
    private static volatile String lastRightConfigSid = null;
    /** 编辑器当前所在的 tab（viewPager position）；-1 = 未知。 */
    private static volatile int curPage = -1;
    /** 未激活时点网格选卡的引导 toast 限流。 */
    private static long lastPickHintAt;
    /** 最近一次绑定的预览适配器 holder（用于运行时判定左右容器）。 */
    private static volatile Object curHolder;
    /** ums 的 classloader（install 时记录），用于反射读系统的左栏选择。 */
    private static volatile ClassLoader sClassLoader;

    private static boolean installed = false;

    private EditorHook() {
    }

    static void install(ClassLoader cl) {
        if (installed) {
            return;
        }
        installed = true;
        sClassLoader = cl;

        // ---- 1. 注入左右选择条（自定义 tab 专用） ------------------------------
        try {
            Class<?> frag = XposedHelpers.findClass(
                    "com.oplus.ums.groupcard.defaultservice.activity.EditDefaultCardFragment", cl);
            XposedHelpers.findAndHookMethod(frag, "initView", View.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        pendingColumn = null; // 每次进入编辑器重置
                        editActive = false; // 未点"编辑"前两侧都只是待命
                        lastFocusedIdx = NO_SELECTION; // 新会话：池子先无选中框
                        inFlightRightSid = null; // 新会话，清掉上一会话的选卡
                        curPage = -1;
                        initPreviewWritten = false; // 新会话允许一次初始化预览写
                        // 旧编辑器会话的视图可能已销毁分离，残留引用会让
                        // 遮罩/左右判定打到失效视图上（第二次进编辑器
                        // "网格禁点失效"就是这个原因），全部随会话清掉。
                        gridRecycler = null;
                        pickerReady = false; // 新会话：等池子首次绑定
                        curHolder = null;
                        // 重进编辑器时 ViewModel 复用旧实体、apply 不会再跑，
                        // 这里主动把自定义 tab 预览同步成当前桌面配置，
                        // 并让预览适配器立刻重绑，否则显示的还是旧值。
                        boolean synced = DefaultServiceHook.syncPreviewNow();
                        if (synced) {
                            try {
                                Object adapter = XposedHelpers.callMethod(
                                        param.thisObject, "H");
                                if (adapter != null) {
                                    XposedHelpers.callMethod(
                                            adapter, "notifyDataSetChanged");
                                    ModuleInit.log("editor open preview resynced");
                                }
                            } catch (Throwable t) {
                                ModuleInit.log("editor open rebind fail " + t);
                            }
                        }
                        // 后续 apply 若有重建，也允许做一次对齐。
                        DefaultServiceHook.pendingPreviewSync = true;
                        View root = (View) param.args[0];
                        editorRoot = root;
                        columnHintShown = false;
                        updateGridMask(); // 初始：网格遮罩禁点（此时网格可能还没挂载）
                        final Object frag = param.thisObject;
                        editorFragment = frag;
                        // 注意：不要清 ViewModel 的已选字段（d）——那是 Kotlin
                        // 非空字段，置 null 会让系统随后
                        // ensureServiceIdInvalid(context, d) 直接 NPE 崩溃
                        //（实测编辑界面反复崩溃、ums 进程重启）。"进去默认
                        // 什么都不选"由未激活时的点击拦截（b/d/e onClick +
                        // updateComposeServiceConfigInfo 吞调用）保证。
                        // 布局完成后找一轮标题。
                        root.post(new Runnable() {
                            @Override
                            public void run() {
                                updateGridTitle();
                            }
                        });
                    } catch (Throwable t) {
                        ModuleInit.log("editor inject fail " + t);
                    }
                }
            });
            ModuleInit.log("editor initView hooked");
        } catch (Throwable t) {
            ModuleInit.log("editor initView hook fail " + t);
        }

        // ---- 2. 跟踪当前 tab，同步网格区标题 -----------------------------------
        try {
            Class<?> cb = XposedHelpers.findClass(
                    "com.oplus.ums.groupcard.defaultservice.activity.EditDefaultCardFragment"
                            + "$setViewPagerCallback$1", cl);
            XposedHelpers.findAndHookMethod(cb, "onPageSelected", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        curPage = (Integer) param.args[0];
                        int custom = DefaultServiceHook.customTabPos;
                        // 每次切页都对齐遮罩：进自定义 tab 要上锁，切出去
                        // （系统 tab）要解锁——updateGridMask 内部按
                        // "onCustom && !editActive" 判定，不会误锁系统 tab。
                        updateGridMask();
                        if (custom >= 0 && curPage == custom) {
                            // 滑到自定义 tab：恢复标题 + 首次给操作引导。
                            updateGridTitle();
                            if (!columnHintShown) {
                                columnHintShown = true;
                                toast(ctx(), "点击预览的左卡或右卡，选择要编辑的栏");
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            ModuleInit.log("editor page hook fail " + t);
        }

        // ---- 3. 右栏预览重定向 --------------------------------------------------
        try {
            Class<?> list = XposedHelpers.findClass(
                    "com.oplus.ums.groupcard.defaultservice.bean.DefaultServiceListEntity", cl);
            Class<?> bean = XposedHelpers.findClass(
                    "com.oplus.ums.groupcard.defaultservice.bean.ServiceBean", cl);
            XposedBridge.hookAllMethods(list, "updateComposeServiceConfigInfo", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object picked0 = param.args.length > 1 ? param.args[1] : null;
                        String pickedId = picked0 instanceof String ? (String) picked0
                                : picked0 == null ? "null"
                                : safeSid(picked0);
                        ModuleInit.log("editor preview call idx=" + param.args[0]
                                + " picked=" + pickedId
                                + " pending=" + pendingColumn
                                + " custom=" + DefaultServiceHook.customTabPos);
                        if (DefaultServiceHook.customTabPos < 0
                                || param.args.length < 1) {
                            return;
                        }
                        int tabIndex = (Integer) param.args[0];
                        if (tabIndex != DefaultServiceHook.customTabPos) {
                            return;
                        }
                        // thisObject 是 DefaultServiceListEntity（整个列表），
                        // compose/optional 操作都在单 tab 实体上，先解析。
                        Object tab = DefaultServiceHook.tabEntityOf(
                                param.thisObject, tabIndex);
                        if (tab == null) {
                            ModuleInit.log("editor preview tab entity null, skip");
                            return;
                        }
                        // 取所选卡（重载 1: ServiceBean；重载 2: String serviceId）
                        Object picked = param.args.length > 1 ? param.args[1] : null;
                        if (picked instanceof String) {
                            picked = DefaultServiceHook.findCandidate(
                                    tab, (String) picked, -1);
                        }
                        if (picked == null) {
                            ModuleInit.log("editor preview picked=null, skip");
                            return;
                        }
                        Object pickedSidObj = DefaultServiceHook.sidOf(picked);
                        String pickedSid = pickedSidObj == null
                                ? null : String.valueOf(pickedSidObj);
                        // 未激活 = 用户还没选择要编辑哪一栏：一律吞掉系统调用。
                        //   ① 系统初始化会把 ViewModel 预置成"已选桌面左栏卡"，
                        //      吞掉它，进入编辑器默认什么都不选（遮罩生效前的
                        //      误点网格也改不动模型）。
                        //   ② 但初始化调用携带桌面左栏实际卡的完整 bean——
                        //      这正是预览该显示的卡。会话内第一次未激活调用
                        //      把它写进 compose[0] 再吞：预览立即与桌面一致。
                        //      不能依赖 findLeftBean 反查——卡池里部分 bean
                        //      缺 cardConfigInfo，反查失败预览就回退旧卡
                        //     （实测 left bake want=536878390 found=false）。
                        //   ③ 之后的未激活调用是遮罩生效前的网格误点：纯吞。
                        if (!editActive) {
                            if (pickedSid != null) {
                                cacheLeftBean(picked, pickedSid);
                                // col_0 档案存在时 compose[0] 已由 bake 按档案
                                // 同步（initView→getDefaultServiceList），不再用
                                // 初始化调用的原生左栏卡覆盖——系统 tab 保存后
                                // 原生左栏是系统样式，写进来预览就串了。
                                if (!initPreviewWritten && !hasCol0()) {
                                    initPreviewWritten = true;
                                    boolean ok = DefaultServiceHook
                                            .writeComposeColumn(tab, picked, 0);
                                    ModuleInit.log("editor init preview write"
                                            + " ok=" + ok + " sid=" + pickedSid);
                                }
                            }
                            param.setResult(null);
                            ModuleInit.log("editor preview swallowed (inactive) "
                                    + "picked=" + pickedSid);
                            return;
                        }
                        if ("col_1".equals(pendingColumn)) {
                            // 系统逻辑更新 compose[0]（左栏）；右栏时改写
                            // compose[1] 并吞掉原方法，避免左栏预览被误改。
                            // 选择阶段不做同卡拦截：调换左右栏必须经过
                            // "中间态两栏相同"，这里拦死会堵死合法调换。
                            // 只有用户真实点选（r/p 入口打的标记）才写预览
                            // ——数据加载器的高频刷新携带的是原生配置卡，
                            // 会把用户已选的卡悄悄顶掉（实测左栏被 384
                            // 替换、右栏被随机目录卡替换），必须吞掉。
                            if (!userPicking) {
                                param.setResult(null);
                                ModuleInit.log("editor preview swallowed"
                                        + " (loader, col_1) picked=" + pickedSid);
                                return;
                            }
                            boolean ok = DefaultServiceHook.writeComposeColumn(
                                    tab, picked, 1);
                            ModuleInit.log("editor preview redirect ok=" + ok);
                            if (ok) {
                                if (pickedSid != null) {
                                    inFlightRightSid = pickedSid;
                                    // 网格里点选的 bean 字段完整（cardConfigInfo
                                    // 等），缓存下来供烘焙/同步直接复用——可选
                                    // 列表里部分卡的 bean 缺配置，findCandidate
                                    // 反查会失败。注意：这里只更新内存缓存，
                                    // 磁盘持久化放到保存成功时做——否则被预
                                    // 检查拒绝的点选也会落盘，磁盘 bean 与
                                    // 实际配置错位，兜底就失效了。
                                    cacheRightBean(picked, pickedSid);
                                }
                                param.setResult(null);
                            }
                            return;
                        }
                        if ("col_0".equals(pendingColumn)) {
                            if (!userPicking) {
                                // 同右栏：加载器刷新携带原生配置卡，会把用户
                                // 选的左栏卡顶掉（实测 compose[0] 被换成
                                // 384，保存后污染 col_0 档案），吞掉。
                                param.setResult(null);
                                ModuleInit.log("editor preview swallowed"
                                        + " (loader, col_0) picked=" + pickedSid);
                                return;
                            }
                            // 用户点选：用 writeComposeColumn 全量拷贝
                            //（serviceId/name/type/size/cardConfigInfo）。
                            // 不能放行系统原方法——它只拷 3 个字段、漏掉
                            // type/size，而渲染器按 type 分发卡片，结果
                            // 左栏预览永远按旧 type 渲染成原来的卡
                            //（实测选什么卡预览都是"天气"）。
                            boolean ok = DefaultServiceHook.writeComposeColumn(
                                    tab, picked, 0);
                            if (ok) {
                                if (pickedSid != null) {
                                    cacheLeftBean(picked, pickedSid);
                                    ModuleInit.log("editor left bean cached "
                                            + pickedSid);
                                }
                                param.setResult(null);
                            }
                            // ok=false（compose 缺失等）→ 放行原方法兜底
                        }
                    } catch (Throwable t) {
                        ModuleInit.log("editor preview redirect fail " + t);
                    }
                }

                private String safeSid(Object bean) {
                    try {
                        Object sid = DefaultServiceHook.sidOf(bean);
                        return sid == null ? "null" : String.valueOf(sid);
                    } catch (Throwable t) {
                        return "?";
                    }
                }
            });
            ModuleInit.log("editor preview redirect installed");
        } catch (Throwable t) {
            ModuleInit.log("editor preview hook fail " + t);
        }

        // ---- 3.5 预览适配器：tab 标题 + 点预览卡选栏 ---------------------------
        // 系统预览卡区下方有一行 tab 标题（自定义 tab 的标题为空串 → 红框空白）；
        // 左右交互改为直接点击预览的左卡/右卡，选中卡描边高亮。
        try {
            Class<?> hCls = XposedHelpers.findClass(
                    "com.oplus.ums.groupcard.defaultservice.activity.h", cl);
            XposedBridge.hookAllMethods(hCls, "onBindViewHolder", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (DefaultServiceHook.customTabPos < 0
                                || !(param.args[1] instanceof Integer)
                                || (Integer) param.args[1]
                                        != DefaultServiceHook.customTabPos) {
                            return;
                        }
                        Object holder = param.args[0];
                        // 卡片池适配器 c 继承自预览适配器 h，复用同一个
                        // onBindViewHolder；池子的 holder（c$a）没有 f/g 字段。
                        // 不先判一下就取字段会抛 NoSuchFieldError 刷日志
                        //（实测「editor bind hook fail ... c$a#f」）。
                        if (!hasField(holder.getClass(), "f")
                                || !hasField(holder.getClass(), "g")) {
                            return;
                        }
                        // holder 字段（反编译 h$a）：f/g=预览卡容器（左右顺序
                        // 不确定！）、c=tab 标题、d=compose[0]（左栏）ImageView。
                        // 用 d 的祖先链反查哪个容器是左，不硬编码假设。
                        final View f = (View) XposedHelpers.getObjectField(holder, "f");
                        final View gv = (View) XposedHelpers.getObjectField(holder, "g");
                        final View left = isLeftContainer(holder, f, gv) ? f : gv;
                        final View right = (left == f) ? gv : f;
                        curHolder = holder;
                        // 左右判定诊断：leftIsF=false 表示 compose[0] 的视图
                        // 在 holder 的 g 容器里（f/g 顺序与直觉相反的实证）。
                        ModuleInit.log("preview sides: leftIsF=" + (left == f));
                        Object title = XposedHelpers.getObjectField(holder, "c");
                        if (title instanceof TextView) {
                            ((TextView) title).setText("自定义（插件接管）");
                        }
                        // 预览卡本身不可点（必须点卡上的"编辑"标签才进入编辑态）
                        left.setOnClickListener(null);
                        right.setOnClickListener(null);
                        refreshCardsPair(left, right);
                    } catch (Throwable t) {
                        ModuleInit.log("editor bind hook fail " + t);
                    }
                }
            });
            ModuleInit.log("editor bind hook installed");
        } catch (Throwable t) {
            ModuleInit.log("editor bind hook fail " + t);
        }

        // ---- 3.6 findTabIndex 选中判定修正 -------------------------------------
        // 单选圈勾在哪个 tab、进编辑器先落在哪个 tab = findTabIndex(选中卡sid)
        //（EditDefaultCardViewModel loader：x3.e = findTabIndex(x3.d)）。
        // 它扫各 tab 的 optionalServices、最后命中者胜——自定义 tab 挂了全量
        // 卡目录又排在最后，于是任何系统 tab 也有的卡（天气时钟/月历等）
        // 全部命中自定义 tab → 单选圈永远勾在自定义（实测）。
        // 修正为模式感知：
        //   mode=custom → 一律返回自定义 tab（当前生效样式）；
        //   mode=native → 原结果落在自定义 tab、但该卡在更早的系统 tab 也
        //                 有时，优先返回系统 tab（单选圈跟回系统样式）。
        try {
            Class<?> listEntityCls = XposedHelpers.findClass(
                    "com.oplus.ums.groupcard.defaultservice.bean."
                            + "DefaultServiceListEntity", cl);
            XposedBridge.hookAllMethods(listEntityCls, "findTabIndex",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length != 1
                                        || !(param.args[0] instanceof String)) {
                                    return;
                                }
                                int custom = DefaultServiceHook.customTabPos;
                                if (custom < 0) {
                                    return;
                                }
                                Context ctx = AppContext.get();
                                if (ctx == null) {
                                    return;
                                }
                                Object res = param.getResult();
                                int idx = res instanceof Integer
                                        ? (Integer) res : -1;
                                if (LayoutStore.isCustomMode(
                                        LayoutStore.mode(ctx))) {
                                    if (idx != custom) {
                                        param.setResult(custom);
                                        ModuleInit.log("findTabIndex " + idx
                                                + " -> " + custom + " (mode=custom)");
                                    }
                                } else if (idx == custom) {
                                    int earlier = findInSystemTabs(
                                            param.thisObject,
                                            (String) param.args[0], custom);
                                    if (earlier >= 0) {
                                        param.setResult(earlier);
                                        ModuleInit.log("findTabIndex " + custom
                                                + " -> " + earlier
                                                + " (system tab preferred)");
                                    }
                                }
                            } catch (Throwable t) {
                                ModuleInit.log("findTabIndex hook fail " + t);
                            }
                        }
                    });
            ModuleInit.log("editor findTabIndex hook installed");
        } catch (Throwable t) {
            ModuleInit.log("findTabIndex hook fail " + t);
        }

        // ---- 4. 完成按钮预检查：中间态同卡则留在编辑器 -------------------------
        // 必须在"完成"入口（handleSaveMenuItemClick → 静态 o(fragment, menuItem)）
        // 拦，晚了保存协程已经启动、页面已经关闭（实测：先回桌面再弹提示）。
        // 守卫语义：只查编辑器里的中间态（左右预览是否同卡），不与桌面已存
        // 状态比较——对调 = 改两栏预览 + 一次保存。
        try {
            Class<?> frag = XposedHelpers.findClass(
                    "com.oplus.ums.groupcard.defaultservice.activity.EditDefaultCardFragment", cl);
            XposedBridge.hookAllMethods(frag, "o", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        // 只拦签名 o(EditDefaultCardFragment, MenuItem) 的那个。
                        if (param.args.length != 2
                                || !(param.args[1] instanceof android.view.MenuItem)) {
                            return;
                        }
                        int custom = DefaultServiceHook.customTabPos;
                        if (custom < 0 || curPage != custom) {
                            // 系统自带 tab：模式切回 native 要在这里做——
                            // 若放到保存 hook，系统 isDataChanged 判定"没变化"
                            // 跳过协程时 mode 永远切不回去（实测"无法切换回
                            // 系统样式"的根因之一）。col_0/col_1 档案保留。
                            if (custom >= 0) {
                                LayoutStore.setMode(ctx(),
                                        LayoutStore.MODE_NATIVE);
                                // 污染清理：系统将跳过保存（c==d）且原生
                                // 配置里还是自定义保存写入的卡（== col_0 档案）
                                // 时，把它清掉——否则 native 模式下桌面左栏
                                // 一直显示自定义的卡，"系统工具+应用建议"
                                // 无法真正生效（实测）。只在"将跳过 + 确认
                                // 是我们写的"两个条件同时满足时清理，正常
                                // 系统保存（会覆写原生配置）不受影响。
                                try {
                                    Object vmN = XposedHelpers.callMethod(
                                            param.args[0], "I");
                                    Object cN = XposedHelpers.getObjectField(vmN, "c");
                                    Object dN = XposedHelpers.getObjectField(vmN, "d");
                                    if (cN != null && cN.equals(dN)) {
                                        String col0 = col0Sid();
                                        String nat = currentLeftSid();
                                        if (col0 != null && col0.equals(nat)) {
                                            clearNativeSelected();
                                            lastNativeLeftSid = null;
                                            nativeKick(); // metis 重读空配置→系统默认
                                            ModuleInit.log("editor precheck: "
                                                    + "native skip + polluted config"
                                                    + " cleared (was " + nat + ")");
                                        }
                                    }
                                } catch (Throwable t) {
                                    ModuleInit.log("editor precheck cleanup fail " + t);
                                }
                                // 关键：切到系统样式要主动通知 launcher 重拉
                                // ——若系统跳过保存（c==d）则原生没有任何
                                // 刷新动作，桌面会一直停留在上一次自定义
                                // 渲染（实测"桌面切换不生效"）。
                                notifyDecisionRefresh();
                                ModuleInit.log("editor precheck: native tab"
                                        + " -> mode=native");
                            }
                            return; // 系统自带 tab 不拦，原生行为
                        }
                        // 强制 isDataChanged：vm.c=进编辑器时的选中卡、
                        // vm.d=最后一次选的卡。对调两栏后 vm.d 恰好等于
                        // vm.c（最后选的是原左栏卡），系统会判定"没变化"
                        // 直接跳过整个保存（isDataChanged=false not save
                        // data，实测保存 hook 都不会触发）。只要用户在
                        // 自定义 tab 点完成，就强制 c≠d 让协程走到写入
                        //（未激活时预览=自定义档案，正好完成"一键切回"）。
                        Object vm = XposedHelpers.callMethod(param.args[0], "I");
                        Object c0 = XposedHelpers.getObjectField(vm, "c");
                        Object d0 = XposedHelpers.getObjectField(vm, "d");
                        if (c0 != null && c0.equals(d0)) {
                            XposedHelpers.setObjectField(vm, "c", "");
                            ModuleInit.log("editor precheck: force data-changed"
                                    + " (c==d=" + d0 + ")");
                        }
                        Object beanL = DefaultServiceHook.composeBean(0);
                        Object beanR = DefaultServiceHook.composeBean(1);
                        String sidL = beanL == null ? null : String.valueOf(
                                DefaultServiceHook.sidOf(beanL));
                        String sidR = beanR == null ? null : String.valueOf(
                                DefaultServiceHook.sidOf(beanR));
                        if (sidL != null && sidL.equals(sidR)) {
                            param.setResult(null); // 吞掉，不进保存流程，留在编辑器
                            if (editActive) {
                                // 用户正在编辑：明确提示换卡。
                                toast(ctx(), "两栏不能是同一张卡片，"
                                        + "请更换一栏的卡片后再保存");
                                ModuleInit.log("editor precheck rejected: "
                                        + "preview pair both " + sidL);
                            } else {
                                // 未激活时的退化状态（bake 失败两栏兜底成
                                // 同一张）：静默吞掉，不弹提示吓用户。
                                ModuleInit.log("editor precheck noop: "
                                        + "degenerate pair both " + sidL);
                            }
                        }
                    } catch (Throwable t) {
                        ModuleInit.log("editor precheck fail " + t);
                    }
                }
            });
            ModuleInit.log("editor save precheck installed");
        } catch (Throwable t) {
            ModuleInit.log("editor precheck hook fail " + t);
        }

        // ---- 4.2 用户点选标记：r/p 入口 ----------------------------------------
        // r = onItemClickListener（网格点选）、p = onPopupListItemClickListener
        //（弹层点选），两者最终都会调 updateComposeServiceConfigInfo。在这里
        // 打标记，让预览 hook 能区分"用户点选"与"数据加载器刷新"——后者
        // 携带原生配置卡，放行会把用户已选的预览顶掉（实测左栏被 384 悄悄
        // 替换后保存，col_0 档案被污染）。
        try {
            Class<?> fragCls = XposedHelpers.findClass(
                    "com.oplus.ums.groupcard.defaultservice.activity."
                            + "EditDefaultCardFragment", cl);
            XC_MethodHook pickFlag = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    userPicking = true;
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    userPicking = false;
                }
            };
            int flagged = 0;
            for (java.lang.reflect.Method m : fragCls.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 3 && ps[2] != null
                        && ps[2].getName().endsWith("ServiceBean")
                        && ("r".equals(m.getName()) || "p".equals(m.getName()))) {
                    XposedBridge.hookMethod(m, pickFlag);
                    flagged++;
                }
            }
            ModuleInit.log("editor pick flag installed n=" + flagged);
        } catch (Throwable t) {
            ModuleInit.log("editor pick flag fail " + t);
        }

        // ---- 4.5 网格挂载即遮罩（代码层第一时间卡住） --------------------------
        // 工具网格是 COUIRecyclerView（COUI 库子类，覆盖链可能不回调基类的
        // onAttachedToWindow），所以基类和 COUI 子类都 hook。网格一挂到编辑器
        // 窗口（首帧绘制之前）就立即上遮罩，用户看到的网格从一开始就是灰的。
        // 真正的禁点在 4.6 的适配器绑定层做（与视图类型/挂载时机无关）。
        try {
            String[] rvClasses = {
                    "androidx.recyclerview.widget.RecyclerView",
                    "androidx.recyclerview.widget.COUIRecyclerView",
            };
            int rvHooked = 0;
            for (int i = 0; i < rvClasses.length; i++) {
                try {
                    Class<?> rvCls = XposedHelpers.findClass(rvClasses[i], cl);
                    XposedBridge.hookAllMethods(rvCls, "onAttachedToWindow",
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        if (editorRoot == null
                                                || !(param.thisObject instanceof ViewGroup)) {
                                            return;
                                        }
                                        ViewGroup rv = (ViewGroup) param.thisObject;
                                        // hookAllMethods 会沿继承链一路挂到
                                        // View#onAttachedToWindow，于是任何子 View
                                        // 都会进来（日志里那些 FrameLayout/
                                        // LinearLayout 就是这么来的）。gridRecycler
                                        // 必须是真正的 RecyclerView，否则后面
                                        // findViewHolderForAdapterPosition 会抛异常
                                        // 被吞掉，标题永远不刷新。
                                        if (!isRecyclerView(rv)) {
                                            return;
                                        }
                                        if (rv != editorRoot
                                                && !isDescendantOf(rv, editorRoot)) {
                                            return; // 不是编辑器窗口里的
                                        }
                                        if (insideViewPager2(rv)) {
                                            return; // 预览区的，放过
                                        }
                                        if (gridRecycler != rv) {
                                            ModuleInit.log("grid recycler attached: "
                                                    + rv.getClass().getName());
                                        }
                                        gridRecycler = rv;
                                        updateGridMask(); // 挂载瞬间上遮罩
                                    } catch (Throwable ignored) {
                                    }
                                }
                            });
                    rvHooked++;
                } catch (Throwable ignored) {
                }
            }
            ModuleInit.log("editor grid attach hook installed n=" + rvHooked);
        } catch (Throwable t) {
            ModuleInit.log("editor grid attach hook fail " + t);
        }

        // ---- 4.55 卡片池分组标题 ------------------------------------------------
        // 反编译实证：池子第 0 项是标题行（adapter.c 的 ViewHolder b，字段 a 是
        // TextView），文字由 onBindViewHolder 按 entity.getIndex() 写死成
        // 2131954390/91/92 三个资源之一。它整行在 RecyclerView 内部，所以按视图树
        // 搜索（跳过 RecyclerView 子树）永远找不到——之前 updateGridTitle 实际
        // 一次都没生效。这里直接在绑定完成后改写，任何重绑都会得到同一文案。
        try {
            Class<?> poolAdapter = XposedHelpers.findClass(
                    "com.oplus.ums.groupcard.defaultservice.activity.c", cl);
            sPoolAdapter = poolAdapter;
            XposedBridge.hookAllMethods(poolAdapter, "onBindViewHolder",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length != 2) {
                                    return;
                                }
                                // 顺手定位池子：复用的 holder 一定已经挂在
                                // RecyclerView 上，沿 itemView 父链即可拿到。
                                // （setAdapter / onAttachedToRecyclerView /
                                //  onViewAttachedToWindow 三个信号在 COUIRecyclerView
                                //  上实测都不回调，只有这条是可靠的。）
                                if (gridRecycler == null) {
                                    Object iv0 = XposedHelpers.getObjectField(
                                            param.args[0], "itemView");
                                    if (iv0 instanceof View) {
                                        ViewGroup anc = recyclerAncestor((View) iv0);
                                        if (anc != null) {
                                            gridRecycler = anc;
                                            ModuleInit.log("grid bound via item parent: "
                                                    + anc.getClass().getSimpleName());
                                        }
                                    }
                                }
                                if (!(param.args[1] instanceof Integer)
                                        || ((Integer) param.args[1]) != 0) {
                                    return;
                                }
                                // 第一次绑出标题行 = adapter 与数据都已就绪。
                                // 原来靠 postDelayed(300ms) 猜这个时机，现在
                                // 用绑定本身作为信号。
                                if (!pickerReady) {
                                    pickerReady = true;
                                    if (editActive) {
                                        focusPickerOn(pendingColumn != null
                                                ? pendingColumn : "col_0");
                                    } else {
                                        clearPickerSelection();
                                    }
                                }
                                if (DefaultServiceHook.customTabPos < 0
                                        || curPage != DefaultServiceHook.customTabPos) {
                                    return; // 系统自带 tab：保持原生文案
                                }
                                Object tv = XposedHelpers.getObjectField(
                                        param.args[0], "a");
                                if (tv instanceof TextView) {
                                    // 记下来：激活后 applyPickerTitle 直接改这个
                                    // TextView，不必依赖 findViewHolderForAdapterPosition
                                    // （那条路在 COUIRecyclerView 上并不可靠）。
                                    pickerTitleView = (TextView) tv;
                                    pickerTitleView.setText(pickerTitleText());
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            ModuleInit.log("picker title hook installed");
        } catch (Throwable t) {
            ModuleInit.log("picker title hook fail " + t);
        }

        // ---- 4.6 网格条目点击拦截（类型层根修） --------------------------------
        // 反编译实证：工具条目的点击监听是三个独立类——b=条目本体（读 tag
        // 索引 → 回调进 fragment 触发选卡），d/e=条目内子按钮。点击实际落在
        // 条目的子 view 上，在 itemView 上覆盖监听拦不住。直接 hook 这三个
        // onClick：未激活一律吞掉（无视觉变化、无模型变化），与视图类型/
        // 挂载时机完全无关。
        try {
            String[] clickClasses = {
                    "com.oplus.ums.groupcard.defaultservice.activity.b",
                    "com.oplus.ums.groupcard.defaultservice.activity.d",
                    "com.oplus.ums.groupcard.defaultservice.activity.e",
            };
            int clickHooked = 0;
            for (int i = 0; i < clickClasses.length; i++) {
                try {
                    XposedHelpers.findAndHookMethod(clickClasses[i], cl, "onClick",
                            View.class, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) {
                                    if (editorRoot == null || editActive) {
                                        return;
                                    }
                                    // 只锁自定义 tab 的网格（"先开始编辑"流程）。
                                    // 系统 tab 的工具选择是原生行为，绝不锁
                                    //（实测系统 tab 被误锁，点工具卡弹提示）。
                                    if (DefaultServiceHook.customTabPos < 0
                                            || curPage
                                                    != DefaultServiceHook.customTabPos) {
                                        return;
                                    }
                                    param.setResult(null);
                                    long now = android.os.SystemClock.uptimeMillis();
                                    if (now - lastPickHintAt > 3000) {
                                        lastPickHintAt = now;
                                        toast(ctx(), "请先点击预览卡上的「开始编辑」");
                                    }
                                    ModuleInit.log("grid item click blocked (inactive)");
                                }
                            });
                    clickHooked++;
                } catch (Throwable ignored) {
                }
            }
            ModuleInit.log("grid click hooks installed n=" + clickHooked);
        } catch (Throwable t) {
            ModuleInit.log("grid click hook fail " + t);
        }

        // ---- 5. 保存分流（双保险：协程里的实际写入处） -------------------------
        try {
            Class<?> appSetting = XposedHelpers.findClass(
                    "com.oplus.ums.common.utils.AppSettingUtilsKt", cl);
            int hooked = 0;
            for (Method m : appSetting.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 3 && ps[0] == ContentResolver.class
                        && ps[1] == String.class && ps[2] == String.class) {
                    sNativeWrite = m; // 污染清理时绕过自身 hook 直写要用
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String key = (String) param.args[1];
                                if (!"group_card_user_selected_config".equals(key)) {
                                    return;
                                }
                                int page = curPage;
                                int custom = DefaultServiceHook.customTabPos;
                                String json = (String) param.args[2];
                                String pickedSid = optServiceId(json);
                                if (page < 0) {
                                    // fail-safe：onPageSelected hook 失效
                                    // （混淆类名变更）或未触发时 curPage 未知。
                                    // 此时宁可不动模块配置也不能冒险——若用户
                                    // 实际在自定义 tab，走下面的 clear 分支会把
                                    // col_1 静默清掉。只放行原生写入并记录左栏。
                                    ModuleInit.log("editor save: page unknown"
                                            + " (page hook dead?), pass through"
                                            + ", no clear");
                                    if (pickedSid != null) {
                                        lastNativeLeftSid = pickedSid;
                                    }
                                    sNativePassThrough = true;
                                    return;
                                }
                                if (custom < 0 || page != custom) {
                                    // 系统自带 tab 上保存 = 用户选择了系统样式：
                                    // 完全放行原生行为（系统 tab 的保存此前被
                                    // clearColumns(true) 连带删掉自定义档案、
                                    // 被误吞导致"无法切换回系统样式"）。
                                    // 只把模式置回 native——桌面改写让路给系统，
                                    // col_0/col_1 档案保留，切回自定义 tab 保存
                                    // 即恢复，三个 tab 互不干扰。
                                    if (pickedSid != null) {
                                        lastNativeLeftSid = pickedSid;
                                    }
                                    LayoutStore.setMode(ctx(),
                                            LayoutStore.MODE_NATIVE);
                                    // 原生写入的内部通知可能不达 launcher
                                    //（对调实测），补发决策刷新兜底。
                                    notifyDecisionRefresh();
                                    sNativePassThrough = true; // 写完后 afterHooked 补油门
                                    ModuleInit.log("editor save: native tab"
                                            + " -> mode=native (col archive kept)");
                                    return;
                                }
                                // ---- 自定义 tab 保存：编辑器状态两栏一起写 ----
                                // 用户语义：守卫只查编辑器里的"中间态"（左右
                                // 预览是否同卡），不与桌面已存状态比较。对调
                                // 两栏 = 先后改两栏的预览 + 一次保存，无需任何
                                // 对调特判。保存 = 左预览→col_0 + 原生左栏配置，
                                // 右预览→col_1，一次写齐，并置 mode=custom。
                                // 注意：不再要求 editActive——用户从系统样式
                                // 切回自定义 tab 直接点完成，预览就是自定义
                                // 档案（bake 自 col_0/col_1），按预览对保存即
                                // 完成"一键切回"。预览 bean 缺失时自然 noop。
                                Object beanL = DefaultServiceHook.composeBean(0);
                                Object beanR = DefaultServiceHook.composeBean(1);
                                String sidL = beanL == null ? null
                                        : String.valueOf(DefaultServiceHook.sidOf(beanL));
                                String sidR = beanR == null ? null
                                        : String.valueOf(DefaultServiceHook.sidOf(beanR));
                                if (sidL == null && sidR == null) {
                                    param.setResult(null);
                                    ModuleInit.log("editor save noop: no preview beans");
                                    return;
                                }
                                // 中间态守卫：左右预览同卡 → 拒绝，留在编辑器
                                //（桌面两栏同卡会触发插件丢弃组合，必须拦）。
                                if (sidL != null && sidL.equals(sidR)) {
                                    param.setResult(null);
                                    toast(ctx(), "两栏不能是同一张卡片，"
                                            + "请更换一栏的卡片后再保存");
                                    ModuleInit.log("editor save rejected: "
                                            + "preview pair both " + sidL);
                                    return; // pendingColumn 保留，继续编辑
                                }
                                // 左栏：**替换 h() 入参 json + 放行系统写入**。
                                // round 26 实测（21:48 对调）：放行 h() 后系统
                                // 写入的是 390（过期值，=旧配置，等于没变）→
                                // 管线无变化不触发渲染；纠偏通道 putString
                                // 虽落库+launcher 重拉，但桌面仍不重绑。
                                // 全部案例收敛后唯一触发过桌面渲染的通道 =
                                // 系统保存协程里 h() 写入一个**不同的值**
                                //（19:50：390→193 成功渲染；putString 值→值、
                                // dance 反射 invoke、h() 写相同值，全部不渲染）。
                                // 故 round 27：不改流程、不吞不放行原 json——
                                // 直接把 h() 的 json 入参替换成左栏卡的 json，
                                // 让系统自己的代码路径写入正确且必变的值。
                                cacheLeftBean(beanL, sidL);
                                persistLeftBean(beanL);
                                String leftJson = buildNativeSelectedJson(beanL);
                                if (leftJson != null) {
                                    param.args[2] = leftJson;
                                    ModuleInit.log("editor save: native json"
                                            + " replaced <- " + sidL);
                                } else {
                                    ModuleInit.log("editor save: left json"
                                            + " build fail, system json passes");
                                }
                                sCustomPassSid = sidL;
                                // 右栏：右预览 bean → col_1（右栏档案），保存
                                // 即写 + **立即通知**。round 21 实证有效的时序：
                                // launcher 在管线重建打乱列表顺序之前拿到正确
                                // 的左右映射。round 22 曾为"两栏同步换装"把
                                // 这步挪进 300ms 延迟拍，结果清空触发的重建
                                // 先把应用建议卡排到列表第一位，launcher 按
                                // 位置绑定渲染出反转数据 → 对调失效（实测）。
                                // 用户定稿：放弃同步换装，回到本时序。
                                // round 52：左右两栏档案**合成一次原子写**。
                                // 原来 col_1、col_0 各发一次 provider 调用，每次
                                // 都是"读整串 JSON → 改 → 写回"；两次之间若插进
                                // 往返切 mode 的写，后写的会把前一列整段盖掉
                                //（丢列，且两次调用都成功、日志看不出来）。
                                if (sidL != null || sidR != null) {
                                    LayoutStore.setColumns(ctx(), "tab_0",
                                            sidL,
                                            beanInt(beanL, "getType", -1),
                                            beanInt(beanL, "getSize", 1),
                                            beanStr(beanL, "getName", ""),
                                            sidR,
                                            beanInt(beanR, "getType", -1),
                                            beanInt(beanR, "getSize", 1),
                                            beanStr(beanR, "getName", ""),
                                            null);
                                }
                                if (sidR != null) {
                                    lastRightConfigSid = sidR;
                                    cacheRightBean(beanR, sidR);
                                    persistRightBean(beanR);
                                }
                                // round 40：**照搬系统切 tab 的做法做"模式往返"**，
                                // 不再插入任何中转卡。系统切到"系统工具+应用建议"
                                // 时并没有加卡——它只是把 mode 变成 native、模块
                                // 停止改写，于是系统卡上台、自定义的两张卡自然
                                // 离场；切回自定义时两张卡重新入场，launcher 按
                                // 新顺序重排。纯对调时自动走一遍这个往返：
                                //   ① mode=native + 通知（系统内容上台，约 700ms）
                                //   ② 数据查询链路自动切回 mode=custom + 通知
                                //     （两张卡按新档案重新入场 → 位置重排）
                                boolean roundTrip = SceneServiceRewriter
                                        .isPureSwap(sidL, sidR);
                                // 判定依据一次性打全：只写"swap detected"没法解释
                                // 为什么没触发往返，把三方数据摆出来才可诊断。
                                ModuleInit.log("editor save: swap check new=(" + sidL
                                        + "," + sidR + ") served=("
                                        + SceneServiceRewriter.sServedL + ","
                                        + SceneServiceRewriter.sServedR + ") -> "
                                        + (roundTrip ? "PURE SWAP" : "not a swap"));
                                if (roundTrip) {
                                    SceneServiceRewriter.sRoundNotify =
                                            new Runnable() {
                                                @Override
                                                public void run() {
                                                    nativeKick();
                                                    notifyDecisionRefresh();
                                                    ModuleInit.log("editor save:"
                                                            + " roundtrip -> custom"
                                                            + " notified");
                                                }
                                            };
                                    // round 52：mode=native **且** 写入往返时间戳，
                                    // 两件事同一次读改写落盘。时间戳让"中间态"
                                    // 可判定：读侧看到未过期标记按 native 处理，
                                    // 看到过期标记按 custom 处理，没有标记则是
                                    // 用户选择——所以进程中途被杀也能自愈，
                                    // 而"切回系统样式"的合法 native 不会被误伤。
                                    boolean marked = LayoutStore
                                            .beginRoundTripMark(ctx());
                                    SceneServiceRewriter.beginRoundTrip();
                                    ModuleInit.log("editor save: swap detected,"
                                            + " roundtrip native phase"
                                            + (marked ? "" : " (MARK FAILED)"));
                                    // 兜底：万一数据链路没推进，2.2s 后强制切回
                                    // 自定义并放行关页（否则用户会卡在编辑器）。
                                    new android.os.Handler(
                                            android.os.Looper.getMainLooper())
                                            .postDelayed(new Runnable() {
                                                @Override
                                                public void run() {
                                                    try {
                                                        // 强制收尾：mode=custom +
                                                        // 删标记，一次原子写。
                                                        boolean ok = LayoutStore
                                                                .endRoundTripMark(ctx());
                                                        if (ok) {
                                                            nativeKick();
                                                            notifyDecisionRefresh();
                                                            ModuleInit.log("editor"
                                                                    + " save: roundtrip"
                                                                    + " fallback custom");
                                                        } else {
                                                            ModuleInit.log("editor"
                                                                    + " save: roundtrip"
                                                                    + " fallback write failed");
                                                        }
                                                    } catch (Throwable t) {
                                                        ModuleInit.log("editor"
                                                                + " roundtrip fallback"
                                                                + " fail " + t);
                                                    }
                                                }
                                            }, 2200L);
                                } else {
                                    LayoutStore.setMode(ctx(),
                                            LayoutStore.MODE_CUSTOM);
                                }
                                notifyDecisionRefresh();
                                nativeKick();
                                ModuleInit.log("editor save: mode=custom applied");
                                // **放行**系统自己的 h()（不 setResult）：
                                // 由它写入原生配置并触发 ums 进程内重建管线
                                //（见上方 round 26 注释）。写完后的校验/纠偏/
                                // 油门在 afterHookedMethod 里做（那时写入才
                                // 真正发生）。
                                sCustomPass = true;
                                ModuleInit.log("editor save pair: left=" + sidL
                                        + " right=" + sidR
                                        + " (system h passthrough)");
                                Diag.log("editor", "pair saved from editor");
                                // 延迟补拍：launcher 的观察者/渲染管线可能晚于
                                // 通知就绪，1.5s 后再补一轮双油门覆盖竞态。
                                new android.os.Handler(android.os.Looper.getMainLooper())
                                        .postDelayed(new Runnable() {
                                            @Override
                                            public void run() {
                                                nativeKick();
                                                notifyDecisionRefresh();
                                                ModuleInit.log(
                                                        "editor save delayed kick done");
                                            }
                                        }, 1500L);
                                pendingColumn = null;
                            } catch (Throwable t) {
                                // 异常时同样保留 pendingColumn：badge 仍显示
                                // "编辑中"，状态保持与界面一致，用户可直接重试。
                                ModuleInit.log("editor save hook fail " + t);
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // 放行路径（系统 tab 保存 / page 未知 / 自定义
                            // tab 放行）的原生写入在 beforeHooked 返回后才
                            // 真正发生，油门/校验必须补在写入之后——这里
                            // 就是那个时机。
                            if (sNativePassThrough) {
                                sNativePassThrough = false;
                                nativeKick();
                            }
                            if (sCustomPass) {
                                sCustomPass = false;
                                // 自定义 tab 放行后的读回校验：h() 应已把替换
                                // 后的左栏 json 写进 secure 表。一致 → 完事
                                //（log "verified"）。不一致（替换失败走了系统
                                // 原 json）→ 用系统 h() 原方法纠偏直写左栏
                                // json（round 27：纠偏也走 h() 通道，putString
                                // 已实证不触发渲染）。
                                String want = sCustomPassSid;
                                sCustomPassSid = null;
                                try {
                                    Context c = AppContext.get();
                                    // 读回必须走 **OPlus AppSettings 私有
                                    // provider**（content://
                                    // com.oplus.appplatform.settings/secure，
                                    // 系统 h()=putSecureString 的真实写入目标，
                                    // ums 模型 getter 也从这里读）。之前用
                                    // Settings.Secure 读的是标准 settings 表
                                    // ——两库并存且不同步，导致 round 27 大量
                                    // 假 "corrected" 警报（实际已写对）。
                                    String cur = null;
                                    if (c != null) {
                                        android.database.Cursor cur0 = c
                                                .getContentResolver()
                                                .query(android.net.Uri.parse(
                                                        "content://com.oplus"
                                                        + ".appplatform.settings"
                                                        + "/secure"), null,
                                                        "name=?",
                                                        new String[]{
                                                        "group_card_user_"
                                                        + "selected_config"},
                                                        null);
                                        if (cur0 != null) {
                                            try {
                                                int vi = cur0
                                                        .getColumnIndex("value");
                                                if (cur0.moveToFirst() && vi >= 0) {
                                                    cur = cur0.getString(vi);
                                                }
                                            } finally {
                                                cur0.close();
                                            }
                                        }
                                    }
                                    String got = optServiceId(cur);
                                    if (want != null && !want.equals(got)) {
                                        Object bean = DefaultServiceHook
                                                .composeBean(0);
                                        String leftJson = bean == null ? null
                                                : buildNativeSelectedJson(bean);
                                        if (leftJson != null && sNativeWrite != null) {
                                            XposedBridge.invokeOriginalMethod(
                                                    sNativeWrite, null,
                                                    new Object[]{param.args[0],
                                                            param.args[1], leftJson});
                                            ModuleInit.log("editor save: native"
                                                    + " config corrected(invoke) "
                                                    + got + " -> " + want);
                                        }
                                    } else {
                                        ModuleInit.log("editor save: native"
                                                + " config verified " + got);
                                    }
                                } catch (Throwable t) {
                                    ModuleInit.log("editor save verify fail " + t);
                                }
                                lastNativeLeftSid = want;
                                nativeKick();
                                notifyDecisionRefresh();
                                ModuleInit.log("editor save kick done (custom)");
                            }
                        }
                    });
                    hooked++;
                }
            }
            ModuleInit.log("editor save hooks installed n=" + hooked);
        } catch (Throwable t) {
            ModuleInit.log("editor save hook fail " + t);
        }
    }

    /** 编辑器根视图（initView 时记录），用于实时查找网格标题。 */
    private static View editorRoot;
    /** 自定义 tab 放行系统 h() 后的读回校验标记（round 26）。 */
    private static boolean sCustomPass;
    /** 校验目标：左栏档案 sid（放行前记录，afterHooked 里比对）。 */
    private static String sCustomPassSid;
    /** 每次编辑器会话只提示一次操作引导。 */
    private static boolean columnHintShown;

    /**
     * 用户正在点选卡（fragment 的 r=onItemClickListener / p=onPopupListItemClick
     * 入口打的标记）。数据加载器也会调用 updateComposeServiceConfigInfo，
     * 携带的是原生配置卡——用它区分"用户点选"（放行/写预览）和"加载器
     * 刷新"（吞掉，保护用户已选的预览）。
     */
    private static volatile boolean userPicking;

    /** 卡片池第 0 行的标题 TextView（绑定层写入时记下，激活后直接改它）。 */
    private static volatile TextView pickerTitleView;

    /** 卡片池标题文案：未编辑时给提示，编辑中显示「左栏 · 天气时钟」。 */
    private static String pickerTitleText() {
        if (!editActive) {
            return "请点击「开始编辑」";
        }
        boolean right = "col_1".equals(pendingColumn);
        Object bean = DefaultServiceHook.composeBean(right ? 1 : 0);
        String name = bean == null ? null : DefaultServiceHook.nameOf(bean);
        String side = right ? "右栏" : "左栏";
        return (name == null || name.length() == 0) ? side + " 卡片" : side + " · " + name;
    }

    /**
     * 把标题写到池子第 0 行。第 0 行不在屏幕上时让它重绑，
     * 由 4.55 的 onBindViewHolder hook 写入同一文案。
     */
    private static void applyPickerTitle() {
        final String text = pickerTitleText();
        // 首选：绑定层记下的那个 TextView（一定就是第 0 行的标题）。
        final TextView captured = pickerTitleView;
        if (captured != null && captured.isAttachedToWindow()) {
            captured.setText(text);
            return;
        }
        final ViewGroup rv = gridRecycler;
        if (rv == null) {
            return;
        }
        rv.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Object vh = XposedHelpers.callMethod(rv,
                            "findViewHolderForAdapterPosition", 0);
                    if (vh != null) {
                        Object tv = XposedHelpers.getObjectField(vh, "a");
                        if (tv instanceof TextView) {
                            ((TextView) tv).setText(text);
                            return;
                        }
                    }
                    Object ad = XposedHelpers.callMethod(rv, "getAdapter");
                    if (ad != null) {
                        XposedHelpers.callMethod(ad, "notifyItemChanged", 0);
                    }
                } catch (Throwable t) {
                    ModuleInit.log("picker title fail " + t);
                }
            }
        });
    }

    /** 卡片池标题跟随编辑状态：未激活提示选栏，激活后显示当前栏与所在卡。 */
    private static void updateGridTitle() {
        applyPickerTitle();
    }

    /** 工具卡网格（未激活时加遮罩禁点，激活后恢复）。 */
    private static ViewGroup gridRecycler;
    /** 卡片池 adapter 类（activity.c），用于判定"哪个 RecyclerView 是池子"。 */
    private static Class<?> sPoolAdapter;
    /** 池子是否已首次绑定（第一次绑出标题行 = adapter 与数据都就绪）。 */
    private static volatile boolean pickerReady;
    /** 会话内是否已做过"初始化预览写"（见 preview hook 未激活分支）。 */
    private static volatile boolean initPreviewWritten = false;

    /** 未激活时拦截网格触摸并提示先开始编辑。 */
    private static final View.OnTouchListener GRID_TOUCH_BLOCK =
            new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, android.view.MotionEvent event) {
                    if (editActive) {
                        return false;
                    }
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastPickHintAt > 3000) {
                        lastPickHintAt = now;
                        toast(ctx(), "请先点击预览卡上的「开始编辑」");
                    }
                    return true; // 消费事件，网格不可点
                }
            };

    /** 找工具卡网格的 RecyclerView（跳过 ViewPager2 预览子树）。 */
    private static ViewGroup findGridRecycler(View root) {
        try {
            java.util.ArrayDeque<View> queue = new java.util.ArrayDeque<View>();
            queue.add(root);
            while (!queue.isEmpty()) {
                View c = queue.poll();
                String cn = c.getClass().getName();
                if (cn.contains("ViewPager2")) {
                    continue; // 预览在 ViewPager2 内，跳过其子树
                }
                if (cn.contains("RecyclerView") && c instanceof ViewGroup) {
                    // 判据是"这个 RecyclerView 的 adapter 是不是 activity.c"，
                    // 而不是"视图树里第一个 RecyclerView"——编辑器里不止一个
                    // 列表，靠第一个命中是猜测。命中后不再深入它的子树。
                    if (isPoolRecycler(c)) {
                        ModuleInit.log("grid found by adapter match: " + cn);
                        return (ViewGroup) c;
                    }
                    continue;
                }
                if (c instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) c;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        queue.add(g.getChildAt(i));
                    }
                }
            }
        } catch (Throwable t) {
            ModuleInit.log("find grid recycler fail " + t);
        }
        return null;
    }

    /** 该 RecyclerView 的 adapter 是不是卡片池的（activity.c）。 */
    private static boolean isPoolRecycler(View v) {
        Class<?> pa = sPoolAdapter;
        if (pa == null) {
            return true; // 类拿不到时退回旧行为：认第一个
        }
        try {
            Object ad = XposedHelpers.callMethod(v, "getAdapter");
            return ad != null && pa.isInstance(ad);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 沿父链找最近的 RecyclerView 祖先（COUIRecyclerView 这类子类都算）。 */
    private static ViewGroup recyclerAncestor(View v) {
        ViewParent p = v.getParent();
        while (p instanceof View) {
            if (p instanceof ViewGroup
                    && p.getClass().getName().contains("RecyclerView")) {
                return (ViewGroup) p;
            }
            p = p.getParent();
        }
        return null;
    }

    /** v 是否在 root 的子树里。 */
    private static boolean isDescendantOf(View v, View root) {
        ViewParent p = v.getParent();
        while (p instanceof View) {
            if (p == root) {
                return true;
            }
            p = p.getParent();
        }
        return false;
    }

    /** v 是否位于 ViewPager2 的子树里（预览区判定）。 */
    private static boolean insideViewPager2(View v) {
        ViewParent p = v.getParent();
        while (p instanceof View) {
            if (p.getClass().getName().contains("ViewPager2")) {
                return true;
            }
            p = p.getParent();
        }
        return false;
    }

    /** 按编辑状态切换网格遮罩：未激活半透明+禁点，激活恢复。 */
    /**
     * 把下面卡片池的选中框对到某一栏当前的卡，并滚动到可见位置。
     *
     * <p>系统只有一个位置槽：{@code DefaultServiceEntity.checkedPosition}
     * （语义 = "该 tab 的候选池里第几个被选中"），左右两栏共用。所以"切到
     * 哪栏编辑就把选中框对到那栏的卡"就是改写这个槽：
     * <ol>
     *   <li>按该栏的 composeService 的 serviceId，在 optionalServices 里求下标；</li>
     *   <li>写 checkedPosition；</li>
     *   <li>调 Fragment.M(tab, false)（系统内部名 updateRecyclerViewData）让
     *       列表按新位置重建——已经绑好的 item 不会自己重读选中态；</li>
     *   <li>scrollToPosition 把该项滚进可见区（卡片池很长时尤其需要）。</li>
     * </ol>
     *
     * @param side "col_0"=左栏，"col_1"=右栏
     */
    private static void focusPickerOn(final String side) {
        final int pos = DefaultServiceHook.focusCheckedOn(side);
        if (pos < 0) {
            return;
        }
        final Object frag = editorFragment;
        if (frag != null && DefaultServiceHook.customTabPos >= 0) {
            try {
                XposedHelpers.callMethod(frag, "M",
                        DefaultServiceHook.customTabPos, false);
            } catch (Throwable t) {
                // 方法名混淆，不同 ROM 可能不同：重建失败不影响数据层，
                // 下次 item 绑定仍会读到新的 checkedPosition。
                ModuleInit.log("picker rebuild fail " + t);
            }
        }
        final ViewGroup rv = gridRecycler;
        if (rv == null) {
            return;
        }
        rv.post(new Runnable() {
            @Override
            public void run() {
                final Object ad;
                try {
                    ad = XposedHelpers.callMethod(rv, "getAdapter");
                } catch (Throwable t) {
                    ModuleInit.log("picker adapter fail " + t);
                    return;
                }
                if (ad == null) {
                    return;
                }
                final int prev = lastFocusedIdx;
                boolean multi = false;
                // e(index, notify) 是系统自己的选中入口：内部按
                // "previousIndex = 当前 j" 去重绑「旧、新」两项。
                // 但点"开始编辑"时系统往往已经把 j 设成本次的目标值，
                // 两项重合后只重绑目标项，旧框不消失（实测 j=1->1 / j=2->2）。
                // 所以先把 j 退回模块上一次设置的位置，再调 e()，让它
                // 精确重绑两个不同的 item——不再需要 notifyDataSetChanged
                // 那样全量重建（那会让列表明显闪一下）。
                try {
                    multi = Boolean.TRUE.equals(XposedHelpers.getObjectField(ad, "h"));
                    if (!multi) {
                        XposedHelpers.setIntField(ad, "j", prev);
                    }
                    XposedHelpers.callMethod(ad, "e", pos, true);
                    if (multi) {
                        // 多 tab 模式下选中值在 ConcurrentHashMap 里，
                        // e() 算出的新旧索引可能落在同一项，退回全量重绑。
                        XposedHelpers.callMethod(ad, "notifyDataSetChanged");
                    }
                    lastFocusedIdx = pos;
                    ModuleInit.log("picker checked side=" + side + " pos=" + pos
                            + " prev=" + prev + " multi=" + multi);
                } catch (Throwable t) {
                    ModuleInit.log("picker checked fail " + t);
                }
                try {
                    // 池子第 0 项是分组标题，卡片从 1 开始。
                    // 用平滑滚动：左右两栏表现一致，不会一边滚一边瞬间跳。
                    XposedHelpers.callMethod(rv, "smoothScrollToPosition", pos + 1);
                    ModuleInit.log("picker focused side=" + side + " pos=" + pos);
                } catch (Throwable t) {
                    ModuleInit.log("picker scroll fail " + t);
                }
            }
        });
    }

    /** 池子没有任何选中项时 adapter.j 的取值。 */
    private static final int NO_SELECTION = -1;
    /** 模块最近一次设置到池子里的选中下标；-1 表示当前没有选中框。 */
    private static volatile int lastFocusedIdx = NO_SELECTION;

    /**
     * 还没点"开始编辑"时清掉池子里的选中框。
     *
     * <p>系统进来默认就带着一个 checkedPosition（左栏当前卡），所以不加处理时
     * 一打开编辑器下面就有蓝框，看起来像"已经选中了某张卡"。用户要求只有
     * 点了某一栏的"开始编辑"才出现选中框。
     */
    private static void clearPickerSelection() {
        final ViewGroup rv = gridRecycler;
        if (rv == null) {
            return;
        }
        rv.post(new Runnable() {
            @Override
            public void run() {
                // 这一帧之前用户可能已经点了"开始编辑"，此时不能再清。
                if (editActive) {
                    return;
                }
                try {
                    Object ad = XposedHelpers.callMethod(rv, "getAdapter");
                    if (ad == null) {
                        return;
                    }
                    if (XposedHelpers.getIntField(ad, "j") == NO_SELECTION) {
                        return;
                    }
                    XposedHelpers.setIntField(ad, "j", NO_SELECTION);
                    XposedHelpers.callMethod(ad, "notifyDataSetChanged");
                    lastFocusedIdx = NO_SELECTION;
                    ModuleInit.log("picker cleared (no column active)");
                } catch (Throwable t) {
                    ModuleInit.log("picker clear fail " + t);
                }
            }
        });
    }

    /**
     * 找卡片池 RecyclerView。
     *
     * <p>主路径是 {@code onBindViewHolder} 里沿 holder.itemView 父链取池子
     * （COUIRecyclerView 实测不回调 setAdapter / onAttachedToRecyclerView /
     *  onViewAttachedToWindow，只有 item 父链可靠）。这里作为"编辑器刚开、
     *  还没绑任何 item"时的一次兜底，判据是 adapter 类型而不是"第一个列表"。
     */
    private static void updateGridMask() {
        if (gridRecycler == null && editorRoot != null) {
            gridRecycler = findGridRecycler(editorRoot);
        }
        if (gridRecycler == null) {
            return;
        }
        // 网格是各 tab 共用的（ViewPager2 之外），遮罩只该在
        // "正在看自定义 tab 且未激活编辑"时出现；系统 tab 一律
        // 原生状态（不灰、不禁点）。
        boolean onCustom = DefaultServiceHook.customTabPos >= 0
                && curPage == DefaultServiceHook.customTabPos;
        boolean lock = onCustom && !editActive;
        gridRecycler.setAlpha(lock ? 0.4f : 1f);
        gridRecycler.setOnTouchListener(lock ? GRID_TOUCH_BLOCK : null);
        if (lock) {
            // 未点"开始编辑"：池子里不该有选中框。
            clearPickerSelection();
        }
    }

    /**
     * 按实际左右刷新两张预览卡（c1/c2 顺序任意，内部用 holder 判定）。
     */
    private static void refreshCardsPair(View c1, View c2) {
        if (c1 == null || c2 == null) {
            return;
        }
        boolean c1IsLeft = curHolder != null
                ? isLeftContainer(curHolder, c1, c2)
                : true;
        View left = c1IsLeft ? c1 : c2;
        View right = (left == c1) ? c2 : c1;
        boolean rightSel = editActive && "col_1".equals(pendingColumn);
        applyCard(left, "col_0", editActive && !rightSel, right);
        applyCard(right, "col_1", rightSel, left);
    }

    private static void applyCard(final View card, final String side, boolean sel,
                                  final View other) {
        try {
            if (card == null) {
                return;
            }
            card.setScaleX(1f);
            card.setScaleY(1f);
            if (!(card instanceof ViewGroup)) {
                return;
            }
            ViewGroup g = (ViewGroup) card;
            // 预览内容：未激活的一律呈灰色（半透明）——初始两栏都灰，
            // 点"编辑"激活后该栏变亮；标签本身不受影响永远清晰。
            boolean dimmed = !sel;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c == null || BADGE_TAG.equals(c.getTag())) {
                    continue;
                }
                c.setAlpha(dimmed ? 0.45f : 1f);
            }
            TextView tv;
            View badge = g.findViewWithTag(BADGE_TAG);
            if (badge instanceof TextView) {
                tv = (TextView) badge;
            } else {
                if (badge != null) {
                    g.removeView(badge);
                }
                tv = makeBadge(g);
                g.addView(tv);
            }
            tv.setText(sel ? "编辑中" : "开始编辑");
            // 未激活文字改黑字（灰字在变暗预览上对比度太低）
            tv.setTextColor(Color.parseColor(sel ? "#1A73E8" : "#1A1A1A"));
            tv.setAlpha(1f); // 标签和文字永远不透明
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pendingColumn = side;
                    editActive = true;
                    refreshCardsPair(card, other);
                    updateGridTitle();
                    updateGridMask(); // 去掉网格遮罩，工具可点
                    ModuleInit.log("editor column activated: " + side);
                    // 切到这一栏时，把下面卡片池的选中框对到该栏当前的卡。
                    focusPickerOn(side);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static final Object BADGE_TAG = new Object();

    /**
     * Is this really a RecyclerView? Compiled without androidx on the classpath,
     * so the check walks the class names instead of using {@code instanceof}.
     */
    private static boolean isRecyclerView(View v) {
        for (Class<?> c = v.getClass(); c != null; c = c.getSuperclass()) {
            if ("androidx.recyclerview.widget.RecyclerView".equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    /** 反射判断字段是否存在（沿父类链），用于按 holder 类型分流。 */
    private static boolean hasField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                c.getDeclaredField(name);
                return true;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return false;
    }

    /**
     * 判定 f/g 哪个是左栏容器：系统的 a3.d 是 compose[0]（左栏）的
     * ImageView，沿它的父链向上找，先命中谁谁就是左。都碰不到时
     * 保守返回 true（维持 f=左 的假设）。
     */
    private static boolean isLeftContainer(Object holder, View f, View g) {
        try {
            View d = (View) XposedHelpers.getObjectField(holder, "d");
            ViewParent p = d.getParent();
            while (p instanceof View) {
                if (p == f) {
                    return true;
                }
                if (p == g) {
                    return false;
                }
                p = p.getParent();
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** 白底圆角长条标签：垂直中心位于卡片 2/3 高度处、水平居中、带阴影。 */
    private static TextView makeBadge(ViewGroup parent) {
        Context ctx = parent.getContext();
        float den = ctx.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(ctx);
        tv.setTag(BADGE_TAG);
        tv.setText("编辑");
        tv.setTextSize(15);
        tv.setTextColor(Color.parseColor("#1A1A1A"));
        tv.setIncludeFontPadding(false);
        int hp = Math.round(16 * den);
        int vp = Math.round(6 * den);
        tv.setPadding(hp, vp, hp, vp);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(20 * den);
        bg.setColor(Color.WHITE);
        bg.setStroke(Math.round(1.5f * den), 0x44000000); // 灰边增强实体感
        tv.setBackground(bg);
        tv.setAlpha(1f);
        tv.setElevation(4 * den); // 投影，让白底从预览上浮起来
        // 水平居中；垂直方向等布局完成后平移到卡片 2/3 高度处（中心点）
        android.widget.FrameLayout.LayoutParams lp =
                new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        tv.setLayoutParams(lp);
        final TextView ftv = tv;
        final ViewGroup fparent = parent;
        tv.post(new Runnable() {
            @Override
            public void run() {
                int h = fparent.getHeight();
                int bh = ftv.getHeight();
                if (h > 0 && bh > 0) {
                    ftv.setTranslationY(h * 2f / 3f - bh / 2f);
                }
            }
        });
        return tv;
    }

    /** Reads the in-flight right-column selection for DefaultServiceHook. */
    static String inFlightRightSid() {
        return inFlightRightSid;
    }

    /** Last picked right-column bean (full fields) and its sid, for reuse. */
    static Object lastRightBean() {
        return lastRightBean;
    }

    static String lastRightSid() {
        return lastRightSid;
    }

    static void cacheRightBean(Object bean, String sid) {
        lastRightBean = bean;
        lastRightSid = sid;
    }

    // ---- 左栏 bean 缓存（对称右栏）：激活左栏点选时缓存，供初始预览同步 ----

    private static volatile Object lastLeftBean = null;
    private static volatile String lastLeftSid = null;

    static Object lastLeftBean() {
        return lastLeftBean;
    }

    static String lastLeftSid() {
        return lastLeftSid;
    }

    static void cacheLeftBean(Object bean, String sid) {
        lastLeftBean = bean;
        lastLeftSid = sid;
    }

    /** 在自定义 tab 之前的系统 tab 里找 sid（找到返回 tab 下标，否则 -1）。 */
    private static int findInSystemTabs(Object listEntity, String sid,
            int custom) {
        try {
            java.util.List<?> tabs = (java.util.List<?>) XposedHelpers.callMethod(
                    listEntity, "getDefaultServiceList");
            if (tabs == null) {
                return -1;
            }
            int limit = Math.min(custom, tabs.size());
            for (int i = 0; i < limit; i++) {
                Object tab = tabs.get(i);
                if (tab == null) {
                    continue;
                }
                java.util.List<?> optional =
                        (java.util.List<?>) XposedHelpers.callMethod(tab,
                                "getOptionalServices");
                if (optional == null) {
                    continue;
                }
                for (Object bean : optional) {
                    if (bean == null) {
                        continue;
                    }
                    Object s = DefaultServiceHook.sidOf(bean);
                    if (sid.equals(String.valueOf(s))) {
                        return i;
                    }
                }
            }
        } catch (Throwable t) {
            ModuleInit.log("findInSystemTabs fail " + t);
        }
        return -1;
    }

    /** 自定义 tab 左栏档案（col_0）是否已设置。 */
    private static boolean hasCol0() {        try {
            Context ctx = AppContext.get();
            if (ctx == null) {
                return false;
            }
            LayoutStore.Column[] cols = LayoutStore.columnsFor(ctx, 0);
            return cols != null && cols.length >= 1 && cols[0] != null
                    && cols[0].isSet();
        } catch (Throwable t) {
            return false;
        }
    }

    /** col_0 档案的 serviceId，未设置返回 null。 */
    private static String col0Sid() {
        try {
            Context ctx = AppContext.get();
            if (ctx == null) {
                return null;
            }
            LayoutStore.Column[] cols = LayoutStore.columnsFor(ctx, 0);
            if (cols != null && cols.length >= 1 && cols[0] != null
                    && cols[0].isSet()) {
                return cols[0].serviceId;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 被hook的原生配置写入方法（AppSettingUtilsKt.h），污染清理直调要用。 */
    private static Method sNativeWrite;

    /**
     * h() 钩子的放行标记：系统 tab 保存 / page 未知分支放行原生写入时置位，
     * afterHookedMethod 检测到就补原生配置油门（写入发生在 before 返回后）。
     */
    private static volatile boolean sNativePassThrough;

    /**
     * 原生配置链路油门：刷新 ums 进程里系统缓存的读取器（nv.p.e → 静态 f），
     * 并显式通知原生配置 URI。桌面左栏的重绑链是 metis 观察这个 URI →
     * 重绑 widget——决策 URI 通知驱动不了它（round 11/18 实证：5 个决策
     * URI 通知发了、决策数据正确，桌面左栏仍不动；写原生配置 + 此油门
     * 后立即更新）。h() 内部的通知不可靠（同轮实测），必须显式补发。
     */
    private static void nativeKick() {
        try {
            Context ctx = AppContext.get();
            if (ctx == null) {
                return;
            }
            try {
                Class<?> npc = XposedHelpers.findClass(
                        "com.oplus.sdp.nv.p", sClassLoader);
                XposedHelpers.callStaticMethod(npc, "e", ctx);
            } catch (Throwable ignored) {
            }
            try {
                ctx.getContentResolver().notifyChange(
                        android.net.Uri.parse("content://com.oplus.appplatform"
                                + ".settings/secure/"
                                + "group_card_user_selected_config"), null, 0);
            } catch (Throwable ignored) {
            }
            ModuleInit.log("native config kick done");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 复刻系统保存协程的 group_card_user_selected_config JSON 构造
     * （serviceId/type/size/cardType/supportSize/packageName/timestamp/
     * subdomain，见 EditDefaultCardFragment$handleSaveMenuItemClick$1 反编译，
     * 其中黄历 536878567 的 packageName 强制映射 com.coloros.calendar）。
     * 自定义保存把左预览卡写进原生配置时用——桌面左栏的重绑链依赖
     * 原生配置变化（见 nativeKick 注释）。
     */
    private static String buildNativeSelectedJson(Object bean) {
        try {
            if (bean == null) {
                return null;
            }
            Object cfg = XposedHelpers.callMethod(bean, "getCardConfigInfo");
            String sid = beanStr(bean, "getServiceId", "");
            if (sid.length() == 0) {
                return null;
            }
            int type = beanInt(cfg, "getType", -1);
            int size = beanInt(cfg, "getSize", 1);
            int cardType = beanInt(cfg, "getCategory", 1);
            String pkg = beanStr(cfg, "getPackageName", "");
            if ("536878567".equals(sid)) {
                pkg = "com.coloros.calendar";
            }
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("serviceId", sid);
            o.put("type", type);
            o.put("size", size);
            o.put("cardType", cardType);
            o.put("supportSize", size);
            o.put("packageName", pkg);
            o.put("timestamp", System.currentTimeMillis());
            o.put("subdomain", beanStr(bean, "getSubdomain", ""));
            return o.toString();
        } catch (Throwable t) {
            ModuleInit.log("build native json fail " + t);
            return null;
        }
    }

    /**
     * 清空原生"用户选择的卡"配置（写空 JSON，读取方解析不出 serviceId
     * 即视为无选择）。用 invokeOriginalMethod 绕过自身 hook，走系统自己的
     * 写入路径（跨应用设置提供器）。
     */
    private static void clearNativeSelected() {
        try {
            if (sNativeWrite == null) {
                return;
            }
            Context ctx = AppContext.get();
            if (ctx == null) {
                return;
            }
            XposedBridge.invokeOriginalMethod(sNativeWrite, null,
                    new Object[]{ctx.getContentResolver(),
                            "group_card_user_selected_config", "{}"});
        } catch (Throwable t) {
            ModuleInit.log("clear native selected fail " + t);
        }
    }

    /**
     * 自定义 tab 左栏预览的同步目标 sid：优先 col_0 档案（自定义 tab 的
     * 左栏独立存储，不被系统 tab 保存覆盖），无 col_0 时回退原生记录。
     */
    static String leftSidForSync() {
        try {
            Context ctx = AppContext.get();
            if (ctx != null) {
                LayoutStore.Column[] cols = LayoutStore.columnsFor(ctx, 0);
                if (cols != null && cols.length >= 1 && cols[0] != null
                        && cols[0].isSet() && cols[0].serviceId != null) {
                    return cols[0].serviceId;
                }
            }
        } catch (Throwable ignored) {
        }
        return currentLeftSid();
    }

    /** 把选中的左栏 bean 持久化到模块 prefs（进程重启后恢复）。 */
    private static void persistLeftBean(Object bean) {
        try {
            Context ctx = AppContext.get();
            if (ctx == null || sClassLoader == null) {
                return;
            }
            Class<?> g = XposedHelpers.findClass("com.google.gson.Gson",
                    sClassLoader);
            Object gson = XposedHelpers.newInstance(g);
            String json = (String) XposedHelpers.callMethod(gson, "toJson", bean);
            Bundle b = new Bundle();
            b.putString("key", "last_left_bean");
            b.putString("value", json);
            ctx.getContentResolver().call(
                    android.net.Uri.parse("content://" + LayoutProvider.AUTHORITY),
                    LayoutProvider.METHOD_PUT_EXTRA, null, b);
            ModuleInit.log("editor left bean persisted len=" + json.length());
        } catch (Throwable t) {
            ModuleInit.log("persist left bean fail " + t);
        }
    }

    /** 进程重启后从磁盘恢复最近左栏 bean；无可恢复数据返回 null。 */
    static Object loadPersistedLeftBean() {
        try {
            Context ctx = AppContext.get();
            if (ctx == null || sClassLoader == null) {
                return null;
            }
            Bundle b = new Bundle();
            b.putString("key", "last_left_bean");
            Bundle out = ctx.getContentResolver().call(
                    android.net.Uri.parse("content://" + LayoutProvider.AUTHORITY),
                    LayoutProvider.METHOD_GET_EXTRA, null, b);
            String json = out == null ? null : out.getString("value");
            if (json == null || json.length() == 0) {
                return null;
            }
            Class<?> beanCls = XposedHelpers.findClass(
                    "com.oplus.ums.groupcard.defaultservice.bean.ServiceBean",
                    sClassLoader);
            Class<?> g = XposedHelpers.findClass("com.google.gson.Gson",
                    sClassLoader);
            Object gson = XposedHelpers.newInstance(g);
            Object bean = XposedHelpers.callMethod(gson, "fromJson", json, beanCls);
            ModuleInit.log("editor left bean restored from disk");
            return bean;
        } catch (Throwable t) {
            ModuleInit.log("restore left bean fail " + t);
            return null;
        }
    }

    /**
     * 把选中的右栏 bean（含 CardConfigInfo）用 Gson 序列化持久化到模块
     * prefs：进程重启后内存缓存丢失，兜底时从磁盘恢复——这样预览兜底
     * 显示的永远是桌面当前真实的卡，而不是固定回退到月历。
     */
    private static void persistRightBean(Object bean) {
        try {
            Context ctx = AppContext.get();
            if (ctx == null || sClassLoader == null) {
                return;
            }
            Class<?> g = XposedHelpers.findClass("com.google.gson.Gson",
                    sClassLoader);
            Object gson = XposedHelpers.newInstance(g);
            String json = (String) XposedHelpers.callMethod(gson, "toJson", bean);
            Bundle b = new Bundle();
            b.putString("key", "last_right_bean");
            b.putString("value", json);
            ctx.getContentResolver().call(
                    android.net.Uri.parse("content://" + LayoutProvider.AUTHORITY),
                    LayoutProvider.METHOD_PUT_EXTRA, null, b);
            ModuleInit.log("editor right bean persisted len=" + json.length());
        } catch (Throwable t) {
            ModuleInit.log("persist right bean fail " + t);
        }
    }

    /** 进程重启后从磁盘恢复最近右栏 bean；无可恢复数据返回 null。 */
    static Object loadPersistedRightBean() {
        try {
            Context ctx = AppContext.get();
            if (ctx == null || sClassLoader == null) {
                return null;
            }
            Bundle b = new Bundle();
            b.putString("key", "last_right_bean");
            Bundle out = ctx.getContentResolver().call(
                    android.net.Uri.parse("content://" + LayoutProvider.AUTHORITY),
                    LayoutProvider.METHOD_GET_EXTRA, null, b);
            String json = out == null ? null : out.getString("value");
            if (json == null || json.length() == 0) {
                return null;
            }
            Class<?> beanCls = XposedHelpers.findClass(
                    "com.oplus.ums.groupcard.defaultservice.bean.ServiceBean",
                    sClassLoader);
            Class<?> g = XposedHelpers.findClass("com.google.gson.Gson",
                    sClassLoader);
            Object gson = XposedHelpers.newInstance(g);
            Object bean = XposedHelpers.callMethod(gson, "fromJson", json, beanCls);
            ModuleInit.log("editor right bean restored from disk");
            return bean;
        } catch (Throwable t) {
            ModuleInit.log("restore right bean fail " + t);
            return null;
        }
    }

    /** 从保存 JSON 里取 serviceId，取不到返回 null。 */
    private static String optServiceId(String json) {
        try {
            String sid = new org.json.JSONObject(json).optString("serviceId", "");
            return (sid.length() == 0 || "null".equals(sid)) ? null : sid;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 当前左栏的 serviceId：优先用本次会话的原生保存记录，否则读系统缓存。 */
    private static String currentLeftSid() {
        if (lastNativeLeftSid != null) {
            return lastNativeLeftSid;
        }
        return nativeLeftSidFresh();
    }

    /**
     * 实时读取原生"用户选择的卡"配置（nv.p.e 刷新静态缓存后解析），
     * 不走会话缓存——供 native 模式的桌面恢复（revertSceneList）用。
     */
    static String nativeLeftSidFresh() {
        try {
            Context ctx = AppContext.get();
            Class<?> p = XposedHelpers.findClass("com.oplus.sdp.nv.p", sClassLoader);
            // 系统自己的 getter：e(Context) 读取 group_card_user_selected_config
            // 到静态 f（HomeLeftServiceHelper）。
            XposedHelpers.callStaticMethod(p, "e", ctx);
            Object f = XposedHelpers.findField(p, "f").get(null);
            if (f instanceof String && ((String) f).length() > 0) {
                return optServiceId((String) f);
            }
        } catch (Throwable t) {
            ModuleInit.log("read left sid fail " + t);
        }
        return null;
    }

    /** 编辑器 fragment（initView 记录，备用操作入口）。 */
    private static volatile Object editorFragment;

    // ---- 对调（swap）辅助 --------------------------------------------------

    /** 反射调 bean 的 int 返回值方法，失败给默认值。 */
    private static int beanInt(Object bean, String method, int def) {
        try {
            Object v = XposedHelpers.callMethod(bean, method);
            return v instanceof Integer ? (Integer) v : def;
        } catch (Throwable t) {
            return def;
        }
    }

    /** 反射调 bean 的 String 返回值方法，失败给默认值。 */
    private static String beanStr(Object bean, String method, String def) {
        try {
            Object v = XposedHelpers.callMethod(bean, method);
            return v instanceof String ? (String) v : def;
        } catch (Throwable t) {
            return def;
        }
    }

    /** 跨进程写 tab_0 的某一列（rewriter 用 sid 向系统要全量字段）。 */

    private static Context ctx() {
        return AppContext.get();
    }

    /**
     * 放行被 4.1 挂住的关页动作（往返结束 / 兜底超时时调用，幂等）。
     *
     * @param why 日志用原因（roundtrip / fallback / timeout）
     */

    private static void toast(Context ctx, String msg) {        try {
            if (ctx == null) {
                return;
            }
            // 钩子可能跑在无 Looper 的线程上，Toast 必须在主线程弹出。
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                android.widget.Toast.makeText(ctx, msg,
                                        android.widget.Toast.LENGTH_SHORT).show();
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    /**
     * 开机快刷（ModuleInit 调用）：ums 就绪后主动补发决策变更通知。
     *
     * 背景：桌面重启后先渲染小组件快照（ROM 默认"系统工具+应用建议"），
     * ums 进程 ~2s 就绪、此刻查询已能返回模块改写的数据（toCursor 钩子
     * 生效），但 launcher 只在收到变更通知时才重新拉取——原生第一波推送
     * 要等它自己的数据管线热身完（实测 9~11s），这就是开机后卡片"慢半拍
     * 换装"的来源。这里在就绪后分三拍补发通知，把重拉时间点提前。
     */
    static void bootKickNotify() {
        long[] delays = {500L, 4000L, 10000L};
        for (final long d : delays) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(d);
                    } catch (InterruptedException e) {
                        return;
                    }
                    notifyDecisionRefresh();
                    ModuleInit.log("boot kick +" + d + "ms done");
                }
            }, "xiaobu-bootkick-" + d);
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * 补发决策变更通知。原生只通知 BASE_URI（带 SKIP_DESCENDANTS 标志，
     * 不会传给注册在子路径上的观察者），这里把所有可能的决策 URI 全量
     * 通知一遍，保证 launcher / assistantscreen 重新拉取。
     */
    private static void notifyDecisionRefresh() {
        try {
            Context ctx = AppContext.get();
            if (ctx == null) {
                return;
            }
            ContentResolver cr = ctx.getContentResolver();
            String[] uris = {
                    "content://com.oplus.pantanal.ums.scenedecision",
                    "content://com.oplus.pantanal.ums.scenedecision/sceneservices",
                    "content://com.oplus.pantanal.ums.scenedecision/scene_all_data",
                    "content://com.oplus.pantanal.ums.decision",
                    "content://com.oplus.pantanal.ums.decision/services",
            };
            int fired = 0;
            for (int i = 0; i < uris.length; i++) {
                try {
                    // 第一个按原生参数（BASE_URI + 32768），其余不带标志。
                    cr.notifyChange(android.net.Uri.parse(uris[i]), null,
                            i == 0 ? 32768 : 0);
                    fired++;
                } catch (Throwable ignored) {
                }
            }
            ModuleInit.log("editor decision change notified uris=" + fired);
        } catch (Throwable t) {
            ModuleInit.log("notify decision fail " + t);
        }
    }

}
