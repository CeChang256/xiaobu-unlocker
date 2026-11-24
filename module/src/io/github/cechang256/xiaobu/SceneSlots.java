package io.github.cechang256.xiaobu;

/**
 * 槽位 → 列 的纯决策。
 *
 * <p>宿主自己按「两行在 list 里的先后」定列（UMS 17.22.8 反编译
 * {@code SceneDecisionProvider.java:919-957 setLeftRight}）：i==0 时
 * {@code list=[home, 2x2]}、home.intentPosition=0、2x2.intentPosition=1；
 * i==1 时 {@code list=[2x2, home]}、home.intentPosition=1、2x2.intentPosition=0。
 * 也就是 <b>下标 == intentPosition == 视觉列</b>，而
 * {@code toCursor(:1048-1061)} 严格按 list 顺序出行。
 *
 * <p>所以改写侧也必须按位置分配 col_0 / col_1，不能按行身份（097 保证卡行）
 * 硬编码左右：native→custom 切换后宿主会把 097 行排到前面，此时按身份分配
 * 就把 col_1 送进了左栏（设备实测 2026-11-23，桌面与预览左右相反）。
 */
final class SceneSlots {

    /** 无法判定（只看得到一行 / 两行同一个下标 / 行缺失）。 */
    static final int UNKNOWN = -1;

    private SceneSlots() {
    }

    /**
     * 097 保证卡槽位行应该拿哪一列。
     *
     * @param idxStock 097 行在 list 中的下标，&lt;0 表示没有
     * @param idxHome  210000000 首张非固定卡行的下标，&lt;0 表示没有
     * @return 0 = 左（col_0）、1 = 右（col_1）、{@link #UNKNOWN} = 沿用旧身份映射
     */
    static int stockColumn(int idxStock, int idxHome) {
        if (idxStock < 0 || idxHome < 0 || idxStock == idxHome) {
            return UNKNOWN;
        }
        return idxStock < idxHome ? 0 : 1;
    }

    /** 另一槽（210000000 首张非固定卡行）的列：与 097 行相反；判定不出时按旧映射当左栏。 */
    static int homeColumn(int stockColumn) {
        return stockColumn == UNKNOWN ? 0 : 1 - stockColumn;
    }
}
