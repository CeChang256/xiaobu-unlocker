package io.github.cechang256.xiaobu;

/**
 * NotifyPlan 的离线断言（纯 JDK，不需要设备 / 不需要 android.jar）。
 *
 * <p>锁住三件在设备上很难验证、但一错就整轮白跑的事：
 * 1) {@code /ai_launcher_services} 在通知列表里（桌面卡组真正观察的 URI）；
 * 2) 没有重复 URI、没有漏掉 base URI（不同入口注册在不同 URI 上）；
 * 3) 节流与补发时序的边界值。
 */
public final class NotifyPlanTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        uris();
        throttle();
        retries();
        System.out.println("RESULT " + (fail == 0 ? "OK" : "FAIL")
                + " PASS " + pass + " FAIL " + fail);
        if (fail != 0) {
            System.exit(1);
        }
    }

    private static void uris() {
        // 桌面双栏卡组的观察者注册在这个子路径上（反编译证据见 NotifyPlan 注释）
        ok("has ai_launcher_services",
                NotifyPlan.has("content://com.oplus.pantanal.ums.scenedecision/ai_launcher_services"));
        ok("has scene base",
                NotifyPlan.has("content://com.oplus.pantanal.ums.scenedecision"));
        ok("has scene_all_data",
                NotifyPlan.has("content://com.oplus.pantanal.ums.scenedecision/scene_all_data"));
        ok("has decision base",
                NotifyPlan.has("content://com.oplus.pantanal.ums.decision"));
        // 不应含无关 provider
        ok("no appplatform", !NotifyPlan.has(
                "content://com.oplus.appplatform.settings/secure/group_card_user_selected_config"));
        // 无重复
        boolean dup = false;
        for (int i = 0; i < NotifyPlan.URIS.length; i++) {
            for (int j = i + 1; j < NotifyPlan.URIS.length; j++) {
                if (NotifyPlan.URIS[i].equals(NotifyPlan.URIS[j])) {
                    dup = true;
                }
            }
        }
        ok("no duplicate uris", !dup);
        ok("uri count >= 6", NotifyPlan.URIS.length >= 6);
        // 每个 URI 都是 content:// 且指向 UMS 或 appplatform 之外的宿主
        boolean shape = true;
        for (int i = 0; i < NotifyPlan.URIS.length; i++) {
            String u = NotifyPlan.URIS[i];
            if (!u.startsWith("content://com.oplus.pantanal.ums.")) {
                shape = false;
            }
        }
        ok("all uris point at ums providers", shape);
    }

    private static void throttle() {
        ok("first ever allowed", NotifyPlan.allow(1000L, 0L));
        ok("negative last allowed", NotifyPlan.allow(1000L, -5L));
        ok("clock went backwards allowed", NotifyPlan.allow(1000L, 5000L));
        ok("just now blocked", !NotifyPlan.allow(1000L, 1000L));
        ok("half window blocked",
                !NotifyPlan.allow(1000L + NotifyPlan.RATE_MS / 2, 1000L));
        ok("exactly window allowed",
                NotifyPlan.allow(1000L + NotifyPlan.RATE_MS, 1000L));
        ok("much later allowed",
                NotifyPlan.allow(1000L + NotifyPlan.RATE_MS * 10, 1000L));
    }

    private static void retries() {
        long[] d = NotifyPlan.RETRY_MS;
        ok("has retries", d.length >= 2);
        ok("first is immediate", d[0] == 0L);
        boolean ascending = true;
        for (int i = 1; i < d.length; i++) {
            if (d[i] <= d[i - 1]) {
                ascending = false;
            }
        }
        ok("delays ascending", ascending);
        ok("span under 2s", d[d.length - 1] < 2000L);
    }

    private static void ok(String name, boolean cond) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL " + name);
        }
    }
}
