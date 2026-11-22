package io.github.cechang256.xiaobu;

import java.io.File;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.cechang256.xiaobu.xp.Xp;
import io.github.libxposed.api.XposedInterface;

/**
 * Offline regression harness for DefaultServiceHook.install().
 *
 * <p>使用假的目标类（同 FQN、同签名）+ 一个计数的 XposedInterface，验证：
 * <ul>
 *   <li>四个目标类全部存在时，注册数、注册目标、18 个反射句柄与日志与改动前一致；</li>
 *   <li>缺任意一个类时 install() 不抛异常，只损失依赖它的那组句柄，并留下
 *       {@code class missing: <fqn>} 行；</li>
 *   <li>四个类全缺时也不抛异常，四条 class missing 全部留痕。</li>
 * </ul>
 * 不覆盖真实 ROM 的继承链/桥方法，只覆盖 install() 的分支逻辑。
 */
public final class ModelInstallTest {

    private static final String CLS_LIST =
            "com.oplus.ums.groupcard.defaultservice.bean.DefaultServiceListEntity";
    private static final String CLS_ENTITY =
            "com.oplus.ums.groupcard.defaultservice.bean.DefaultServiceEntity";
    private static final String CLS_BEAN =
            "com.oplus.ums.groupcard.defaultservice.bean.ServiceBean";
    private static final String CLS_CONFIG =
            "com.oplus.ums.groupcard.defaultservice.bean.CardConfigInfo";

    /** 18 个反射句柄，顺序与 install() 里的 methodOf 调用一致。 */
    private static final String[] HANDLES = {
            "mGetDefaultServiceList", "mGetComposeServices", "mGetOptionalServices",
            "mGetServiceId", "mGetName", "mGetType", "mGetSize", "mGetCardConfig",
            "mSetServiceId", "mSetName", "mSetType", "mSetSize", "mSetCardConfig",
            "mGetComposeName", "mGetIndex", "mGetShouldFilter", "mGetCheckedPosition",
            "mUpdateChecked",
    };

    private static final List<String> LOGS = new ArrayList<String>();
    private static final List<String> HOOKED = new ArrayList<String>();

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        File host = new File(args[0]);
        URL[] urls = {host.toURI().toURL()};
        FakeIface fake = new FakeIface();
        Xp.attach(fake);

        fullHit(urls);
        missingOne(urls, CLS_LIST, 0);
        missingOne(urls, CLS_ENTITY, 1);
        missingOne(urls, CLS_BEAN, 1);
        missingOne(urls, CLS_CONFIG, 1);
        missingAll(urls);

        System.out.println();
        System.out.println("RESULT " + (fail == 0 ? "OK" : "FAIL")
                + " PASS " + pass + " FAIL " + fail);
        if (fail != 0) {
            System.exit(1);
        }
    }

    /** 全命中：与改动前一样，只注册 1 个 hook，18 个句柄全在，零 class missing。 */
    private static void fullHit(URL[] urls) throws Exception {
        reset();
        DefaultServiceHook.install(new URLClassLoader(urls, null));

        check("full: 注册数 1", HOOKED.size() == 1);
        check("full: 目标是 DefaultServiceListEntity#getDefaultServiceList",
                HOOKED.size() == 1 && HOOKED.get(0).contains(
                        "com.oplus.ums.groupcard.defaultservice.bean.DefaultServiceListEntity.getDefaultServiceList"));
        int missingHandles = 0;
        for (String h : HANDLES) {
            if (field(h) == null) {
                missingHandles++;
                System.out.println("   null handle: " + h);
            }
        }
        check("full: 18 个句柄全部解析", missingHandles == 0);
        check("full: 零 class missing", count("class missing:") == 0);
        check("full: 零 skip", count("skip:") == 0);
        check("full: 打了 model hooked",
                joined().contains("model hooked"));
        check("full: sEntityReady/sBeanReady 为 true",
                bool("sEntityReady") && bool("sBeanReady"));
    }

    /**
     * 单类缺失：注册数按依赖关系变化，缺失类的句柄为 null，其余不受影响，
     * 且日志里恰好一条 class missing。
     */
    private static void missingOne(URL[] urls, String hidden, int expectHooks) throws Exception {
        reset();
        DefaultServiceHook.install(new HidingLoader(urls, hidden));
        String label = "minus " + simple(hidden);

        check(label + ": 注册数 " + expectHooks, HOOKED.size() == expectHooks);
        dumpClassMissing();
        check(label + ": 恰一条 class missing", count("class missing: " + hidden + " (") == 1);
        check(label + ": 无 model hook failed", count("model hook failed") == 0);

        if (CLS_LIST.equals(hidden)) {
            check(label + ": getDefaultServiceList 句柄为 null",
                    field("mGetDefaultServiceList") == null);
            check(label + ": updateCheckedPosition 句柄为 null",
                    field("mUpdateChecked") == null);
            check(label + ": 文案区分 class missing",
                    joined().contains("model hook skipped (class missing: " + hidden + ")"));
            check(label + ": 无 model hooked", !joined().contains("model hooked"));
            check(label + ": 18 条 missing method null.x 噪声已消失",
                    count("missing method null.") == 0);
        }
        if (CLS_ENTITY.equals(hidden)) {
            check(label + ": compose/optional/name/index 句柄为 null",
                    field("mGetComposeServices") == null && field("mGetOptionalServices") == null
                            && field("mGetComposeName") == null && field("mGetIndex") == null
                            && field("mGetShouldFilter") == null
                            && field("mGetCheckedPosition") == null);
            check(label + ": tab list 句柄仍在",
                    field("mGetDefaultServiceList") != null && field("mUpdateChecked") != null);
            check(label + ": bean 句柄仍在", field("mGetServiceId") != null
                    && field("mSetCardConfig") != null);
            check(label + ": 仍注册了 hook", joined().contains("model hooked"));
            check(label + ": sEntityReady=false / sBeanReady=true",
                    !bool("sEntityReady") && bool("sBeanReady"));
        }
        if (CLS_BEAN.equals(hidden)) {
            check(label + ": bean 读写句柄全为 null",
                    field("mGetServiceId") == null && field("mGetName") == null
                            && field("mGetType") == null && field("mGetSize") == null
                            && field("mGetCardConfig") == null && field("mSetServiceId") == null
                            && field("mSetName") == null && field("mSetType") == null
                            && field("mSetSize") == null && field("mSetCardConfig") == null);
            check(label + ": entity 句柄仍在", field("mGetComposeServices") != null);
            check(label + ": hook 仍注册", HOOKED.size() == 1);
            check(label + ": sBeanReady=false", !bool("sBeanReady") && bool("sEntityReady"));
        }
        if (CLS_CONFIG.equals(hidden)) {
            check(label + ": 只有 setCardConfigInfo 句柄为 null",
                    field("mSetCardConfig") == null);
            int nulls = 0;
            for (String h : HANDLES) {
                if (field(h) == null) {
                    nulls++;
                }
            }
            check(label + ": 其余 17 个句柄都在", nulls == 1);
            check(label + ": hook 仍注册", HOOKED.size() == 1);
        }
    }

    /** 四个类全缺：不抛异常、不注册、四条 class missing 都留痕。 */
    private static void missingAll(URL[] urls) throws Exception {
        reset();
        boolean threw = false;
        try {
            DefaultServiceHook.install(new HidingLoader(urls, CLS_LIST, CLS_ENTITY, CLS_BEAN, CLS_CONFIG));
        } catch (Throwable t) {
            threw = true;
            System.out.println("   unexpected throw: " + t);
        }
        check("all missing: install() 未抛异常", !threw);
        check("all missing: 零注册", HOOKED.size() == 0);
        dumpClassMissing();
        check("all missing: 四条 class missing",
                count("class missing: " + CLS_LIST + " (") == 1
                        && count("class missing: " + CLS_ENTITY + " (") == 1
                        && count("class missing: " + CLS_BEAN + " (") == 1
                        && count("class missing: " + CLS_CONFIG + " (") == 1);
        check("all missing: skip 文案指向 class missing",
                joined().contains("model hook skipped (class missing: " + CLS_LIST + ")"));
        check("all missing: 18 个句柄都为 null", countNullHandles() == 18);
    }

    // ---- helpers -------------------------------------------------------

    private static void reset() {
        LOGS.clear();
        HOOKED.clear();
    }

    private static void dumpClassMissing() {
        if (System.getenv("XIAOBU_TEST_DUMP") == null) {
            return;
        }
        for (String line : LOGS) {
            if (line.contains("class missing")) {
                System.out.println("    [log] " + line);
            }
        }
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("PASS " + label);
        } else {
            fail++;
            System.out.println("FAIL " + label);
        }
    }

    private static int count(String needle) {
        int n = 0;
        for (String line : LOGS) {
            if (line.contains(needle)) {
                n++;
            }
        }
        return n;
    }

    private static String joined() {
        StringBuilder sb = new StringBuilder();
        for (String line : LOGS) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static int countNullHandles() throws Exception {
        int n = 0;
        for (String h : HANDLES) {
            if (field(h) == null) {
                n++;
            }
        }
        return n;
    }

    private static Object field(String name) throws Exception {
        Field f = DefaultServiceHook.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static boolean bool(String name) throws Exception {
        return Boolean.TRUE.equals(field(name));
    }

    private static String simple(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }

    /** URLClassLoader that pretends one or more FQNs do not exist. */
    private static final class HidingLoader extends URLClassLoader {
        private final List<String> hidden;

        HidingLoader(URL[] urls, String... hidden) {
            super(urls, null);
            this.hidden = Arrays.asList(hidden);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (hidden.contains(name) && directLookup()) {
                // 只拦模块自己的 findClass：JVM 解析 ServiceBean 的方法签名时需要
                // 照常加载该类，否则假类会因 NoClassDefFoundError 连带整类失败，
                // 掩盖「只缺 CardConfigInfo」这一分支。
                throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
        }

        /** True when the current loadClass call comes straight from XposedHelpers.findClass. */
        private static boolean directLookup() {
            StackTraceElement[] st = new Throwable().getStackTrace();
            int n = Math.min(st.length, 6);
            for (int i = 0; i < n; i++) {
                if ("findClass".equals(st[i].getMethodName())
                        && st[i].getClassName().endsWith("xp.XposedHelpers")) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Counts hook() calls and captures log() text; everything else is a stub. */
    private static final class FakeIface implements XposedInterface {
        @Override
        public int getApiVersion() {
            return 102;
        }

        @Override
        public HookBuilder hook(Executable executable) {
            HOOKED.add(executable.getDeclaringClass().getName() + "." + executable.getName());
            return new FakeBuilder(executable);
        }

        @Override
        public Invoker<?, Method> getInvoker(Method method) {
            return null;
        }

        @Override
        public void log(int priority, String tag, String message) {
            LOGS.add(tag + ": " + message);
        }

        private static final class FakeBuilder implements HookBuilder {
            private final Executable executable;

            FakeBuilder(Executable executable) {
                this.executable = executable;
            }

            @Override
            public HookBuilder setPriority(int priority) {
                return this;
            }

            @Override
            public HookBuilder setExceptionMode(ExceptionMode mode) {
                return this;
            }

            @Override
            public HookHandle intercept(Hooker hooker) {
                return new FakeHandle(executable);
            }

            @Override
            public HookBuilder setId(String id) {
                return this;
            }
        }

        private static final class FakeHandle implements HookHandle {
            private final Executable executable;

            FakeHandle(Executable executable) {
                this.executable = executable;
            }

            @Override
            public Executable getExecutable() {
                return executable;
            }

            @Override
            public void unhook() {
            }

            @Override
            public String getId() {
                return "test";
            }

            @Override
            public HookHandle replaceHook(Hooker hooker) {
                return this;
            }
        }
    }
}
