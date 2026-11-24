package io.github.cechang256.xiaobu;

import java.util.ArrayList;
import java.util.List;

/**
 * PerturbGate 的离线测试（1.39）。
 *
 * <p>覆盖：签名规整、脏状态回落、phase 三态、窗口过期、同签名 24h 幂等、
 * 12h 配额、异常输入不抛。纯 JDK，无 android 依赖。
 */
public final class PerturbGateTest {

    private static int passed;
    private static final List<String> failed = new ArrayList<>();

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed.add(name);
            System.out.println("FAIL " + name);
        }
    }

    private static void checkEq(String name, Object want, Object got) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (!ok) {
            System.out.println("  want=[" + want + "] got=[" + got + "]");
        }
        check(name, ok);
    }

    private static final long T0 = 1795420000000L;
    private static final long SEC = 1000L;
    private static final long MIN = 60L * SEC;
    private static final long HOUR = 60L * MIN;

    public static void main(String[] args) {
        signature();
        parseDirty();
        phases();
        expireKeepsQuota();
        sameSignature();
        remaining();
        startGates();
        idempotentAndQuota();
        shouldNoteNative();
        System.out.println();
        if (failed.isEmpty()) {
            System.out.println("RESULT OK PASS " + passed + " FAIL 0");
            System.out.println("ALL PERTURB TESTS OK");
        } else {
            System.out.println("RESULT FAIL PASS " + passed + " FAIL " + failed.size());
            for (String f : failed) {
                System.out.println("  failed: " + f);
            }
            System.exit(1);
        }
    }

    private static void signature() {
        checkEq("signature(null)=null", null, PerturbGate.signature(null));
        checkEq("signature(\"\")=null", null, PerturbGate.signature(""));
        checkEq("signature 原样返回", "mode=custom|L=1|R=2",
                PerturbGate.signature("mode=custom|L=1|R=2"));
    }

    private static void parseDirty() {
        checkEq("parse(null sig) = EMPTY", PerturbGate.State.EMPTY, PerturbGate.parse(null, 0, 0, 0, 0));
        checkEq("parse(\"\") = EMPTY", PerturbGate.State.EMPTY, PerturbGate.parse("", 1, 2, 3, 4));
        PerturbGate.State s = PerturbGate.parse("sig", -5, -7, -3, -9);
        check("parse 负数 until 归零", s.until == 0L);
        check("parse 负数 armedAt 归零", s.armedAt == 0L);
        check("parse 负数 count 归零", s.count == 0);
        check("parse 负数 since 归零", s.since == 0L);
        checkEq("parse 保存 sig", "sig", s.sig);
        PerturbGate.State ok = PerturbGate.parse("sig", T0 + 900, T0, 2, T0 - HOUR);
        check("parse 正常值原样", ok.until == T0 + 900 && ok.armedAt == T0
                && ok.count == 2 && ok.since == T0 - HOUR);
    }

    private static void phases() {
        checkEq("phase(EMPTY)=IDLE", PerturbGate.PHASE_IDLE,
                PerturbGate.phase(PerturbGate.State.EMPTY, T0));
        checkEq("phase(null)=IDLE", PerturbGate.PHASE_IDLE, PerturbGate.phase(null, T0));
        PerturbGate.State armed = PerturbGate.parse("sig", T0 + 900, T0, 1, T0);
        checkEq("phase 窗内=NATIVE", PerturbGate.PHASE_NATIVE, PerturbGate.phase(armed, T0 + 1));
        checkEq("phase 恰好到期=RESTORE", PerturbGate.PHASE_RESTORE,
                PerturbGate.phase(armed, T0 + 900));
        checkEq("phase 过期很久=RESTORE", PerturbGate.PHASE_RESTORE,
                PerturbGate.phase(armed, T0 + 10 * HOUR));
        checkEq("phase(until=0)=IDLE", PerturbGate.PHASE_IDLE,
                PerturbGate.phase(PerturbGate.expire(armed), T0 + 10 * HOUR));
    }

    private static void expireKeepsQuota() {
        PerturbGate.State armed = PerturbGate.parse("sig", T0 + 900, T0, 3, T0 - HOUR);
        PerturbGate.State e = PerturbGate.expire(armed);
        check("expire 清 until", e.until == 0L);
        check("expire 留 count", e.count == 3);
        check("expire 留 since", e.since == T0 - HOUR);
        checkEq("expire 留 sig", "sig", e.sig);
        checkEq("expire(null)=EMPTY", PerturbGate.State.EMPTY, PerturbGate.expire(null));
    }

    private static void sameSignature() {
        PerturbGate.State s = PerturbGate.parse("sig-a", 0L, T0, 1, T0);
        check("同签名 24h 内为真", PerturbGate.sameSignatureWithin(s, "sig-a", T0 + HOUR));
        check("同签名正好 24h 为假",
                !PerturbGate.sameSignatureWithin(s, "sig-a", T0 + 24 * HOUR));
        check("同签名 24h 多 1ms 为假",
                !PerturbGate.sameSignatureWithin(s, "sig-a", T0 + 24 * HOUR + 1));
        check("不同签名为假", !PerturbGate.sameSignatureWithin(s, "sig-b", T0 + HOUR));
        check("空状态为假", !PerturbGate.sameSignatureWithin(PerturbGate.State.EMPTY, "sig-a", T0));
        check("null 签名为假", !PerturbGate.sameSignatureWithin(s, null, T0));
    }

    private static void remaining() {
        checkEq("EMPTY 剩余 5", 5, PerturbGate.remaining(PerturbGate.State.EMPTY, T0));
        checkEq("since=0 剩余 5", 5,
                PerturbGate.remaining(PerturbGate.parse("s", 0L, T0, 3, 0L), T0));
        checkEq("12h 内已用 2 剩 3", 3,
                PerturbGate.remaining(PerturbGate.parse("s", 0L, T0, 2, T0 - HOUR), T0));
        checkEq("超过 12h 重置为 5", 5,
                PerturbGate.remaining(PerturbGate.parse("s", 0L, T0, 5, T0 - 13 * HOUR), T0));
        checkEq("已用 6 不为负，取 0", 0,
                PerturbGate.remaining(PerturbGate.parse("s", 0L, T0, 6, T0 - HOUR), T0));
    }

    private static void startGates() {
        PerturbGate.Decision off = PerturbGate.start(null, "sig-a", false, T0);
        check("feature off 不扰动", !off.serveNative);
        checkEq("feature off 原因", "skipped: feature off", off.reason);
        checkEq("feature off 状态不变", PerturbGate.State.EMPTY, off.state);

        PerturbGate.Decision noSig = PerturbGate.start(null, null, true, T0);
        check("空签名不扰动", !noSig.serveNative);
        checkEq("空签名原因", "skipped: no signature", noSig.reason);
        check("空字符串签名同样不扰动",
                !PerturbGate.start(null, "", true, T0).serveNative);

        PerturbGate.State armed = PerturbGate.parse("sig-a", T0 + 900, T0, 1, T0);
        PerturbGate.Decision busy = PerturbGate.start(armed, "sig-b", true, T0 + 100);
        check("窗内不再开窗", !busy.serveNative);
        checkEq("窗内原因", "skipped: window already active", busy.reason);
        checkEq("窗内状态引用原对象", armed, busy.state);

        PerturbGate.Decision same = PerturbGate.start(
                PerturbGate.parse("sig-a", 0L, T0, 1, T0), "sig-a", true, T0 + MIN);
        check("同签名 24h 内不扰动", !same.serveNative);
        checkEq("同签名原因", "skipped: same signature within 24h", same.reason);

        PerturbGate.Decision fresh = PerturbGate.start(null, "sig-a", true, T0);
        check("首轮扰动", fresh.serveNative);
        checkEq("首轮原因", "armed 1/5 window=900ms", fresh.reason);
        check("首轮 until=now+900", fresh.state.until == T0 + 900);
        check("首轮 armedAt=now", fresh.state.armedAt == T0);
        checkEq("首轮 count=1", 1, fresh.state.count);
        check("首轮 since=now", fresh.state.since == T0);
        checkEq("首轮签名落盘", "sig-a", fresh.state.sig);

        // 24h 之后同签名允许再扰动（保护期已过），计新的一轮
        PerturbGate.Decision later = PerturbGate.start(
                PerturbGate.parse("sig-a", 0L, T0, 1, T0), "sig-a", true, T0 + 25 * HOUR);
        check("24h 后同签名可再扰动", later.serveNative);
        checkEq("24h 后计数重置为 1", 1, later.state.count);
        check("24h 后 since 刷新", later.state.since == T0 + 25 * HOUR);
    }

    private static void idempotentAndQuota() {
        // 连续两次判定：第二次因为窗口生效而被挡（幂等，不会连开两个窗）
        PerturbGate.State s = PerturbGate.State.EMPTY;
        PerturbGate.Decision d1 = PerturbGate.start(s, "sig-1", true, T0);
        PerturbGate.Decision d2 = PerturbGate.start(d1.state, "sig-1", true, T0);
        check("连判两次只开一个窗", d1.serveNative && !d2.serveNative);
        checkEq("第二次被 'window already active' 挡", "skipped: window already active", d2.reason);

        // 窗口过期后同签名仍被 24h 保护
        PerturbGate.State expired = PerturbGate.expire(d1.state);
        PerturbGate.Decision d3 = PerturbGate.start(expired, "sig-1", true, T0 + 2 * MIN);
        check("过期后同签名不重复扰动", !d3.serveNative);
        checkEq("过期后原因仍是同签名", "skipped: same signature within 24h", d3.reason);

        // 换签名逐个用满配额：5 次之后第 6 个签名被挡
        PerturbGate.State acc = PerturbGate.State.EMPTY;
        long now = T0;
        int armed = 0;
        for (int i = 0; i < 5; i++) {
            PerturbGate.Decision d = PerturbGate.start(acc, "sig-" + i, true, now);
            if (d.serveNative) {
                armed++;
            }
            acc = PerturbGate.expire(d.state);
            now += 2 * MIN;
        }
        checkEq("12h 内配额正好用 5 次", 5, armed);
        checkEq("用满后 count=5", 5, acc.count);
        PerturbGate.Decision sixth = PerturbGate.start(acc, "sig-new", true, now);
        check("第 6 次被配额挡", !sixth.serveNative);
        checkEq("配额原因", "skipped: quota 5/5 in 12h", sixth.reason);

        // 配额窗口滑过 12h 后重新可用
        PerturbGate.Decision after = PerturbGate.start(acc, "sig-new", true, acc.since + 13 * HOUR);
        check("13h 后配额恢复", after.serveNative);
        checkEq("配额恢复后 count=1", 1, after.state.count);
        check("配额恢复后 since 刷新", after.state.since == acc.since + 13 * HOUR);

        // 脏状态不得让判定抛异常
        boolean threw = false;
        try {
            PerturbGate.State bad = new PerturbGate.State("x", -1L, -1L, -1, -1L);
            PerturbGate.start(bad, "x", true, T0);
            PerturbGate.phase(bad, T0);
            PerturbGate.expire(bad);
            PerturbGate.remaining(bad, T0);
            PerturbGate.sameSignatureWithin(bad, "x", T0);
        } catch (Throwable t) {
            threw = true;
            System.out.println("  threw: " + t);
        }
        check("脏状态全程不抛", !threw);

        // 同一时刻窗口边界：now == until 视为过期（RESTORE）
        PerturbGate.State edge = PerturbGate.parse("s", T0, T0, 1, T0);
        checkEq("now==until 进入 RESTORE", PerturbGate.PHASE_RESTORE,
                PerturbGate.phase(edge, T0));
    }
    /** 1.39 首轮真机 bug：native 态（on=false 提前 return）没记 sNativeTypes。 */
    private static void shouldNoteNative() {
        check("native+刚切换 → 记录", PerturbGate.shouldNoteNative(false, true, false));
        check("native+刚切换+已有 → 重记（原生内容变了）",
                PerturbGate.shouldNoteNative(false, true, true));
        check("native+没切换+还没有 → 记录（进程首查）",
                PerturbGate.shouldNoteNative(false, false, false));
        check("native+没切换+已有 → 不重复解析",
                !PerturbGate.shouldNoteNative(false, false, true));
        check("custom 态一律不记原生",
                !PerturbGate.shouldNoteNative(true, true, false)
                        && !PerturbGate.shouldNoteNative(true, true, true)
                        && !PerturbGate.shouldNoteNative(true, false, false));
    }
}