package io.github.cechang256.xiaobu;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 往一行的 cardConfig 里补 isUserSelected 标记（1.38）。
 *
 * <p>宿主侧的 cardConfig 列是一个 JSON 数组，每个元素形如
 * {@code {"size":1,"type":"<serviceId>","full_card_info":"<CardConfig JSON>"}}
 * （UMS SceneDecisionProvider.filterNotSupportCardEntry 就是按这个形状重建的）。
 * GCP 的 {@code sa/p.s(ServiceInfo)} 在 {@code g0.j()} 分支会取“第一个支持尺寸的
 * 那份 config 字符串”再 {@code optBoolean("isUserSelected")}；但那份字符串到底是
 * 整个元素还是元素里的 {@code full_card_info}，取决于 SDK 侧的映射实现，离线无法
 * 确证。两处同时写上即可覆盖两种实现——多余的键对 JSON 解析方无害（Gson 忽略未知
 * 字段）。
 *
 * <p>契约：<b>永不抛异常</b>。字符串不是合法 JSON 数组、或元素不是对象时，按
 * “重建一份最小可用结构”处理，绝不把坏数据再写回去。
 *
 * <p>纯 JDK + org.json，无 android 依赖，便于 JVM 离线测试。
 */
final class CardConfigStamp {

    /** 标记键名（GCP 读的就是它）。 */
    static final String KEY_SELECTED = "isUserSelected";
    /** 时间戳键名：GCP 的 sa/p.f() 用它判“最近一次用户选择”。 */
    static final String KEY_TS = "userSelectedTimestamp";
    /** 元素里承载真卡配置的字段名。 */
    static final String KEY_FULL = "full_card_info";
    /** 元素里的尺寸字段名。 */
    static final String KEY_SIZE = "size";
    /** 元素里的类型字段名（宿主这里放的是 serviceId）。 */
    static final String KEY_TYPE = "type";

    private CardConfigStamp() {
    }

    /**
     * 补标记。
     *
     * @param raw  原 cardConfig 字符串（可为 null / 空 / 坏 JSON）
     * @param sid  该行 serviceId，重建结构时用作元素的 type
     * @param size 该行 supportSize（解析不出时回落 1）
     * @param now  时间戳，写进 userSelectedTimestamp
     * @return 新的 cardConfig 字符串；极端情况下连 JSON 都造不出来时返回 null（调用方保持原值）
     */
    static String stamp(String raw, String sid, int size, long now) {
        JSONArray arr = parseArray(raw);
        if (arr == null) {
            arr = rebuild(sid, size);
            if (arr == null) {
                return null;
            }
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject el = arr.optJSONObject(i);
            if (el == null) {
                continue;
            }
            putQuietly(el, KEY_SELECTED, Boolean.TRUE);
            putQuietly(el, KEY_TS, Long.valueOf(now));
            String full = el.optString(KEY_FULL, null);
            if (full == null || full.length() == 0) {
                continue;
            }
            JSONObject inner = parseObject(full);
            if (inner == null) {
                inner = new JSONObject();
            }
            putQuietly(inner, KEY_SELECTED, Boolean.TRUE);
            putQuietly(inner, KEY_TS, Long.valueOf(now));
            putQuietly(el, KEY_FULL, inner.toString());
        }
        try {
            return arr.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 已经是合法数组就原样返回（不复制），否则 null。 */
    private static JSONArray parseArray(String raw) {
        if (raw == null || raw.length() == 0) {
            return null;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            return arr.length() == 0 ? null : arr;
        } catch (Throwable t) {
            return null;
        }
    }

    private static JSONObject parseObject(String raw) {
        try {
            return new JSONObject(raw);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 坏数据兜底：造一个结构最小的数组，确保下游仍能取到一份 config。 */
    private static JSONArray rebuild(String sid, int size) {
        try {
            JSONObject el = new JSONObject();
            el.put(KEY_SIZE, size > 0 ? size : 1);
            el.put(KEY_TYPE, sid == null ? "" : sid);
            el.put(KEY_FULL, new JSONObject().toString());
            JSONArray arr = new JSONArray();
            arr.put(el);
            return arr;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void putQuietly(JSONObject o, String k, Object v) {
        try {
            o.put(k, v);
        } catch (Throwable ignored) {
        }
    }
}
