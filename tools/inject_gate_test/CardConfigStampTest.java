package io.github.cechang256.xiaobu;

import org.json.JSONArray;
import org.json.JSONObject;

/** CardConfigStamp / cardConfig 补标记的离线断言（纯 JDK + org.json）。 */
public final class CardConfigStampTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        normal();
        badInput();
        oddShapes();
        idempotent();
        System.out.println("RESULT " + (fail == 0 ? "OK" : "FAIL")
                + " PASS " + pass + " FAIL " + fail);
        if (fail != 0) {
            System.exit(1);
        }
    }

    private static void normal() {
        String full = "{\"type\":10000492,\"name\":\"实时天气\",\"size\":1,\"displayArea\":3}";
        JSONObject el = new JSONObject();
        el.put("size", 1);
        el.put("type", "536878709");
        el.put("full_card_info", full);
        JSONArray arr = new JSONArray();
        arr.put(el);

        String out = CardConfigStamp.stamp(arr.toString(), "536878709", 1, 777L);
        ok("normal returns something", out != null);
        JSONArray got = new JSONArray(out);
        eq("normal element count", 1, got.length());
        JSONObject g0 = got.getJSONObject(0);
        ok("element flagged", g0.optBoolean("isUserSelected"));
        eq("element ts", 777L, g0.optLong("userSelectedTimestamp"));
        eq("element keeps size", 1, g0.optInt("size"));
        eq("element keeps type", "536878709", g0.optString("type"));

        JSONObject inner = new JSONObject(g0.optString("full_card_info"));
        ok("inner flagged", inner.optBoolean("isUserSelected"));
        eq("inner ts", 777L, inner.optLong("userSelectedTimestamp"));
        eq("inner keeps name", "实时天气", inner.optString("name"));
        eq("inner keeps type", 10000492, inner.optInt("type"));
        eq("inner keeps displayArea", 3, inner.optInt("displayArea"));
    }

    private static void badInput() {
        String[] bad = new String[]{"not json", "{oops", "[]", ""};
        for (String raw : bad) {
            String out = null;
            boolean threw = false;
            try {
                out = CardConfigStamp.stamp(raw, "536878390", 2, 1234L);
            } catch (Throwable t) {
                threw = true;
            }
            ok("bad input does not throw [" + raw + "]", !threw);
            ok("bad input rebuilds [" + raw + "]", out != null);
            if (out == null) {
                continue;
            }
            JSONArray got = new JSONArray(out);
            eq("rebuild count [" + raw + "]", 1, got.length());
            JSONObject g0 = got.getJSONObject(0);
            eq("rebuild sid [" + raw + "]", "536878390", g0.optString("type"));
            eq("rebuild size [" + raw + "]", 2, g0.optInt("size"));
            ok("rebuild flagged [" + raw + "]", g0.optBoolean("isUserSelected"));
            ok("rebuild inner flagged [" + raw + "]",
                    new JSONObject(g0.optString("full_card_info")).optBoolean("isUserSelected"));
        }
        // null 与“坏 JSON”同路：重建，不返回原值
        String outNull = CardConfigStamp.stamp(null, "536871060", 0, 5L);
        ok("null rebuilds", outNull != null);
        JSONObject n0 = new JSONArray(outNull).getJSONObject(0);
        eq("null size falls back to 1", 1, n0.optInt("size"));
        ok("null flagged", n0.optBoolean("isUserSelected"));
    }

    private static void oddShapes() {
        // 元素缺 full_card_info：只打元素级标记，不抛
        JSONArray arr = new JSONArray();
        JSONObject el = new JSONObject();
        el.put("size", 1);
        arr.put(el);
        String out = CardConfigStamp.stamp(arr.toString(), "sid", 1, 9L);
        ok("missing full no throw", out != null);
        ok("missing full still flagged",
                new JSONArray(out).getJSONObject(0).optBoolean("isUserSelected"));

        // 元素里的 full_card_info 本身是坏 JSON：替换成最小对象，不抛
        JSONArray arr2 = new JSONArray();
        JSONObject el2 = new JSONObject();
        el2.put("full_card_info", "{oops");
        arr2.put(el2);
        String out2 = CardConfigStamp.stamp(arr2.toString(), "sid", 1, 11L);
        ok("bad inner no throw", out2 != null);
        JSONObject g2 = new JSONArray(out2).getJSONObject(0);
        ok("bad inner replaced and flagged",
                new JSONObject(g2.optString("full_card_info")).optBoolean("isUserSelected"));

        // 非对象元素（字符串/数字）被跳过，其余元素照常
        JSONArray arr3 = new JSONArray();
        arr3.put("junk");
        arr3.put(new JSONObject().put("size", 1));
        String out3 = null;
        boolean threw = false;
        try {
            out3 = CardConfigStamp.stamp(arr3.toString(), "sid", 1, 12L);
        } catch (Throwable t) {
            threw = true;
        }
        ok("non-object element no throw", !threw);
        ok("non-object element keeps count", out3 != null && new JSONArray(out3).length() == 2);
        ok("object element still flagged", new JSONArray(out3).getJSONObject(1)
                .optBoolean("isUserSelected"));

        // 多元素（多尺寸）时每个都要打上
        JSONArray arr4 = new JSONArray();
        arr4.put(new JSONObject().put("size", 1).put("full_card_info", "{}"));
        arr4.put(new JSONObject().put("size", 2).put("full_card_info", "{}"));
        String out4 = CardConfigStamp.stamp(arr4.toString(), "sid", 1, 13L);
        JSONArray g4 = new JSONArray(out4);
        eq("two entries kept", 2, g4.length());
        ok("entry0 flagged", g4.getJSONObject(0).optBoolean("isUserSelected"));
        ok("entry1 flagged", g4.getJSONObject(1).optBoolean("isUserSelected"));
    }

    private static void idempotent() {
        JSONObject src = new JSONObject();
        src.put("size", 1);
        src.put("type", "sid");
        src.put("full_card_info", new JSONObject().put("name", "x").toString());
        JSONArray srcArr = new JSONArray();
        srcArr.put(src);
        String raw = srcArr.toString();
        String once = CardConfigStamp.stamp(raw, "sid", 1, 100L);
        String twice = CardConfigStamp.stamp(once, "sid", 1, 200L);
        ok("idempotent valid", twice != null);
        JSONObject g = new JSONArray(twice).getJSONObject(0);
        ok("idempotent still flagged", g.optBoolean("isUserSelected"));
        eq("idempotent keeps name", "x", new JSONObject(g.optString("full_card_info"))
                .optString("name"));
        eq("idempotent refreshes ts", 200L, g.optLong("userSelectedTimestamp"));
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
