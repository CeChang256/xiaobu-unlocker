package io.github.cechang256.xiaobu;

import org.json.JSONObject;

/**
 * LayoutSeed 离线单测（1.35：不再补种 dswap）。
 *
 * <p>1.34 的用例断言"缺键补 dswap=true"；1.35 闸门改由 mode 驱动，补种
 * dswap 已多余且有害（会把实时选择冻成快照），所以这里反过来断言
 * "seed 绝不新增 dswap 键，已存在的 dswap 原样保留"。
 */
public final class LayoutSeedTest {

    private static int pass = 0;
    private static int fail = 0;

    private static void check(String name, Object got, Object want) {
        boolean ok = got == null ? want == null : got.equals(want);
        if (ok) {
            pass++;
            System.out.println("PASS " + name);
        } else {
            fail++;
            System.out.println("FAIL " + name + " got=" + got + " want=" + want);
        }
    }

    private static JSONObject f(JSONObject o) {
        return o.getJSONObject("features");
    }

    public static void main(String[] args) throws Exception {
        // case1：设备备份原样（有 tab_0 + mode=custom，无 features）
        JSONObject a = new JSONObject("{\"tab_0\":{\"col_0\":{\"serviceId\":\"536878390\"},"
                + "\"col_1\":{\"serviceId\":\"536870911\"}},\"mode\":\"custom\"}");
        check("case1 changed", LayoutSeed.seed(a), true);
        check("case1 model", f(a).getBoolean("model"), true);
        check("case1 custom_tab", f(a).getBoolean("custom_tab"), true);
        check("case1 不补 dswap", f(a).has("dswap"), false);
        check("case1 mode 保留", a.getString("mode"), "custom");
        check("case1 二次 seed 幂等", LayoutSeed.seed(a), false);

        // case2：features {} → 只补两项
        JSONObject b = new JSONObject("{\"features\":{}}");
        check("case2 changed", LayoutSeed.seed(b), true);
        check("case2 只有 model/custom_tab", f(b).length(), 2);
        check("case2 不补 dswap", f(b).has("dswap"), false);

        // case3：显式 dswap=false 原样保留（不删不改）
        JSONObject c = new JSONObject("{\"mode\":\"custom\",\"features\":{\"dswap\":false}}");
        check("case3 changed(补两项)", LayoutSeed.seed(c), true);
        check("case3 dswap 仍为 false", f(c).getBoolean("dswap"), false);
        check("case3 dswap 键还在", f(c).has("dswap"), true);

        // case4：1.34 已写下的 dswap=true 同样不动
        JSONObject d = new JSONObject("{\"features\":{\"dswap\":true}}");
        check("case4 changed(补 custom_tab)", LayoutSeed.seed(d), true);
        check("case4 dswap=true 保留", f(d).getBoolean("dswap"), true);
        check("case4 model=true 补上", f(d).getBoolean("model"), true);

        // case5：model:false 是用户选择，不覆盖
        JSONObject e = new JSONObject("{\"features\":{\"model\":false,\"custom_tab\":false}}");
        check("case5 无改动", LayoutSeed.seed(e), false);
        check("case5 model=false 保留", f(e).getBoolean("model"), false);
        check("case5 custom_tab=false 保留", f(e).getBoolean("custom_tab"), false);
        check("case5 不补 dswap", f(e).has("dswap"), false);

        // case6：features 类型损坏 → 重建，只补两项
        JSONObject g = new JSONObject("{\"features\":\"oops\"}");
        check("case6 changed", LayoutSeed.seed(g), true);
        check("case6 重建后两项", f(g).length(), 2);
        check("case6 不补 dswap", f(g).has("dswap"), false);

        // case7：空对象 = 还没配过，不动
        JSONObject h = new JSONObject("{}");
        check("case7 空对象不补", LayoutSeed.seed(h), false);
        check("case7 无 features", h.has("features"), false);

        // case8：null
        check("case8 null 不补", LayoutSeed.seed(null), false);

        System.out.println("RESULT " + (fail == 0 ? "OK" : "FAILED")
                + " PASS " + pass + " FAIL " + fail);
        if (fail != 0) {
            System.exit(1);
        }
    }
}
