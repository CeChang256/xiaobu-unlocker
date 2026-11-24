package io.github.cechang256.xiaobu;

/**
 * 编辑器预览每一栏该做什么（1.41）。
 *
 * <p>背景：预览的数据源原先不是 {@code col_0/col_1} 配置，而是"卡池里找得到就用、
 * 找不到就保持 compose 槽位里原有的 bean"。旧 bean 属于上一次会话，于是出现
 * "配置是 时钟+今日天气，预览却显示 实时天气+月历，点确定把档案冲成那两个"。
 *
 * <p>现在的规则：槽位与服务 id 一致 → 不动；不一致且卡池里有完整 bean → 用它覆盖；
 * 卡池里没有 → <b>按配置现造一个 bean</b>（{@link #REBUILD}），绝不沿用旧卡。
 * 用户正在编辑、尚未保存的预览不受影响——同步只在"打开编辑器"那一刻跑一次，
 * 编辑过程中的点选直接改 compose，不会再被本类覆盖。
 */
final class PreviewPlan {

    /** 配置没写这一栏（或没有可写入的实体）：保持原样。 */
    static final int SKIP = 0;
    /** 槽位已经是目标卡：不动。 */
    static final int KEEP = 1;
    /** 用卡池里的完整 bean 覆盖槽位。 */
    static final int WRITE = 2;
    /** 卡池里没有这张卡：按配置现造 bean 覆盖槽位。 */
    static final int REBUILD = 3;

    private PreviewPlan() {
    }

    /**
     * @param entityPresent 自定义 tab 实体是否存在（编辑器还没建列表时不存在）
     * @param colSet        该栏在配置里是否有 serviceId
     * @param want          该栏目标的 serviceId
     * @param haveSid       槽位当前 bean 的 serviceId（可为 null）
     * @param haveCandidate 卡池/缓存里是否找到同一 serviceId 的完整 bean
     */
    static int action(boolean entityPresent, boolean colSet, String want,
                      String haveSid, boolean haveCandidate) {
        if (!entityPresent) {
            return SKIP;
        }
        if (!colSet || want == null || want.length() == 0) {
            return SKIP;
        }
        if (want.equals(haveSid)) {
            return KEEP;
        }
        return haveCandidate ? WRITE : REBUILD;
    }

    static boolean writes(int action) {
        return action == WRITE || action == REBUILD;
    }

    static String label(int action) {
        switch (action) {
            case KEEP:
                return "keep";
            case WRITE:
                return "write";
            case REBUILD:
                return "rebuild";
            default:
                return "skip";
        }
    }
}
