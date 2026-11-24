package io.github.cechang256.xiaobu;

/**
 * 槽位定序键的纯决策（round 55）。
 *
 * <p>为什么需要它：2026-11-23 的真机证据里，两个状态的**列表下标**（home@0、
 * 097@1）、**intentPosition**（0 / 1）、宿主库里的行序（390 在前、709 在后）
 * 完全相同，桌面前后却相反。也就是说宿主/桌面并不是按"我们摆好的位置"定
 * 左右的，而是按某个被 {@code PRESERVE_FIELDS} 原样留下的字段定序：
 * 正确态 {@code policyName=SUBSTITUTE}，错误态 {@code policyName=空}；同时
 * 两行的 {@code timestamp} 随"用户在编辑器里点确定"而翻转。
 *
 * <p>既然如此，改写侧就不该只摆一条线：把 policyName、intentPosition、
 * 列表下标、timestamp/createTime 四条定序线一起摆正。下游只认其中一条时，
 * 其余三条是无害的冗余；认哪一条都能得到配置里的左右。
 */
final class SceneOrder {

    /** 用户自定义槽（用户卡）在宿主词表里的 policyName。 */
    static final String POLICY_LEFT = "SUBSTITUTE";

    /** 保证卡槽（097 / 应用建议位）在宿主词表里的 policyName。 */
    static final String POLICY_STOCK = "DYNAMIC_SUBSTITUTE";

    private SceneOrder() {
    }

    /**
     * 槽位行应该带哪个 policyName。
     *
     * <p>已经有非空 policy 的一律保留——那是宿主自己写的策略标记（例如
     * {@code TODAY_WEATHER_REMINDER}、{@code PERSISTENT_*}），改写侧不该覆盖；
     * 只把**空串/null**（用户点确定之后宿主落库的真实卡行就是空）补成槽位值。
     *
     * @param current   行上现在的 policyName，可为 null
     * @param stockSlot true = 保证卡槽行，false = 用户自定义槽行
     */
    static String policyFor(String current, boolean stockSlot) {
        if (current != null && current.length() > 0) {
            return current;
        }
        return stockSlot ? POLICY_STOCK : POLICY_LEFT;
    }

    /**
     * 左行是否必须与右行对调时间戳（左行要严格更旧）。
     *
     * @return true = 两行相等或左行更新，需要摆正
     */
    static boolean needsSwap(long leftTs, long rightTs) {
        return leftTs >= rightTs;
    }

    /** 两行时间戳相同时左行用的值：减 1 毫秒，保证严格更旧且不影响时效判断。 */
    static long leftWhenEqual(long ts) {
        return ts - 1L;
    }
}
