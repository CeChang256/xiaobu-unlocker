package io.github.cechang256.xiaobu;

/**
 * "让 launcher 重新拉一次卡序"的纯数据部分（round 56）。
 *
 * <p>为什么要通知：2026-11-23 的对照实验里，只 force-stop
 * {@code com.android.launcher}（UMS 进程 pid 不变）桌面立刻回正，而
 * 重启 UMS 后桌面仍反——说明桌面把卡序**缓存在 launcher 进程内**，
 * UMS 侧后续怎么改都不影响已缓存的那一份；反过来说，只要能让 launcher
 * 重拉一次，改写结果就会立刻上台。
 *
 * <p>通知哪个 URI：launcher 17.5.4 的静态 SDK 在注册 ContentObserver 时
 * 按入口分支（反编译 {@code com/pantanal/server/content/sdk/a.java:105-127}）：
 * 入口 {@code AI_LAUNCHER}=524288 注册的是
 * {@code content://com.oplus.pantanal.ums.scenedecision/ai_launcher_services}
 * （{@code SceneDecisionDao$Companion$AI_LAUNCHER_SCENE_DECISION_URI$2:11}），
 * 其它入口注册的是 base URI（{@code SCENE_DECISION_URI$2:11}）。
 * 观察者收到 onChange 后会走
 * {@code SceneDecisionCenter onChange()}（反编译 {@code defpackage/cn9.java:8-20}）
 * 重新查询并重建卡组。
 *
 * <p>旧实现（EditorHook.notifyDecisionRefresh，round 11 引入）通知了 5 个 URI，
 * 唯独**没有** {@code /ai_launcher_services}，所以"点确定后桌面不动"。
 */
final class NotifyPlan {

    /** 场景 provider 的 base URI（非 AI launcher 入口注册在这上面）。 */
    static final String URI_SCENE = "content://com.oplus.pantanal.ums.scenedecision";

    /** AI launcher 入口（524288）注册的 URI——桌面双栏卡组走的就是它。 */
    static final String URI_AI_LAUNCHER = URI_SCENE + "/ai_launcher_services";

    /** 其它已知子路径，历史上出现过观察者/查询。 */
    static final String URI_SCENE_SERVICES = URI_SCENE + "/sceneservices";
    static final String URI_SCENE_ALL_DATA = URI_SCENE + "/scene_all_data";

    /** 决策 provider（应用建议/保证卡列表）。 */
    static final String URI_DECISION = "content://com.oplus.pantanal.ums.decision";
    static final String URI_DECISION_SERVICES = URI_DECISION + "/services";

    /** 一次全量通知覆盖的 URI。顺序无关，去重由本数组保证。 */
    static final String[] URIS = {
            URI_AI_LAUNCHER,
            URI_SCENE,
            URI_SCENE_SERVICES,
            URI_SCENE_ALL_DATA,
            URI_DECISION,
            URI_DECISION_SERVICES,
    };

    /**
     * 两次通知之间的最小间隔。launcher 收到通知会重新查询 provider，
     * 而查询又会进我们的改写 hook；没有节流就是 通知→查询→通知 的死循环。
     */
    static final long RATE_MS = 1200L;

    /** 补发时机（毫秒，相对第一次通知）。第 0 项是主通知，其余是重试。 */
    static final long[] RETRY_MS = {0L, 250L, 900L};

    private NotifyPlan() {
    }

    /** 距上次通知够久了吗。 */
    static boolean allow(long now, long last) {
        if (last <= 0L) {
            return true;
        }
        if (now < last) {
            return true;
        }
        return now - last >= RATE_MS;
    }

    /** URIS 里是否含某个 URI（离线测试用）。 */
    static boolean has(String uri) {
        for (int i = 0; i < URIS.length; i++) {
            if (URIS[i].equals(uri)) {
                return true;
            }
        }
        return false;
    }
}
