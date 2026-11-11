package io.github.cechang256.xiaobu;

import android.content.Context;
import android.text.TextUtils;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Makes either column of the 小布建议 group card user-selectable.
 *
 * How the stock model works (assets/app_ums_group_card_default_services.json and
 * com.oplus.ums.groupcard.defaultservice classes):
 *
 *   DefaultServiceListEntity
 *     defaultServiceList: List of DefaultServiceEntity   // one entry per tab
 *       composeServices: List of ServiceBean             // the card's columns
 *       optionalServices: List of ServiceBean            // what the picker offers
 *       checkedPosition                                  // picker state
 *
 * The editor draws column i from composeServices[i] and fills its picker from
 * optionalServices. The stock save path only ever writes composeServices[0]
 * (DefaultServiceListEntity#updateComposeServiceConfigInfo always rewrites
 * get(0)), so the second column can never change whatever the UI offers.
 * We rewrite the model right after it is built so each column follows the
 * user's own choice.
 *
 * A ServiceBean's identity for rendering is its CardConfigInfo: the widget host
 * resolves the card from that config (type + size + provider component), so a
 * replacement must carry the candidate's config, not just its serviceId.
 *
 * Scope note: this hook changes the editor/model, which on the device is
 * confirmed to drive the edit page preview. The desktop column is composed in
 * com.coloros.assistantscreen; see {@link AssistScreenHook}.
 */
final class DefaultServiceHook {

    private static final String CLS_LIST =
            "com.oplus.ums.groupcard.defaultservice.bean.DefaultServiceListEntity";
    private static final String CLS_ENTITY =
            "com.oplus.ums.groupcard.defaultservice.bean.DefaultServiceEntity";
    private static final String CLS_BEAN =
            "com.oplus.ums.groupcard.defaultservice.bean.ServiceBean";
    private static final String CLS_CONFIG =
            "com.oplus.ums.groupcard.defaultservice.bean.CardConfigInfo";

    private static java.lang.reflect.Method mGetDefaultServiceList;
    private static java.lang.reflect.Method mGetComposeServices;
    private static java.lang.reflect.Method mGetOptionalServices;
    private static java.lang.reflect.Method mGetServiceId;
    private static java.lang.reflect.Method mGetName;
    private static java.lang.reflect.Method mGetType;
    private static java.lang.reflect.Method mGetSize;
    private static java.lang.reflect.Method mGetCardConfig;
    private static java.lang.reflect.Method mSetServiceId;
    private static java.lang.reflect.Method mSetName;
    private static java.lang.reflect.Method mSetType;
    private static java.lang.reflect.Method mSetSize;
    private static java.lang.reflect.Method mSetCardConfig;
    private static java.lang.reflect.Method mGetComposeName;
    private static java.lang.reflect.Method mGetIndex;
    private static java.lang.reflect.Method mGetShouldFilter;
    private static java.lang.reflect.Method mGetCheckedPosition;
    /** DefaultServiceListEntity.updateCheckedPosition(int tabIndex, int position)。 */
    private static java.lang.reflect.Method mUpdateChecked;
    private static Class<?> sEntityCls;
    private static Class<?> sListCls;

    /** Name of the extra tab this module appends to the stock two. */
    private static final String CUSTOM_TAB_NAME = "系统工具+月历";
    /** Preferred native cards for the right column of the custom tab, in order. */
    private static final String[] CUSTOM_RIGHT_CANDIDATES = {
            "536875395", // 月历
            "536878567", // 黄历
            "536877066", // 节假日
            "536877064", // 纪念日
            "536877063", // 生日卡
            "536877065", // 倒数日
    };

    private DefaultServiceHook() {
    }

    /**
     * 反射查方法，**缺失不致命**。
     *
     * <p>这里每一个查找都必须是「可选」的：1.13 曾在 updateCheckedPosition 上
     * 写错所属类，getMethod 抛 NoSuchMethodException 直接把整个 install() 中断，
     * 结果是 getDefaultServiceList 的 hook 都没装上——编辑器的第三个 tab 消失、
     * 选中框定位整条链路静默失效，而桌面渲染照常，很难看出来。
     * 任何一个方法找不到只该让依赖它的那一小块功能降级。
     */
    private static java.lang.reflect.Method methodOf(Class<?> cls, String name,
            Class<?>... types) {
        try {
            return cls.getMethod(name, types);
        } catch (Throwable t) {
            String who = cls == null ? "null" : cls.getSimpleName();
            Diag.log("model", "missing method " + who + "." + name + " " + t);
            XposedBridge.log(ModuleInit.TAG + ": missing method " + who + "." + name + " " + t);
            return null;
        }
    }

    static void install(ClassLoader cl) throws Throwable {
        Class<?> listCls = XposedHelpers.findClass(CLS_LIST, cl);
        Class<?> entityCls = XposedHelpers.findClass(CLS_ENTITY, cl);
        Class<?> beanCls = XposedHelpers.findClass(CLS_BEAN, cl);
        Class<?> configCls = XposedHelpers.findClass(CLS_CONFIG, cl);

        mGetDefaultServiceList = methodOf(listCls, "getDefaultServiceList");
        mGetComposeServices = methodOf(entityCls, "getComposeServices");
        mGetOptionalServices = methodOf(entityCls, "getOptionalServices");
        mGetServiceId = methodOf(beanCls, "getServiceId");
        mGetName = methodOf(beanCls, "getName");
        mGetType = methodOf(beanCls, "getType");
        mGetSize = methodOf(beanCls, "getSize");
        mGetCardConfig = methodOf(beanCls, "getCardConfigInfo");
        mSetServiceId = methodOf(beanCls, "setServiceId", String.class);
        mSetName = methodOf(beanCls, "setName", String.class);
        mSetType = methodOf(beanCls, "setType", int.class);
        mSetSize = methodOf(beanCls, "setSize", int.class);
        mSetCardConfig = methodOf(beanCls, "setCardConfigInfo", configCls);
        mGetComposeName = methodOf(entityCls, "getComposeName");
        mGetIndex = methodOf(entityCls, "getIndex");
        mGetShouldFilter = methodOf(entityCls, "getShouldFilter");
        mGetCheckedPosition = methodOf(entityCls, "getCheckedPosition");
        // updateCheckedPosition(int tabIndex, int position) 属于
        // DefaultServiceListEntity（dexdump 实证 (II)V），不在单个 tab entity 上。
        mUpdateChecked = methodOf(listCls, "updateCheckedPosition",
                int.class, int.class);
        sEntityCls = entityCls;
        sListCls = listCls;

        if (mGetDefaultServiceList == null) {
            XposedBridge.log(ModuleInit.TAG
                    + ": model hook skipped (getDefaultServiceList not found)");
            return;
        }
        XposedBridge.hookMethod(mGetDefaultServiceList, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    apply(param.getResult(), param.thisObject);
                } catch (Throwable t) {
                    Diag.log("model", "apply failed " + t);
                }
            }
        });
        Diag.log("model", "hooked getDefaultServiceList");
        de.robv.android.xposed.XposedBridge.log(ModuleInit.TAG + ": model hooked");
    }

    @SuppressWarnings("unchecked")
    private static void apply(Object result, Object parent) {
        if (!(result instanceof List)) {
            return;
        }
        List<Object> tabs = (List<Object>) result;
        if (tabs.isEmpty()) {
            return;
        }
        Context ctx = AppContext.get();
        if (ctx == null) {
            Diag.log("model", "no context, cannot read layout");
            return;
        }
        if (!LayoutStore.enabled(ctx)
                || !LayoutStore.feature(ctx, LayoutStore.FEATURE_MODEL, true)) {
            return;
        }
        // 编辑器里每次选卡都会重新 apply：不再写 logcat（明细看 diag.log）。
        Diag.log("model", "apply tabs=" + tabs.size());
        // 顶层容器：updateCheckedPosition 只能从它调用。
        if (parent != null) {
            sListEntity = parent;
        }
        // 系统自带的 tab（系统工具+应用建议 / 应用建议）完全不碰：模型层不再把
        // 配置覆写进 stock tab（旧机制，会造成左右栏应用行为混乱）。桌面渲染
        // 由 SceneServiceRewriter 按配置改写决策数据完成。
        // 这里只负责：追加自定义 tab + 把它的左右预览同步成当前配置。
        try {
            appendCustomTab(tabs, parent);
        } catch (Throwable t) {
            Diag.log("model", "append custom tab failed " + t);
        }
        try {
            syncCustomTabPreview(tabs);
        } catch (Throwable t) {
            Diag.log("model", "sync custom tab failed " + t);
        }
    }

    /**
     * Adds a third tab ("系统工具+月历") to the editor's model.
     *
     * The stock model only has two tabs (系统工具+应用建议 and 应用建议), and the
     * save path can only rewrite column 0 of the selected tab. A tab whose
     * column 1 is already a native card therefore reaches the desktop without
     * touching the write path at all.
     */
    @SuppressWarnings("unchecked")
    private static void appendCustomTab(List<Object> tabs, Object parent) throws Throwable {
        android.content.Context ctx = AppContext.get();
        if (ctx == null || !LayoutStore.feature(ctx, LayoutStore.FEATURE_CUSTOM_TAB, true)) {
            return;
        }
        for (Object t : tabs) {
            if (t == null) {
                continue;
            }
            Object name = mGetComposeName.invoke(t);
            // The custom tab is named "系统工具+<card name>"; the stock tab is
            // exactly "系统工具+应用建议", so any other "系统工具+..." is ours.
            if (name instanceof String) {
                String n = (String) name;
                if (n.startsWith("系统工具+") && !"系统工具+应用建议".equals(n)) {
                    return;
                }
            }
        }
        Object tab0 = tabs.get(0);
        List<Object> compose = (List<Object>) mGetComposeServices.invoke(tab0);
        if (compose == null || compose.isEmpty()) {
            Diag.log("model", "custom tab: tab0 has no compose services");
            return;
        }
        Object left = compose.get(0);
        List<Object> optional = (List<Object>) mGetOptionalServices.invoke(tab0);

        StringBuilder ids = new StringBuilder("custom tab: optional=");
        if (optional != null) {
            for (Object o : optional) {
                if (o != null) {
                    ids.append(mGetServiceId.invoke(o)).append(' ');
                }
            }
        }
        Diag.log("model", ids.toString());
        de.robv.android.xposed.XposedBridge.log(ModuleInit.TAG + ": " + ids);
        Object right = null;
        if (optional != null) {
            for (String wanted : CUSTOM_RIGHT_CANDIDATES) {
                for (Object o : optional) {
                    if (o == null) {
                        continue;
                    }
                    if (wanted.equals(mGetServiceId.invoke(o))) {
                        right = o;
                        break;
                    }
                }
                if (right != null) {
                    break;
                }
            }
        }
        if (right == null && compose.size() >= 2) {
            right = compose.get(1);
            Diag.log("model", "custom tab: falling back to tab0 column 1");
        }
        if (right == null) {
            Diag.log("model", "custom tab: no right column candidate");
            return;
        }
        // 左栏初始 bean：tab0 的 compose[0] 不一定是桌面左栏实际（用户可能
        // 在系统编辑器里改过左栏），按桌面左栏实际 sid（系统用户选择记录）
        // 同步，兜底链同右栏（会话缓存→反查→磁盘）。
        try {
            String wantLeft = EditorHook.leftSidForSync();
            if (wantLeft != null) {
                Object lb = findLeftBean(tab0, wantLeft);
                if (lb != null) {
                    left = lb;
                }
                ModuleInit.log("custom tab left bake want=" + wantLeft
                        + " found=" + (lb != null)
                        + " left=" + sidOf(left));
            }
        } catch (Throwable t) {
            Diag.log("model", "custom tab left config fail " + t);
        }
        // 右栏初始卡按优先级取：本会话刚选的卡 > 已保存配置 > 默认候选。
        // 不这样做的后果：实体每次 apply 都重建，compose[1] 会被重置成
        // 默认候选（月历），既记不住已保存的选项，也会冲掉会话内选卡。
        String wantRight = null;
        Object better = null;
        try {
            wantRight = EditorHook.inFlightRightSid();
            if (wantRight == null) {
                LayoutStore.Column[] cols = LayoutStore.columnsFor(ctx, 0);
                if (cols != null && cols.length >= 2 && cols[1] != null
                        && cols[1].isSet() && cols[1].serviceId != null) {
                    wantRight = cols[1].serviceId;
                }
            }
            if (wantRight != null) {
                better = findRightBean(tab0, wantRight, -1);
                if (better != null) {
                    right = better;
                }
            }
        } catch (Throwable t) {
            Diag.log("model", "custom tab right config fail " + t);
        }
        ModuleInit.log("custom tab right bake want=" + wantRight
                + " found=" + (better != null)
                + " right=" + sidOf(right));
        java.util.List<Object> newCompose = new java.util.ArrayList<Object>();
        newCompose.add(left);
        newCompose.add(right);
        java.util.List<Object> newOptional = new java.util.ArrayList<Object>();
        if (optional != null) {
            newOptional.addAll(optional);
        }
        int index = tabs.size() + 1;
        try {
            Object last = tabs.get(tabs.size() - 1);
            index = ((Integer) mGetIndex.invoke(last)) + 1;
        } catch (Throwable ignored) {
        }
        String tabName = CUSTOM_TAB_NAME;
        try {
            Object rightName = mGetName.invoke(right);
            if (rightName instanceof String && ((String) rightName).length() > 0) {
                String clean = ((String) rightName).replaceAll("\\(.*\\)$", "");
                tabName = "系统工具+" + clean;
            }
        } catch (Throwable ignored) {
        }
        java.lang.reflect.Constructor<?> ctor = sEntityCls.getConstructor(
                int.class, String.class, List.class, List.class, int.class, boolean.class);
        Object entity = ctor.newInstance(index, tabName, newCompose, newOptional, 0, false);
        boolean added = false;
        try {
            tabs.add(entity);
            added = true;
        } catch (Throwable t) {
            added = false;
        }
        if (!added && parent != null) {
            java.util.List<Object> copy = new java.util.ArrayList<Object>(tabs);
            copy.add(entity);
            java.lang.reflect.Field f = sListCls.getDeclaredField("defaultServiceList");
            f.setAccessible(true);
            f.set(parent, copy);
            added = true;
            Diag.log("model", "custom tab: replaced immutable list");
        }
        if (!added) {
            Diag.log("model", "custom tab: add failed");
            return;
        }
        customTabPos = tabs.size() - 1;
        String msg = "custom tab " + tabName + " pos=" + customTabPos
                + " left=" + mGetServiceId.invoke(left) + " right=" + mGetServiceId.invoke(right)
                + " tabs=" + tabs.size();
        Diag.log("model", msg);
        de.robv.android.xposed.XposedBridge.log(ModuleInit.TAG + ": " + msg);
    }

    /** 自定义 tab 在 defaultServiceList 里的下标（viewPager position 相同）；-1 = 未追加。 */
    static volatile int customTabPos = -1;
    /** 自定义 tab 的实体（用于把用户选的卡同步进右栏预览）。 */
    static volatile Object customEntity;
    /**
     * 顶层 DefaultServiceListEntity（所有 tab 的容器）。
     * updateCheckedPosition(tabIndex, position) 挂在这个对象上，不在 tab 上。
     */
    static volatile Object sListEntity;

    /**
     * 编辑器打开时置位（EditorHook.initView）：下一次 apply 把自定义 tab
     * 预览同步成当前桌面配置，之后 apply 不再覆盖，保证选卡实时预览。
     */
    static volatile boolean pendingPreviewSync = false;

    /**
     * 把用户在自定义 tab 选的卡同步到指定列的预览 bean（copyComposeCol）。
     * 系统的 updateComposeServiceConfigInfo 硬编码只更新 compose[0]（左栏），
     * 右栏预览由本方法补齐。返回 true 表示已处理（调用方应跳过原方法）。
     */
    /** Reads serviceId from a ServiceBean for logging; null when unavailable. */
    static Object sidOf(Object serviceBean) {
        try {
            return mGetServiceId.invoke(serviceBean);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 卡片的显示名，去掉尾巴上的"(快应用)"/"(系统工具)"等来源后缀。
     * 用于卡片池标题「左栏 · 天气时钟」这类文案。
     */
    static String nameOf(Object serviceBean) {
        try {
            Object n = mGetName.invoke(serviceBean);
            if (!(n instanceof String)) {
                return null;
            }
            String s = (String) n;
            int cut = s.indexOf('(');
            if (cut > 0) {
                s = s.substring(0, cut);
            }
            return s.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把「某一栏当前的卡」设为编辑器的选中位置（就是下面卡片池里那个选中框）。
     *
     * <p>系统原生只记一个位置：{@code DefaultServiceEntity.checkedPosition}，
     * 由 {@code DefaultServiceListEntity.updateCheckedPosition(tabIndex, position)}
     * 写入，语义是"this tab 的候选池里第几个被选中"。所以左栏/右栏共用一个
     * 位置槽——这正是"切到哪栏编辑，就把选中框对到那栏的卡"的实现基础：
     * 切栏时重写这个位置即可，不需要动 UI。
     *
     * @param side "col_0"=左栏，"col_1"=右栏
     * @return 该卡在候选池（optionalServices）里的下标；-1 表示找不到（未改动）
     */
    static int focusCheckedOn(String side) {
        Object entity = customEntity;
        Object list = sListEntity;
        if (entity == null || list == null || customTabPos < 0) {
            return -1;
        }
        if (mUpdateChecked == null) {
            ModuleInit.log("checked focus: updateCheckedPosition unavailable");
            return -1;
        }
        int col = "col_1".equals(side) ? 1 : 0;
        Object bean = composeBean(col);
        Object sidObj = bean == null ? null : sidOf(bean);
        String sid = sidObj == null ? null : String.valueOf(sidObj);
        if (sid == null || sid.length() == 0) {
            return -1;
        }
        int idx = indexInOptional(entity, sid);
        if (idx < 0) {
            ModuleInit.log("checked focus: " + side + " sid=" + sid
                    + " not in picker, skipped");
            return -1;
        }
        try {
            mUpdateChecked.invoke(list, customTabPos, idx);
            ModuleInit.log("checked focus: " + side + " -> pos=" + idx
                    + " sid=" + sid);
            return idx;
        } catch (Throwable t) {
            ModuleInit.log("checked focus fail " + t);
            return -1;
        }
    }

    /** 候选池里 serviceId 对应的下标，找不到返回 -1。 */
    @SuppressWarnings("unchecked")
    private static int indexInOptional(Object entity, String sid) {
        try {
            Object raw = mGetOptionalServices.invoke(entity);
            if (!(raw instanceof List)) {
                return -1;
            }
            List<Object> opts = (List<Object>) raw;
            for (int i = 0; i < opts.size(); i++) {
                Object b = opts.get(i);
                if (b == null) {
                    continue;
                }
                Object id = sidOf(b);
                if (sid.equals(String.valueOf(id))) {
                    return i;
                }
            }
        } catch (Throwable t) {
            ModuleInit.log("checked search fail " + t);
        }
        return -1;
    }

    /**
     * 从 DefaultServiceListEntity（整个列表）解析出指定下标的单 tab 实体
     * （DefaultServiceEntity）。updateComposeServiceConfigInfo 的 thisObject
     * 是列表实体，getComposeServices/getOptionalServices 都在单 tab 实体上。
     */
    static Object tabEntityOf(Object listEntity, int tabIndex) {
        try {
            List<Object> tabs = (List<Object>) mGetDefaultServiceList.invoke(listEntity);
            if (tabs == null || tabIndex < 0 || tabIndex >= tabs.size()) {
                return null;
            }
            return tabs.get(tabIndex);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 自定义 tab 预览两栏的当前 bean（compose[0]=左 / compose[1]=右）。
     * 保存时以这里的状态为准（编辑器即真相源），取不到返回 null。
     */
    @SuppressWarnings("unchecked")
    static Object composeBean(int col) {
        try {
            Object e = customEntity;
            if (e == null) {
                return null;
            }
            List<Object> compose = (List<Object>) mGetComposeServices.invoke(e);
            if (compose == null || col < 0 || col >= compose.size()) {
                return null;
            }
            return compose.get(col);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean writeComposeColumn(Object entity, Object serviceBean, int copyComposeCol) {        try {
            List<Object> compose = (List<Object>) mGetComposeServices.invoke(entity);
            if (compose == null || compose.size() <= copyComposeCol || serviceBean == null) {
                return false;
            }
            Object target = compose.get(copyComposeCol);
            if (target == null) {
                return false;
            }
            mSetServiceId.invoke(target, mGetServiceId.invoke(serviceBean));
            mSetName.invoke(target, mGetName.invoke(serviceBean));
            mSetType.invoke(target, mGetType.invoke(serviceBean));
            mSetSize.invoke(target, mGetSize.invoke(serviceBean));
            Object cfg = mGetCardConfig.invoke(serviceBean);
            if (cfg != null) {
                mSetCardConfig.invoke(target, cfg);
            }
            Diag.log("model", "compose col " + copyComposeCol + " -> "
                    + mGetServiceId.invoke(serviceBean));
            return true;
        } catch (Throwable t) {
            Diag.log("model", "writeComposeColumn failed " + t);
            return false;
        }
    }

    /**
     * 把自定义 tab 的左右预览同步成当前配置（tab_0.col_0 / col_1），
     * 让打开编辑器时第三个选项的预览与桌面一致。stock tab 不碰。
     */
    @SuppressWarnings("unchecked")
    private static void syncCustomTabPreview(List<Object> tabs) throws Throwable {
        Object entity = null;
        for (Object t : tabs) {
            if (t == null) {
                continue;
            }
            Object name = mGetComposeName.invoke(t);
            if (name instanceof String) {
                String n = (String) name;
                if (n.startsWith("系统工具+") && !"系统工具+应用建议".equals(n)) {
                    entity = t;
                    break;
                }
            }
        }
        if (entity == null) {
            return;
        }
        customEntity = entity;
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i) == entity) {
                customTabPos = i;
            }
        }
        // 预览同步只在编辑器打开时做一次：apply() 在选卡过程中会被反复触发
        // （重绑预览本身就会调 getDefaultServiceList），如果每次都把预览 bean
        // 重置成已保存配置，用户刚选的卡会被冲掉，预览就"不实时"了。
        if (!pendingPreviewSync) {
            return;
        }
        pendingPreviewSync = false;
        syncEntityPreview(entity);
    }

    /**
     * 编辑器打开时由 EditorHook 直接调用：把缓存的自定义 tab 实体的预览
     * 同步成当前配置。重进编辑器时 ViewModel 复用旧实体，getDefaultServiceList
     * （apply）不会再被调用，不主动同步的话预览永远停留在上次构建的值。
     *
     * @return true 当存在自定义 tab 实体并已执行同步
     */
    static boolean syncPreviewNow() {
        try {
            Object entity = customEntity;
            if (entity == null) {
                ModuleInit.log("editor open sync: no custom entity yet");
                return false;
            }
            pendingPreviewSync = false;
            syncEntityPreview(entity);
            return true;
        } catch (Throwable t) {
            ModuleInit.log("syncPreviewNow fail " + t);
            return false;
        }
    }

    /** 把 entity 的双列预览 bean 同步成当前配置（col_0 / col_1）。 */
    @SuppressWarnings("unchecked")
    private static void syncEntityPreview(Object entity) throws Throwable {
        android.content.Context ctx = AppContext.get();
        if (ctx == null) {
            return;
        }
        LayoutStore.Column[] wanted = LayoutStore.columnsFor(ctx, 0);
        List<Object> compose = (List<Object>) mGetComposeServices.invoke(entity);
        if (compose == null || compose.size() < 2) {
            return;
        }
        for (int col = 0; col < 2; col++) {
            Object bean = compose.get(col);
            String want;
            Object candidate;
            if (col == 0) {
                // 左栏走系统原生（模块无 col_0 配置），按桌面左栏实际同步
                want = EditorHook.leftSidForSync();
                candidate = want == null ? null : findLeftBean(entity, want);
            } else {
                LayoutStore.Column c = wanted == null ? null : wanted[1];
                if (c == null || !c.isSet()) {
                    continue;
                }
                want = c.serviceId;
                // 右栏优先复用点选缓存的完整 bean（可选列表部分 bean 缺配置）。
                candidate = findRightBean(entity, c.serviceId, c.type);
            }
            if (want == null || bean == null || candidate == null) {
                continue;
            }
            if (!want.equals(mGetServiceId.invoke(bean))) {
                String name = (String) mGetName.invoke(candidate);
                mSetServiceId.invoke(bean, want);
                if (!TextUtils.isEmpty(name)) {
                    mSetName.invoke(bean, name);
                }
                mSetType.invoke(bean, (Integer) mGetType.invoke(candidate));
                mSetSize.invoke(bean, (Integer) mGetSize.invoke(candidate));
                Object cfg = mGetCardConfig.invoke(candidate);
                if (cfg != null) {
                    mSetCardConfig.invoke(bean, cfg);
                }
                Diag.log("model", "custom tab col " + col + " synced -> " + want);
            }
        }
    }

    /**
     * 右栏 bean 查找，兜底链：会话缓存（网格点选的完整 bean）→ 卡池反查 →
     * 磁盘持久化恢复（= 桌面当前真实状态）。绝不固定回退到月历。
     */
    static Object findRightBean(Object tabEntity, String sid, int type) {
        if (sid == null) {
            return null;
        }
        if (sid.equals(EditorHook.lastRightSid())) {
            Object bean = EditorHook.lastRightBean();
            if (bean != null) {
                return bean;
            }
        }
        Object c = findCandidate(tabEntity, sid, type);
        if (c != null) {
            return c;
        }
        Object restored = EditorHook.loadPersistedRightBean();
        if (restored != null) {
            Object rsid = sidOf(restored);
            if (rsid != null && sid.equals(String.valueOf(rsid))) {
                EditorHook.cacheRightBean(restored, String.valueOf(rsid));
                return restored;
            }
        }
        return null;
    }

    /**
     * 左栏 bean 查找，兜底链同右栏：会话缓存 → 卡池反查 → 磁盘恢复。
     */
    static Object findLeftBean(Object tabEntity, String sid) {
        if (sid == null) {
            return null;
        }
        if (sid.equals(EditorHook.lastLeftSid())) {
            Object bean = EditorHook.lastLeftBean();
            if (bean != null) {
                return bean;
            }
        }
        Object c = findCandidate(tabEntity, sid, -1);
        if (c != null) {
            return c;
        }
        Object restored = EditorHook.loadPersistedLeftBean();
        if (restored != null) {
            Object rsid = sidOf(restored);
            if (rsid != null && sid.equals(String.valueOf(rsid))) {
                EditorHook.cacheLeftBean(restored, String.valueOf(rsid));
                return restored;
            }
        }
        return null;
    }

    /** Finds the ROM's own ServiceBean for a card so we reuse its config. */
    @SuppressWarnings("unchecked")
    static Object findCandidate(Object tabEntity, String serviceId, int type) {
        Object exact = null;
        Object loose = null;
        for (int pass = 0; pass < 2; pass++) {
            List<Object> list;
            try {
                list = pass == 0
                        ? (List<Object>) mGetOptionalServices.invoke(tabEntity)
                        : (List<Object>) mGetComposeServices.invoke(tabEntity);
            } catch (Throwable t) {
                continue;
            }
            if (list == null) {
                continue;
            }
            for (Object o : list) {
                if (o == null) {
                    continue;
                }
                try {
                    if (!serviceId.equals(mGetServiceId.invoke(o))) {
                        continue;
                    }
                    if (mGetCardConfig.invoke(o) == null) {
                        continue;
                    }
                    int t = (Integer) mGetType.invoke(o);
                    if (type <= 0 || t == type) {
                        if (exact == null) {
                            exact = o;
                        }
                    } else if (loose == null) {
                        loose = o;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return exact != null ? exact : loose;
    }
}
