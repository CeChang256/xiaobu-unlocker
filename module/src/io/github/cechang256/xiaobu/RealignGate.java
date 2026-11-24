package io.github.cechang256.xiaobu;

/**
 * 1.41 B：切到「自定义」时要不要把 compose 双栏按 {@code col_0/col_1} 重排一次。
 *
 * <p>为什么需要：用户从系统样式直接切到自定义、不点「开始编辑」时，保存路径读到的
 * compose 可能还是上一轮的旧卡（预览同步失败/未触发），于是确定之后桌面被换成别的卡。
 * 切模式这一刻按配置重排一次，再发一次与「确定」等价的通知，launcher 拿到的就是对的那一对。
 *
 * <p>约束：同一个 pairKey 只做一次（避免每次查询都重排）；{@code features.compose_realign}
 * 默认开、可关；失败只记一行日志；不写 prefs、不碰 layout_json。
 */
final class RealignGate {

    /** 关闭开关：layout_json 的 features.compose_realign=false。 */
    static final String FEATURE = "compose_realign";

    private RealignGate() {
    }

    static boolean shouldRun(boolean featureOn, String pairKey, String lastPairKey) {
        return featureOn && pairKey != null && !pairKey.equals(lastPairKey);
    }

    /** 给 diag 用的一句话原因（无论跑不跑都能解释清楚）。 */
    static String reason(boolean featureOn, String pairKey, String lastPairKey) {
        if (!featureOn) {
            return "off (features." + FEATURE + "=false)";
        }
        if (pairKey == null) {
            return "skip: no pair key";
        }
        if (pairKey.equals(lastPairKey)) {
            return "skip: same pair (" + pairKey + ")";
        }
        return "run (" + pairKey + ")";
    }
}
