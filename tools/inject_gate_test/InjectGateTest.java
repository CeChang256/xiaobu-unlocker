package io.github.cechang256.xiaobu;

/** InjectGate / 注入闸门的离线断言（纯 JDK，不需要设备）。 */
public final class InjectGateTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        armAndCount();
        sigChange();
        featureAndMax();
        parse();
        signature();
        System.out.println("RESULT " + (fail == 0 ? "OK" : "FAIL")
                + " PASS " + pass + " FAIL " + fail);
        if (fail != 0) {
            System.exit(1);
        }
    }

    private static void armAndCount() {
        String sig = InjectGate.signature("custom", "on|custom|mode|536878390|536878709");
        InjectGate.Decision d1 = InjectGate.next(null, sig, true, 0, 1000L);
        ok("first arms", d1.inject);
        eq("first count", 1, d1.state.count);
        eq("first lastAt", 1000L, d1.state.lastAt);
        ok("first reason armed", d1.reason.startsWith("armed"));

        InjectGate.Decision d2 = InjectGate.next(d1.state, sig, true, 0, 2000L);
        ok("second injects", d2.inject);
        eq("second count", 2, d2.state.count);

        InjectGate.Decision d3 = InjectGate.next(d2.state, sig, true, 0, 3000L);
        ok("third injects", d3.inject);
        eq("third count", 3, d3.state.count);

        InjectGate.Decision d4 = InjectGate.next(d3.state, sig, true, 0, 4000L);
        ok("fourth stops", !d4.inject);
        ok("fourth says done", d4.reason.startsWith("done"));
        eq("fourth keeps count", 3, d4.state.count);
        eq("fourth keeps sig", sig, d4.state.sig);
        eq("limit constant", 3, InjectGate.LIMIT);
    }

    private static void sigChange() {
        String a = InjectGate.signature("custom", "pairA");
        String b = InjectGate.signature("custom", "pairB");
        InjectGate.State armed = new InjectGate.State(a, 3, 100L);
        InjectGate.Decision d = InjectGate.next(armed, b, true, 0, 200L);
        ok("sig change re-arms", d.inject);
        eq("sig change resets count", 1, d.state.count);
        eq("sig change stores new sig", b, d.state.sig);

        InjectGate.Decision same = InjectGate.next(armed, a, true, 0, 300L);
        ok("same exhausted sig stays stopped", !same.inject);
        eq("same exhausted count kept", 3, same.state.count);
    }

    private static void featureAndMax() {
        String sig = InjectGate.signature("custom", "p");
        InjectGate.State armed = new InjectGate.State(sig, 0, 0L);
        InjectGate.Decision off = InjectGate.next(armed, sig, false, 0, 10L);
        ok("feature off no inject", !off.inject);
        eq("feature off reason", "feature off", off.reason);
        eq("feature off keeps state", 0, off.state.count);

        InjectGate.Decision cap1 = InjectGate.next(armed, sig, true, 1, 20L);
        ok("max1 injects", cap1.inject);
        InjectGate.Decision cap1b = InjectGate.next(cap1.state, sig, true, 1, 30L);
        ok("max1 stops at 1", !cap1b.inject);

        InjectGate.Decision negMax = InjectGate.next(armed, sig, true, -5, 40L);
        ok("non-positive max falls back to LIMIT", negMax.inject);

        InjectGate.Decision noSig = InjectGate.next(armed, null, true, 0, 50L);
        ok("null sig no inject", !noSig.inject);
        eq("null sig reason", "no signature", noSig.reason);
    }

    private static void parse() {
        eq("parse missing sig is EMPTY", null, InjectGate.parse(null, 3, 5L).sig);
        eq("parse empty sig is EMPTY", null, InjectGate.parse("", 3, 5L).sig);
        eq("parse count", 2, InjectGate.parse("s", 2, 5L).count);
        eq("parse negative count clamps", 0, InjectGate.parse("s", -1, 5L).count);
        eq("parse keeps lastAt", 5L, InjectGate.parse("s", 2, 5L).lastAt);
        // 进程重启后读到“已用尽”的状态，不应重新武装
        InjectGate.Decision restart = InjectGate.next(
                InjectGate.parse("custom|p", 3, 1L), "custom|p", true, 0, 99L);
        ok("restart does not re-inject", !restart.inject);
    }

    private static void signature() {
        eq("empty signature is null", null, InjectGate.signature(null, null));
        eq("empty strings null", null, InjectGate.signature("", ""));
        eq("signature joins", "custom|on|custom|mode|A|B",
                InjectGate.signature("custom", "on|custom|mode|A|B"));
        ok("signature differs by pair", !InjectGate.signature("custom", "A")
                .equals(InjectGate.signature("custom", "B")));
        ok("signature differs by mode", !InjectGate.signature("custom", "A")
                .equals(InjectGate.signature("native", "A")));
    }

    private static void ok(String what, boolean cond) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL " + what);
        }
    }

    private static void eq(String what, Object want, Object got) {
        boolean same = want == null ? got == null : want.equals(got);
        if (same) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL " + what + " want=" + want + " got=" + got);
        }
    }
}
