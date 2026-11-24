package io.github.cechang256.xiaobu;

/**
 * 「让 launcher 重建卡片槽位」的注入闸门（1.38）。
 *
 * <p>背景：卡片落位（layoutGravity/order）由 launcher 进程在**建视图**时算一次，
 * 常规刷新直接 "no need to load scene!" 返回（na/l.r0）。唯一不扩作用域就能
 * 撬动的把手在 UMS 数据侧：GCP 的 sa/p.s(ServiceInfo) 为真时，na/h.z1() 会在
 * “用户解锁”那一刻 setForceRebuild(true) 并对场景整重载 → 子卡视图按当前数据
 * 重建 → 左右跟着当前行序摆正。sa/p.s() 读的是两处标记：ServiceInfo extras 的
 * isUserSelected（{@code g0.d()} 分支）与 cardConfig JSON 里的 isUserSelected
 * （{@code g0.j()} 分支）；后者可由本模块在 cursor 列上写入。
 *
 * <p>闸门只负责“这次要不要注入”，不碰 JSON（见 {@link CardConfigStamp}）。为避免
 * 每次解锁都触发一次整场景重载（有人叫它闪烁），注入按“配置签名 + 次数”收敛：
 * 签名变了（改配置 / 切模式 / 重启后配置仍不同）重新武装，之后最多注入
 * {@code max} 次，然后静默；状态落 prefs，进程重启不会重新计数。
 *
 * <p>纯 JDK，无 android 依赖，便于 JVM 离线测试（tools/inject_gate_test）。
 */
final class InjectGate {

    /** 关掉注入的 feature 名：layout_json.features.rebuild_inject = false。 */
    static final String FEATURE = "rebuild_inject";
    /** 注入次数上限的可调项：layout_json.features.rebuild_inject_max（默认 {@link #LIMIT}）。 */
    static final String FEATURE_MAX = "rebuild_inject_max";
    /** 默认注入次数上限：一次配置变化最多注入 3 轮查询。 */
    static final int LIMIT = 3;

    private InjectGate() {
    }

    /** 落盘的闸门状态；{@link #sig} 为 null 表示从没武装过。 */
    static final class State {
        final String sig;
        final int count;
        final long lastAt;

        State(String sig, int count, long lastAt) {
            this.sig = sig;
            this.count = count;
            this.lastAt = lastAt;
        }

        static final State EMPTY = new State(null, 0, 0L);
    }

    /** 一次判定：注不注入、判定后的状态、给 diag 的一句话原因。 */
    static final class Decision {
        final boolean inject;
        final State state;
        final String reason;

        Decision(boolean inject, State state, String reason) {
            this.inject = inject;
            this.state = state == null ? State.EMPTY : state;
            this.reason = reason == null ? "" : reason;
        }

        String sig() {
            return state.sig == null ? "-" : state.sig;
        }
    }

    /** 配置签名：mode + 左右组合（调用方给的 pairKey）。两边都可能为 null。 */
    static String signature(String mode, String pairKey) {
        String m = mode == null ? "" : mode;
        String p = pairKey == null ? "" : pairKey;
        if (m.length() == 0 && p.length() == 0) {
            return null;
        }
        return m + "|" + p;
    }

    /** 从 prefs 读回的状态；缺项按“没武装过”处理。 */
    static State parse(String sig, int count, long lastAt) {
        if (sig == null || sig.length() == 0) {
            return State.EMPTY;
        }
        return new State(sig, count < 0 ? 0 : count, lastAt);
    }

    /**
     * 判定这一次查询要不要注入。
     *
     * @param prev      上次状态（null = 无状态）
     * @param sig       当前配置签名（null/空 = 无法判定，不注入）
     * @param featureOn layout_json.features.rebuild_inject（默认 true）
     * @param max       次数上限；&lt;=0 时用 {@link #LIMIT}
     * @param now       当前时间（毫秒），只在 State.lastAt 里留痕
     */
    static Decision next(State prev, String sig, boolean featureOn, int max, long now) {
        State base = prev == null ? State.EMPTY : prev;
        if (!featureOn) {
            return new Decision(false, base, "feature off");
        }
        if (sig == null || sig.length() == 0) {
            return new Decision(false, base, "no signature");
        }
        int cap = max <= 0 ? LIMIT : max;
        if (base.sig == null || !sig.equals(base.sig)) {
            return new Decision(true, new State(sig, 1, now), "armed " + (base.sig == null ? "first" : "sig changed"));
        }
        if (base.count >= cap) {
            return new Decision(false, base, "done " + base.count + "/" + cap);
        }
        return new Decision(true, new State(sig, base.count + 1, now), "again " + (base.count + 1) + "/" + cap);
    }
}
