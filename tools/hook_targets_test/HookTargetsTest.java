package io.github.cechang256.xiaobu;

/**
 * HookTargets 离线单测（1.42）。
 *
 * <p>守的是 2026-11-24 那次事故：UMS 17.24.8 把编辑器预览适配器从 activity.h
 * 挪到 activity.g，activity.h 退化成对话框类。旧代码 findClass 成功、
 * hookAllMethods 匹配 0 个方法且不抛异常，于是照打 "editor bind hook installed"，
 * 药丸/遮罩/标题/预览侧判定全部静默失效，只能靠用户发现功能没了。
 *
 * <p>三条硬要求（lead 要求逐条断言）：
 * <ol>
 *   <li>命中首项：候选里第一个存在就取它；</li>
 *   <li>命中兜底：第一个不在时取后面的候选；</li>
 *   <li>全不命中：返回 null 且 hit 为 null，并有明确的 noClassMessage。</li>
 * </ol>
 */
public final class HookTargetsTest {

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

    private static void checkTrue(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("PASS " + name);
        } else {
            fail++;
            System.out.println("FAIL " + name);
        }
    }

    /** 假适配器：有 onBindViewHolder。 */
    public static final class FakeG {
        public void onBindViewHolder(Object holder, int position) {
        }
    }

    /** 假适配器：h 退化成对话框类，没有 onBindViewHolder。 */
    public static final class FakeH {
        public FakeH(int reason) {
        }
    }

    private static HookTargets.Resolver resolver(final Class<?> g, final Class<?> h) {
        return new HookTargets.Resolver() {
            @Override
            public Class<?> find(String fqn) {
                if (fqn.endsWith("activity.g")) {
                    return g;
                }
                if (fqn.endsWith("activity.h")) {
                    return h;
                }
                return null;
            }
        };
    }

    public static void main(String[] args) {
        String[] cands = {"com.oplus.ums.groupcard.defaultservice.activity.g",
                "com.oplus.ums.groupcard.defaultservice.activity.h"};

        // 1. 命中首项：g 存在 → 直接拿 g，hit 记 g。
        String[] hit = new String[1];
        Class<?> c1 = HookTargets.first(resolver(FakeG.class, FakeH.class), cands, hit);
        check("hit first: class", c1, FakeG.class);
        check("hit first: hit name", hit[0], cands[0]);
        check("hit first: method count", HookTargets.countMethods(c1, "onBindViewHolder"), 1);

        // 2. 命中兜底：g 不在（17.22.8 形态）→ 取 h。
        String[] hit2 = new String[1];
        Class<?> c2 = HookTargets.first(resolver(null, FakeH.class), cands, hit2);
        check("hit fallback: class", c2, FakeH.class);
        check("hit fallback: hit name", hit2[0], cands[1]);

        // 3. 全不命中：两个都不在 → null + hit 清空 + 明确文案。
        String[] hit3 = new String[1];
        Class<?> c3 = HookTargets.first(resolver(null, null), cands, hit3);
        check("none: class", c3, null);
        check("none: hit name", hit3[0], null);
        String msg3 = HookTargets.noClassMessage("editor bind hook", cands, "onBindViewHolder");
        check("none: message", msg3,
                "editor bind hook: no candidate class found#onBindViewHolder"
                        + " (tried " + cands[0] + ", " + cands[1] + ")");

        // 4. 类在、方法不在（今天这次事故的形态）：计数 0 + zeroMethodMessage。
        String[] hit4 = new String[1];
        Class<?> c4 = HookTargets.first(resolver(null, FakeH.class), cands, hit4);
        check("method absent: class", c4, FakeH.class);
        check("method absent: count", HookTargets.countMethods(c4, "onBindViewHolder"), 0);
        check("method absent: message",
                HookTargets.zeroMethodMessage(hit4[0], "onBindViewHolder"),
                "hook matched 0 methods: " + cands[1]
                        + "#onBindViewHolder (class exists, method absent)");

        // 5. 边界：null 输入不抛、计数为 0。
        check("null resolver", HookTargets.first(null, cands, null), null);
        check("null candidates", HookTargets.first(resolver(null, null), null, null), null);
        check("null class count", HookTargets.countMethods(null, "onBindViewHolder"), 0);
        check("null name count", HookTargets.countMethods(FakeG.class, null), 0);
        check("names empty", HookTargets.names(new String[0]), "(none)");
        check("names null", HookTargets.names(null), "(none)");

        // 6. 场景工厂候选表：17.24.8 起第一候选必须是去混淆后的 util.n。
        check("scene factory head", HookTargets.SCENE_FACTORY[0],
                "com.pantanal.server.decision.util.n");
        check("scene factory keeps nv.p", HookTargets.SCENE_FACTORY[1], "com.oplus.sdp.nv.p");
        check("left sid head", HookTargets.NATIVE_LEFT_SID[0],
                "com.pantanal.server.decision.util.n");
        check("editor preview head", HookTargets.EDITOR_PREVIEW[0],
                "com.oplus.ums.groupcard.defaultservice.activity.g");
        checkTrue("editor preview keeps h as fallback",
                HookTargets.EDITOR_PREVIEW[1].endsWith("activity.h"));

        System.out.println();
        System.out.println("PASS " + pass + " FAIL " + fail);
        if (fail != 0) {
            throw new AssertionError("HookTargetsTest failed");
        }
    }
}
