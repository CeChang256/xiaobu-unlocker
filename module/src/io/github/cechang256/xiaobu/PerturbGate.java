package io.github.cechang256.xiaobu;

/**
 * “模式扰动”闸门（1.39）：切到 custom 时，先让 provider 在时间窗内原样吐宿主
 * 原生卡组，窗到再吐自定义双栏，用一次真实的数据变化去撬 launcher 重建子卡视图。
 *
 * <p>为什么是“换模式”而不是“换一张卡”：用户明确否掉了中间态塞别的卡
 * （2026-11-23）；而且落位/gravity 只在 launcher 建视图时算一次
 * （childcard/o0.java:354-356 → GroupChildCardWrapper.k0 :1893-1894），
 * 常规刷新走 na/l.r0 的 “no need to load scene!” 直接返回。原生卡组与自定义
 * 卡组在 cardType 集合上通常不同，这是唯一不扩作用域就存在的“真变化”。
 *
 * <p>状态全部落在独立 prefs（{@code xiaobu_unlocker_perturb}），**绝不写
 * layout_json**：窗内被杀/断电，下一次查询读到的 until 已过期 → 直接正确态。
 *
 * <p>纯 JDK，无 android 依赖，便于 JVM 离线测试（tools/perturb_gate_test）。
 */
final class PerturbGate {

    /** 真扰动开关：layout_json.features.perturb_rebuild，**默认关**（1.39 首版）。 */
    static final String FEATURE = "perturb_rebuild";

    /** 中间态窗口：900ms。依据 = UMS 查询 1–2 次/秒 + B 组 ccv 落在保存后约 1s。 */
    static final long WINDOW_MS = 900L;

    /** 同一配置签名在此时长内只扰动一次。 */
    static final long SIG_TTL_MS = 24L * 60L * 60L * 1000L;

    /** 全局配额窗口与上限：12 小时内最多 5 次。 */
    static final long GLOBAL_WINDOW_MS = 12L * 60L * 60L * 1000L;
    static final int GLOBAL_LIMIT = 5;

    /** {@link #phase} 的三种结果。 */
    static final int PHASE_IDLE = 0;
    static final int PHASE_NATIVE = 1;
    static final int PHASE_RESTORE = 2;

    private PerturbGate() {
    }

    /** 落盘状态；{@code until<=0} 表示当前没有生效中的窗口。 */
    static final class State {
        final String sig;
        final long until;
        final long armedAt;
        final int count;
        final long since;

        State(String sig, long until, long armedAt, int count, long since) {
            this.sig = sig;
            this.until = until;
            this.armedAt = armedAt;
            this.count = count;
            this.since = since;
        }

        static final State EMPTY = new State(null, 0L, 0L, 0, 0L);
    }

    /** 一次判定：这一次要不要 serve 原生 + 判定后的状态 + 一句原因。 */
    static final class Decision {
        final boolean serveNative;
        final State state;
        final String reason;

        Decision(boolean serveNative, State state, String reason) {
            this.serveNative = serveNative;
            this.state = state == null ? State.EMPTY : state;
            this.reason = reason == null ? "" : reason;
        }
    }

    /** 配置签名：这里直接用调用方的 pairKey（已含 on/mode/来源/左右 sid）。 */
    static String signature(String pairKey) {
        if (pairKey == null || pairKey.length() == 0) {
            return null;
        }
        return pairKey;
    }

    /** 从 prefs 读回；缺项/脏值一律按空状态处理，永不抛。 */
    static State parse(String sig, long until, long armedAt, int count, long since) {
        if (sig == null || sig.length() == 0) {
            return State.EMPTY;
        }
        return new State(sig, Math.max(0L, until), Math.max(0L, armedAt),
                count < 0 ? 0 : count, Math.max(0L, since));
    }

    /** 当前窗口处于哪一段：没有窗口 / 窗内（serve 原生）/ 刚过期（要 restore 一次）。 */
    static int phase(State s, long now) {
        if (s == null || s.until <= 0L) {
            return PHASE_IDLE;
        }
        return now < s.until ? PHASE_NATIVE : PHASE_RESTORE;
    }

    /** 窗口结束后抹掉 until，计数保留（配额要用）。 */
    static State expire(State s) {
        if (s == null) {
            return State.EMPTY;
        }
        return new State(s.sig, 0L, s.armedAt, s.count, s.since);
    }

    /** 同签名是否还在 24h 保护期内（保护期内不再扰动）。 */
    static boolean sameSignatureWithin(State s, String sig, long now) {
        if (s == null || sig == null || s.sig == null || !sig.equals(s.sig)) {
            return false;
        }
        return now - s.armedAt < SIG_TTL_MS;
    }

    /** 全局配额还剩几次（<=0 表示用尽）；供 diag 与测试用。 */
    static int remaining(State s, long now) {
        if (s == null || s.since <= 0L || now - s.since >= GLOBAL_WINDOW_MS) {
            return GLOBAL_LIMIT;
        }
        int left = GLOBAL_LIMIT - s.count;
        return left < 0 ? 0 : left;
    }

    /**
     * 判定“这一次切到 custom”要不要开一个扰动窗口。
     *
     * @param prev      上次状态（null = 无状态）
     * @param sig       当前配置签名（null/空 = 无法判定，不扰动）
     * @param featureOn features.perturb_rebuild（默认关）
     * @param now       当前时间（毫秒）
     */
    static Decision start(State prev, String sig, boolean featureOn, long now) {
        State base = prev == null ? State.EMPTY : prev;
        if (!featureOn) {
            return new Decision(false, base, "skipped: feature off");
        }
        if (sig == null || sig.length() == 0) {
            return new Decision(false, base, "skipped: no signature");
        }
        if (base.until > now) {
            return new Decision(false, base, "skipped: window already active");
        }
        if (sameSignatureWithin(base, sig, now)) {
            return new Decision(false, base, "skipped: same signature within 24h");
        }
        boolean samePeriod = base.since > 0L && now - base.since < GLOBAL_WINDOW_MS;
        if (samePeriod && base.count >= GLOBAL_LIMIT) {
            return new Decision(false, base,
                    "skipped: quota " + base.count + "/" + GLOBAL_LIMIT + " in 12h");
        }
        int count = samePeriod ? base.count + 1 : 1;
        long since = samePeriod ? base.since : now;
        State armed = new State(sig, now + WINDOW_MS, now, count, since);
        return new Decision(true, armed, "armed " + count + "/" + GLOBAL_LIMIT
                + " window=" + WINDOW_MS + "ms");
    }
    /**
     * 是否该把这一轮的 rows 记成"原生卡组长什么样"（阶段 1 观测）。
     *
     * <p>踩过的坑（1.39 首轮）：记 sNativeTypes 的代码原本写在 "mode != custom"
     * 分支里，而 native 模式下 {@code on=false} 会在更早的 "skip: dswap off"
     * 处 return —— 于是真机上 native 态永远记不到，{@code native=?} 恒成立、
     * {@code sameType} 恒 false，phase 2 的 useless 过滤等于没有。
     *
     * <p>现在把判定提前到那个 return 之前，并且只在"刚切换"或"还没记录"时
     * 解析一次，避免每次查询都白跑一遍 JSON。
     *
     * @param custom   当前 mode 是否 custom
     * @param justOpened 本轮 pairKey 是否刚变化（含进程第一次看到）
     * @param have     是否已经有记录
     */
    static boolean shouldNoteNative(boolean custom, boolean justOpened, boolean have) {
        return !custom && (justOpened || !have);
    }
}