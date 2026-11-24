package io.github.cechang256.xiaobu;

/**
 * 离线断言：1.41 的两条修复面。
 *
 * <p>A（预览）：每一栏的动作判定，覆盖"配置没写"、"卡池里有"、"卡池里没有 → 按配置重建"
 * 以及"实体还没建出来"这条回退分支。
 * <p>B（切模式重排）：同一 pairKey 只跑一次、features.compose_realign=false 时一次都不跑。
 */
public final class PreviewPlanTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        // ---- A: PreviewPlan.action ----------------------------------------
        eq("entity missing -> skip", PreviewPlan.SKIP,
                PreviewPlan.action(false, true, "1", null, false));
        eq("col not configured -> skip", PreviewPlan.SKIP,
                PreviewPlan.action(true, false, null, "1", true));
        eq("empty want -> skip", PreviewPlan.SKIP,
                PreviewPlan.action(true, true, "", "1", true));
        eq("same sid -> keep", PreviewPlan.KEEP,
                PreviewPlan.action(true, true, "536878390", "536878390", false));
        eq("same sid with candidate -> keep", PreviewPlan.KEEP,
                PreviewPlan.action(true, true, "536878390", "536878390", true));
        eq("different sid + candidate -> write", PreviewPlan.WRITE,
                PreviewPlan.action(true, true, "536878390", "536878709", true));
        eq("right column missing from pool -> rebuild", PreviewPlan.REBUILD,
                PreviewPlan.action(true, true, "536878193", "536875395", false));
        eq("stale bean, no candidate -> rebuild", PreviewPlan.REBUILD,
                PreviewPlan.action(true, true, "536878709", null, false));
        eq("blank slot, no candidate -> rebuild", PreviewPlan.REBUILD,
                PreviewPlan.action(true, true, "536878390", "", false));

        eq("writes(write)", true, PreviewPlan.writes(PreviewPlan.WRITE));
        eq("writes(rebuild)", true, PreviewPlan.writes(PreviewPlan.REBUILD));
        eq("writes(keep)", false, PreviewPlan.writes(PreviewPlan.KEEP));
        eq("writes(skip)", false, PreviewPlan.writes(PreviewPlan.SKIP));
        eq("label(rebuild)", "rebuild", PreviewPlan.label(PreviewPlan.REBUILD));
        eq("label(skip)", "skip", PreviewPlan.label(PreviewPlan.SKIP));

        // 真实故障复现：配置 390/193，槽位残留 709/395（上一次会话），卡池里都找不到。
        // 旧实现两栏都是 continue → 预览显示 实时天气+月历；新实现两栏都 rebuild。
        eq("incident col0", PreviewPlan.REBUILD,
                PreviewPlan.action(true, true, "536878390", "536878709", false));
        eq("incident col1", PreviewPlan.REBUILD,
                PreviewPlan.action(true, true, "536878193", "536875395", false));

        // ---- B: RealignGate ----------------------------------------------
        eq("feature off -> never run", false,
                RealignGate.shouldRun(false, "on|custom|mode|390|193", null));
        eq("feature off, same pair -> no", false,
                RealignGate.shouldRun(false, "sig", "sig"));
        eq("first switch -> run", true,
                RealignGate.shouldRun(true, "on|custom|mode|390|193", null));
        eq("same pair again -> skip", false,
                RealignGate.shouldRun(true, "on|custom|mode|390|193", "on|custom|mode|390|193"));
        eq("pair changed -> run", true,
                RealignGate.shouldRun(true, "on|custom|mode|390|193", "on|custom|mode|390|709"));
        eq("null pair -> no", false,
                RealignGate.shouldRun(true, null, null));
        eq("reason feature off", true,
                RealignGate.reason(false, "sig", null).startsWith("off (features.compose_realign=false)"));
        eq("reason same pair", true,
                RealignGate.reason(true, "sig", "sig").startsWith("skip: same pair"));
        eq("reason run", true,
                RealignGate.reason(true, "sig", null).startsWith("run (sig)"));

        System.out.println("RESULT " + (fail == 0 ? "OK" : "FAIL")
                + " PASS " + pass + " FAIL " + fail);
        if (fail != 0) {
            System.exit(1);
        }
    }

    private static void eq(String what, Object expect, Object actual) {
        boolean ok = expect == null ? actual == null : expect.equals(actual);
        if (ok) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL " + what + " expected=" + expect + " actual=" + actual);
        }
    }

    private PreviewPlanTest() {
    }
}
