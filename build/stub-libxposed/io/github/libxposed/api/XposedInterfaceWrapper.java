package io.github.libxposed.api;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;

/**
 * Compile-time stub of {@code XposedInterfaceWrapper}: the concrete
 * implementation the real {@code XposedModule} inherits its methods from.
 *
 * <p>Mirroring the wrapper (instead of putting these methods on
 * {@code XposedModule}) keeps the method references pointing at the same class
 * the runtime resolves them on.
 */
public class XposedInterfaceWrapper implements XposedInterface {

    public XposedInterfaceWrapper() {
    }

    @Override
    public int getApiVersion() {
        return 102;
    }

    @Override
    public HookBuilder hook(Executable executable) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public Invoker<?, Method> getInvoker(Method method) {
        throw new UnsupportedOperationException("stub");
    }

    @Override
    public void log(int priority, String tag, String message) {
        throw new UnsupportedOperationException("stub");
    }
}
