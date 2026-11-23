package io.descoped.lds.core.saga;

import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogEntryBuilder;
import no.cantara.sagalog.SagaLogEntryType;
import no.cantara.sagalog.SagaLogInitializer;
import no.cantara.sagalog.SagaLogPool;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The PostgreSQL saga log, which loses one entry when asked: the End entry of a step, as when the process stops
 * after the step has run and before its End reached the log. The step then has a Start and no End, and the recovery
 * runs it again.
 */
public class LostEntrySagaLogInitializer implements SagaLogInitializer {

    /** The id of the step whose next End entry is lost, or null. */
    static final AtomicReference<String> LOSE_END_OF = new AtomicReference<>();

    @Override
    public SagaLogPool initialize(Map<String, String> configuration) {
        SagaLogPool pool = postgres().initialize(configuration);
        return proxy(SagaLogPool.class, (_, method, args) -> {
            Object result = call(pool, method, args);
            return result instanceof SagaLog log ? losing(log) : result;
        });
    }

    @Override
    public Map<String, String> configurationOptionsAndDefaults() {
        return postgres().configurationOptionsAndDefaults();
    }

    private static SagaLog losing(SagaLog log) {
        return proxy(SagaLog.class, (_, method, args) -> {
            if (method.getName().equals("write") && args[0] instanceof SagaLogEntryBuilder entry
                    && entry.entryType() == SagaLogEntryType.End && entry.nodeId() != null
                    && LOSE_END_OF.compareAndSet(entry.nodeId(), null)) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "the End entry of " + entry.nodeId() + " was lost, as the test asked"));
            }
            return call(log, method, args);
        });
    }

    private static SagaLogInitializer postgres() {
        return SagaLogInitializers.named(SagaLogInitializers.POSTGRES);
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object call(Object delegate, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(delegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
