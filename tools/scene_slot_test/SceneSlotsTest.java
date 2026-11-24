package io.github.cechang256.xiaobu;

/**
 * SceneSlots 离线单测（1.37）：左/右必须按两行在 list 里的先后定，不按行身份定。
 *
 * <p>背景见 SceneSlots 类注释：宿主 setLeftRight 保持 下标 == intentPosition == 视觉列，
 * 而 native→custom 切换会把 097 保证卡行从后面挪到前面，旧的身份映射就把 col_1
 * 送进了左栏（设备实测 2026-11-23，桌面与预览左右相反）。
 */
public final class SceneSlotsTest {

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

    /** 用槽位列号模拟一次改写，返回"[左] 右"（左 = 下标小的那行拿到的卡）。 */
    private static String layout(int idxStock, int idxHome, String cfgL, String cfgR) {
        int stockCol = SceneSlots.stockColumn(idxStock, idxHome);
        int homeCol = SceneSlots.homeColumn(stockCol);
        // 与 SceneRewriter 一致：判定不出时沿用旧身份映射（097 → cfgR、home → cfgL）。
        String stockCard = stockCol == 0 ? cfgL : cfgR;
        String homeCard = stockCol == 0 ? cfgR : cfgL;
        String left;
        String right;
        if (idxStock < idxHome) {
            left = stockCard;
            right = homeCard;
        } else {
            left = homeCard;
            right = stockCard;
        }
        return left + " | " + right;
    }

    public static void main(String[] args) throws Exception {
        // 纯判据
        check("stockCol 097 first -> left", SceneSlots.stockColumn(0, 1), 0);
        check("stockCol 097 last -> right", SceneSlots.stockColumn(1, 0), 1);
        check("stockCol missing stock -> unknown", SceneSlots.stockColumn(-1, 0),
                SceneSlots.UNKNOWN);
        check("stockCol missing home -> unknown", SceneSlots.stockColumn(0, -1),
                SceneSlots.UNKNOWN);
        check("stockCol same index -> unknown", SceneSlots.stockColumn(2, 2),
                SceneSlots.UNKNOWN);
        check("stockCol negative both -> unknown", SceneSlots.stockColumn(-1, -1),
                SceneSlots.UNKNOWN);
        check("stockCol 0/3 (extra rows) -> left", SceneSlots.stockColumn(0, 3), 0);
        check("stockCol 3/0 (extra rows) -> right", SceneSlots.stockColumn(3, 0), 1);

        check("homeCol of left-stock -> right", SceneSlots.homeColumn(0), 1);
        check("homeCol of right-stock -> left", SceneSlots.homeColumn(1), 0);
        check("homeCol unknown -> left (old mapping)", SceneSlots.homeColumn(SceneSlots.UNKNOWN), 0);

        // 端到端（模拟）：col_0 = 390 时钟, col_1 = 709 实时天气
        final String CLOCK = "390";
        final String WEATHER = "709";
        // 旧顺序 [home, 097]：与 1.36 行为一致 —— 左=时钟
        check("order [home,097] left=clock right=weather", layout(1, 0, CLOCK, WEATHER),
                "390 | 709");
        // 设备实测的新顺序 [097, home]：修复前会输出 "709 | 390"（左右相反）
        check("order [097,home] left=clock right=weather", layout(0, 1, CLOCK, WEATHER),
                "390 | 709");
        // 判定不出（只看得到一行）时保持旧身份映射，不换列
        check("unknown stockCol keeps 097 -> cfgR", SceneSlots.stockColumn(0, -1) == SceneSlots.UNKNOWN
                && "709".equals((SceneSlots.stockColumn(0, -1) == 0 ? CLOCK : WEATHER)), true);
        check("unknown stockCol keeps home -> cfgL", SceneSlots.homeColumn(SceneSlots.UNKNOWN) == 0
                && "390".equals((SceneSlots.homeColumn(SceneSlots.UNKNOWN) == 0 ? CLOCK : WEATHER)), true);

        System.out.println("RESULT " + (fail == 0 ? "OK" : "FAIL")
                + " PASS " + pass + " FAIL " + fail);
        if (fail != 0) {
            System.exit(1);
        }
    }
}
