package io.github.cechang256.xiaobu;

/**
 * 候选类名解析 + 命中计数（纯 JDK，可离线测试）。
 *
 * <p>存在理由：宿主每次升级都可能把某个类挪走/改名，而
 * {@code XposedBridge.hookAllMethods(cls, name, cb)} 在「类存在、方法不存在」时
 * 匹配 0 个方法且<strong>不抛异常</strong>——模块会照常打印 "hook installed"，
 * 回调却永远不进来（2026-11-24 UMS 17.24.8：activity.h 变成对话框类、
 * 预览适配器搬到 activity.g，编辑器整条交互链静默失效）。
 *
 * <p>因此所有「按名字挂宿主方法」的落点都改成：候选列表 → 逐个加载 → 取首个
 * 存在的类 → 统计同名方法数 → 为 0 时 {@code Diag.always} 明确留痕。
 */
public final class HookTargets {

    private HookTargets() {
    }

    /** 编辑器预览适配器：17.24.8 起是 activity.g，activity.h 退化为对话框类（保留兜底）。 */
    public static final String[] EDITOR_PREVIEW = {
            "com.oplus.ums.groupcard.defaultservice.activity.g",
            "com.oplus.ums.groupcard.defaultservice.activity.h",
    };

    /** 系统卡工厂：17.24.8 起宿主去混淆，com.oplus.sdp.nv.p → com.pantanal.server.decision.util.n。 */
    public static final String[] SCENE_FACTORY = {
            "com.pantanal.server.decision.util.n",
            "com.oplus.sdp.nv.p",
            "com.oplus.sdp.mv.t",
    };

    /** 原生左卡配置读取器（静态 e(Context) + 静态字段 f）：17.24.8 起是 util.n。 */
    public static final String[] NATIVE_LEFT_SID = {
            "com.pantanal.server.decision.util.n",
            "com.oplus.sdp.nv.p",
    };

    /** 类加载器抽象，便于离线用假 Resolver 测试（模块里包一层 XposedHelpers.findClass）。 */
    public interface Resolver {
        Class<?> find(String fqn);
    }

    /** 逗号分隔的候选名，直接进日志。 */
    public static String names(String[] candidates) {
        if (candidates == null || candidates.length == 0) {
            return "(none)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(candidates[i]);
        }
        return sb.toString();
    }

    /**
     * 按顺序返回第一个能加载到的类；全部失败返回 null。
     * {@code hit} 非空且长度 ≥1 时写入命中名（全失败写 null）。
     */
    public static Class<?> first(Resolver resolver, String[] candidates, String[] hit) {
        if (hit != null && hit.length > 0) {
            hit[0] = null;
        }
        if (resolver == null || candidates == null) {
            return null;
        }
        for (String name : candidates) {
            Class<?> c;
            try {
                c = resolver.find(name);
            } catch (Throwable t) {
                c = null;
            }
            if (c != null) {
                if (hit != null && hit.length > 0) {
                    hit[0] = name;
                }
                return c;
            }
        }
        return null;
    }

    /** 统计类（含父类链，到 Object 为止）中同名的已声明方法数——与 hookAllMethods 的遍历口径一致。 */
    public static int countMethods(Class<?> cls, String methodName) {
        if (cls == null || methodName == null) {
            return 0;
        }
        int n = 0;
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            java.lang.reflect.Method[] ms;
            try {
                ms = c.getDeclaredMethods();
            } catch (Throwable t) {
                continue;
            }
            for (java.lang.reflect.Method m : ms) {
                if (methodName.equals(m.getName())) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 「类在、方法不在」的告警文案（静默失败的替代品）。 */
    public static String zeroMethodMessage(String clsName, String methodName) {
        return "hook matched 0 methods: " + (clsName == null ? "(unknown)" : clsName)
                + "#" + methodName + " (class exists, method absent)";
    }

    /** 「候选类全都不在」的告警文案。 */
    public static String noClassMessage(String what, String[] candidates) {
        return noClassMessage(what, candidates, null);
    }

    /** 同上，附带方法名。 */
    public static String noClassMessage(String what, String[] candidates, String methodName) {
        String tail = methodName == null ? "" : "#" + methodName;
        return what + ": no candidate class found" + tail
                + " (tried " + names(candidates) + ")";
    }
}
