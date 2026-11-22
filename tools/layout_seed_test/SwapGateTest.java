package io.github.cechang256.xiaobu;

/**
 * SwapGate 离线单测（1.35）：闸门 = 显式 features.dswap 优先，缺键看 mode。
 *
 * <p>这里只测纯判据；mode 的计算（含"没写过 mode 但有 col_1 → custom"的旧配置
 * 兼容、往返标记过期按 custom 自愈）在 LayoutStore.mode()，属 Android 侧，
 * 见 review/2026-11-22/1.35_交付说明_2026-11-22.md 的代码走查记录。
 */
public final class SwapGateTest {

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

    private static boolean gate(String raw, String mode) {
        return SwapGate.on(SwapGate.explicit(raw), mode);
    }

    public static void main(String[] args) throws Exception {
        final String NO_FEATURES = "{\"tab_0\":{\"col_0\":{\"serviceId\":\"536878390\"},"
                + "\"col_1\":{\"serviceId\":\"536870911\"}},\"mode\":\"custom\"}";
        final String NATIVE = "{\"tab_0\":{},\"mode\":\"native\"}";

        // 验收矩阵 1-4
        check("1 no features + custom -> on", gate(NO_FEATURES, "custom"), true);
        check("1 source is mode", SwapGate.explicit(NO_FEATURES), null);
        check("2 no features + native -> off", gate(NATIVE, "native"), false);
        check("3 dswap=false + custom -> off(explicit)",
                gate("{\"mode\":\"custom\",\"features\":{\"dswap\":false}}", "custom"), false);
        check("3 source is explicit",
                SwapGate.explicit("{\"features\":{\"dswap\":false}}"), Boolean.FALSE);
        check("4 dswap=true + native -> on(explicit)",
                gate("{\"mode\":\"native\",\"features\":{\"dswap\":true}}", "native"), true);

        // 显式键覆盖不因 mode 改变
        check("5 dswap=true + custom -> on", gate("{\"features\":{\"dswap\":true}}", "custom"), true);
        check("6 dswap=false + native -> off", gate("{\"features\":{\"dswap\":false}}", "native"), false);

        // 缺键 = 没配过：三种"没有 features"的形态
        check("7 features {} -> null", SwapGate.explicit("{\"features\":{}}"), null);
        check("8 features {} + custom -> on", gate("{\"features\":{}}", "custom"), true);
        check("9 no features key at all -> null", SwapGate.explicit("{\"mode\":\"custom\"}"), null);
        check("10 empty string -> null", SwapGate.explicit(""), null);
        check("11 null -> null", SwapGate.explicit(null), null);
        check("12 broken json -> null", SwapGate.explicit("{not json"), null);

        // 真值规则与 1.33 的 optBoolean 一致：非布尔值 = false
        check("13 dswap=\"junk\" -> explicit false",
                SwapGate.explicit("{\"features\":{\"dswap\":\"junk\"}}"), Boolean.FALSE);
        check("14 dswap=0 -> explicit false",
                SwapGate.explicit("{\"features\":{\"dswap\":0}}"), Boolean.FALSE);
        // org.json 的 optBoolean 只认真正的布尔值：数字 1/0 一律回落 def。
        // 1.33 的 feature() 就是这个行为，闸门必须与之完全一致（配置里请写 true/false）。
        check("15 dswap=1 -> explicit false (optBoolean 语义)",
                SwapGate.explicit("{\"features\":{\"dswap\":1}}"), Boolean.FALSE);

        // mode 前缀语义与 LayoutStore.isCustomMode 一致
        check("16 mode=null -> off by mode", gate(null, null), false);
        check("17 mode=\"\" -> off by mode", gate(null, ""), false);
        check("18 mode=custom2 -> on (prefix)", gate(null, "custom2"), true);
        check("19 isCustom(\"native\")", SwapGate.isCustom("native"), false);

        // diag 标注
        check("20 label on(mode)", SwapGate.label(true, false), "on(mode)");
        check("21 label off(explicit)", SwapGate.label(false, true), "off(explicit)");
        check("22 label on(explicit)", SwapGate.label(true, true), "on(explicit)");
        check("23 label off(mode)", SwapGate.label(false, false), "off(mode)");

        System.out.println("RESULT " + (fail == 0 ? "OK" : "FAILED")
                + " PASS " + pass + " FAIL " + fail);
        if (fail != 0) {
            System.exit(1);
        }
    }
}
