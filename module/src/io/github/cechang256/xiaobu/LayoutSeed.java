package io.github.cechang256.xiaobu;

import org.json.JSONObject;

/**
 * layout_json 的 features 补种（1.34 起，1.35 收敛为不碰 dswap）。
 *
 * <p>历史：1.33 及更早的闸门是 {@code LayoutStore.feature(ctx, "dswap", false)}，
 * 键缺失等于关闭，而代码<strong>从不写 features 键</strong>——于是全新安装 /
 * 配置未迁移的机器上编辑器能用、桌面永远不改写（新机 17.0.1.201 实测，
 * diag.log 里只剩 {@code dswap: skip: dswap off}）。1.34 的办法是把缺键补成
 * 显式 true。
 *
 * <p>1.35 起闸门改由 mode 驱动（{@link SwapGate} / {@link LayoutStore#dswapOn}）：
 * 选了自定义（mode=custom）就开，{@code features.dswap} 只是可选覆盖。因此
 * dswap 的补种已经没有作用，而且有害——补进去的 true 是<strong>一次性快照</strong>，
 * 会把"用户后来切回系统样式"这种实时状态挡在门外。本类从此只补两项与默认值
 * 一致的键（model / custom_tab），让配置自解释；对已存在的任何键（包括
 * {@code dswap: false} 或 1.34 写下的 {@code dswap: true}）一律不读不写不删。
 *
 * <p>这个类刻意只依赖 org.json、不碰任何 Android / Xposed 类型，
 * 好让它能在普通 JVM 里被单测覆盖（见 tools/layout_seed_test/）。
 */
final class LayoutSeed {

    /** layout JSON 里存放功能开关的对象键。 */
    static final String FEATURES = "features";

    /** 与 1.33 默认值一致的项：补种不改变行为，只为让配置自解释。 */
    static final String[] KEEP_DEFAULTS = { "model", "custom_tab" };

    private LayoutSeed() {
    }

    /**
     * 就地把缺失的 features 补进 root。
     *
     * <p>空对象（没有任何配置）不补：那等于"用户还没配过桌面"，
     * 凭空写出一份 features 只会制造一个假的"已配置"状态。
     *
     * @return root 是否被改动（调用方据此决定要不要写回 prefs）
     */
    static boolean seed(JSONObject root) {
        if (root == null || root.length() == 0) {
            return false;
        }
        JSONObject f = featuresOf(root);
        if (f == null) {
            return false;
        }
        boolean changed = false;
        for (String k : KEEP_DEFAULTS) {
            changed = putIfAbsent(f, k) || changed;
        }
        return changed;
    }

    /**
     * 取 features 对象；缺失或类型损坏（字符串/数字/数组）时重建一个。
     * 损坏值留着也没有任何代码能读，替换掉不损失信息。
     */
    private static JSONObject featuresOf(JSONObject root) {
        Object cur = root.opt(FEATURES);
        if (cur instanceof JSONObject) {
            return (JSONObject) cur;
        }
        try {
            JSONObject f = new JSONObject();
            root.put(FEATURES, f);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 只在键不存在时写入 true：已存在的值（显式 false 也一样）绝不覆盖。 */
    private static boolean putIfAbsent(JSONObject f, String key) {
        if (f.has(key)) {
            return false;
        }
        try {
            f.put(key, true);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
