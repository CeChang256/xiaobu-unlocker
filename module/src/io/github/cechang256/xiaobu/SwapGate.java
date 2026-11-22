package io.github.cechang256.xiaobu;

import org.json.JSONObject;

/**
 * 桌面决策层改写（dswap）的闸门判据（1.35 起）。
 *
 * <p>用户语义：在原生样式编辑器里选中第三个「自定义」并保存，模块就该开始改写，
 * <strong>不需要任何 features 键</strong>。所以：
 * <ul>
 *   <li>{@code features.dswap} 存在时是<strong>显式覆盖</strong>：true 开、false 关，
 *       两级都照办（显式 false 不允许被 mode 翻掉——那是用户/调试者的真实选择）；</li>
 *   <li>键缺失时由 {@code mode()} 推断：custom（含"从没写过 mode 但有 col_1"的
 *       旧配置兼容）即开，native / 空即关。</li>
 * </ul>
 *
 * <p>为什么不再补种 {@code features.dswap=true}（1.34 的做法）：补种会把"实时的
 * 用户选择"冻成一次性快照——用户切回系统样式后残留的 true 会让闸门与 mode 打架，
 * 而且缺键本来就没有"用户关掉过"的含义。1.35 起 LayoutSeed 不再碰 dswap。
 *
 * <p>本类刻意只依赖 org.json、不碰 Android / Xposed 类型，好在普通 JVM 里单测
 * （见 tools/layout_seed_test/SwapGateTest.java）。mode 的计算仍归
 * {@link LayoutStore#mode}（它含往返标记自愈与 col_1 兼容），这里只做纯判定。
 */
final class SwapGate {

    /** layout JSON 里存放功能开关的对象键。 */
    static final String FEATURES = "features";

    /** 显式覆盖键名。 */
    static final String KEY = "dswap";

    /** 与 LayoutStore.MODE_CUSTOM 同义；本类保持无 Android 依赖，故不复用常量。 */
    static final String MODE_CUSTOM = "custom";

    private SwapGate() {
    }

    /**
     * 读显式键。
     *
     * @return {@code null} = 没有该键（或整串解析不出来，等价于没配过）；
     *         否则为它的布尔值（与 1.33 的 {@code optBoolean} 真值规则一致：
     *         非布尔值按 false 处理，不抛异常）
     */
    static Boolean explicit(String rawJson) {
        if (rawJson == null || rawJson.length() == 0) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(rawJson);
            JSONObject f = o.optJSONObject(FEATURES);
            if (f == null || !f.has(KEY)) {
                return null;
            }
            return f.optBoolean(KEY, false);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 闸门：显式键优先，缺键看 mode。 */
    static boolean on(Boolean explicit, String mode) {
        return explicit != null ? explicit.booleanValue() : isCustom(mode);
    }

    /** 是否 custom 模式（与 LayoutStore.isCustomMode 同规则：前缀匹配）。 */
    static boolean isCustom(String mode) {
        return mode != null && mode.startsWith(MODE_CUSTOM);
    }

    /** diag 用：{@code on(explicit)} / {@code off(explicit)} / {@code on(mode)} / {@code off(mode)}。 */
    static String label(boolean on, boolean explicit) {
        return (on ? "on" : "off") + "(" + (explicit ? "explicit" : "mode") + ")";
    }
}
