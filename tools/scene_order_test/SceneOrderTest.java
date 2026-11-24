package io.github.cechang256.xiaobu;

/** SceneOrder / 定序键对齐的离线断言（纯 JDK，不需要设备）。 */
public final class SceneOrderTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        policy();
        ordering();
        realign();
        System.out.println("RESULT " + (fail == 0 ? "OK" : "FAIL")
                + " PASS " + pass + " FAIL " + fail);
        if (fail != 0) {
            System.exit(1);
        }
    }

    private static void policy() {
        // 正确态：home 行本来就带 SUBSTITUTE → 原样保留
        eq("keep SUBSTITUTE", "SUBSTITUTE", SceneOrder.policyFor("SUBSTITUTE", false));
        // 保证卡槽带 DYNAMIC_SUBSTITUTE → 原样保留
        eq("keep DYNAMIC_SUBSTITUTE", "DYNAMIC_SUBSTITUTE",
                SceneOrder.policyFor("DYNAMIC_SUBSTITUTE", true));
        // 错误态：用户点确定后 home 行 policy 为空 → 补成槽位值
        eq("fill empty home", "SUBSTITUTE", SceneOrder.policyFor("", false));
        eq("fill null home", "SUBSTITUTE", SceneOrder.policyFor(null, false));
        eq("fill empty stock", "DYNAMIC_SUBSTITUTE", SceneOrder.policyFor("", true));
        eq("fill null stock", "DYNAMIC_SUBSTITUTE", SceneOrder.policyFor(null, true));
        // 宿主自己的其它策略标记不能被覆盖
        eq("keep TODAY_WEATHER_REMINDER", "TODAY_WEATHER_REMINDER",
                SceneOrder.policyFor("TODAY_WEATHER_REMINDER", false));
        eq("keep PERSISTENT_FOO", "PERSISTENT_FOO", SceneOrder.policyFor("PERSISTENT_FOO", true));
    }

    private static void ordering() {
        ok("left older keeps", !SceneOrder.needsSwap(100L, 200L));
        ok("left newer swaps", SceneOrder.needsSwap(200L, 100L));
        ok("equal swaps", SceneOrder.needsSwap(100L, 100L));
        eq("tie-break is strictly older", 99L, SceneOrder.leftWhenEqual(100L));
        ok("tie-break strictly smaller than right",
                SceneOrder.leftWhenEqual(100L) < 100L);
    }

    /**
     * 2026-11-23 16:04 的真实形态（设备 dump）：我们希望显示在**左**的是 col_0
     * 的天气时钟 536878390，它落在 home 行上、timestamp = 1795412619464（16:04，
     * 用户点确定后宿主新落的行，policy 空）；希望显示在**右**的是 col_1 的实时
     * 天气 536878709，它落在 097 行上、timestamp = 1795410812099（15:53，更旧）。
     * 也就是说左行比右行**新**，两条定序键都与"左"相反 → 桌面把 709 放到了
     * 左边（实测）。对齐后：左行变旧、右行变新，home 行补上槽位 policy。
     */
    private static void realign() {
        Row leftHome = new Row("536878390", 1795412619464L, 1795410000000L, "", 0);
        Row rightStock = new Row("536878709", 1795410812099L, 1795410000000L, "DYNAMIC_SUBSTITUTE", 1);
        ok("real case needs realign", SceneOrder.needsSwap(leftHome.ts, rightStock.ts));
        long newLeft = Math.min(leftHome.ts, rightStock.ts);
        long newRight = Math.max(leftHome.ts, rightStock.ts);
        ok("aligned left older", newLeft < newRight);
        eq("aligned left is the older value", 1795410812099L, newLeft);
        eq("aligned right is the newer value", 1795412619464L, newRight);
        eq("home row policy filled", "SUBSTITUTE", SceneOrder.policyFor(leftHome.policy, false));
        eq("stock row policy kept", "DYNAMIC_SUBSTITUTE",
                SceneOrder.policyFor(rightStock.policy, true));

        // 正确态（16:00）本来就是左旧右新 + policy 齐全 → 必须原样不动（幂等）
        Row okLeft = new Row("536878390", 1795400000000L, 1795400000000L, "SUBSTITUTE", 0);
        Row okRight = new Row("536878709", 1795410812099L, 1795410000000L, "DYNAMIC_SUBSTITUTE", 1);
        ok("correct state untouched", !SceneOrder.needsSwap(okLeft.ts, okRight.ts));
        eq("correct state policy untouched", "SUBSTITUTE",
                SceneOrder.policyFor(okLeft.policy, false));
    }

    private static final class Row {
        final String sid;
        final long ts;
        final long createTime;
        final String policy;
        final int ip;

        Row(String sid, long ts, long createTime, String policy, int ip) {
            this.sid = sid;
            this.ts = ts;
            this.createTime = createTime;
            this.policy = policy;
            this.ip = ip;
        }
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
        if (want == null ? got == null : want.equals(got)) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL " + what + " want=" + want + " got=" + got);
        }
    }
}
