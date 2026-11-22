package io.descoped.lds.core.saga;

import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogInitializer;
import no.cantara.sagalog.SagaLogPool;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The memory saga log with a {@code write} and a {@code truncate()} that take time, as they do in a saga log that is
 * a database. A saga then completes after the caller that started it has got its futures back, and a saga log that
 * is released before its truncate has finished is still full when the next saga acquires it. The coordinator would
 * hide that: it takes the entries for an unfinished saga, runs recovery on them and then gets the log empty, while
 * the late truncate is still to come and removes what the next saga has written. So every log handed out with
 * entries in it is counted in {@link #ACQUIRED_WITH_ENTRIES}.
 */
public class SlowTruncateSagaLogInitializer implements SagaLogInitializer {

    /** The number of times a pool of this initializer handed out a saga log that had entries in it. */
    static final AtomicInteger ACQUIRED_WITH_ENTRIES = new AtomicInteger();

    /** False makes {@code truncate()} do nothing, so a saga log stays full whatever is done to empty it. */
    static final AtomicBoolean TRUNCATES = new AtomicBoolean(true);

    /** The number of coming {@code truncate()} calls that fail. */
    static final AtomicInteger FAILING_TRUNCATES = new AtomicInteger();

    static final long WRITE_MILLIS = 10;
    static final long TRUNCATE_MILLIS = 200;

    private final Map<SagaLog, SagaLog> slowLogs = new ConcurrentHashMap<>();

    @Override
    public SagaLogPool initialize(Map<String, String> configuration) {
        SagaLogPool pool = memory().initialize(configuration);
        return proxy(SagaLogPool.class, (_, method, args) -> {
            Object result = call(pool, method, args);
            if (!(result instanceof SagaLog log)) {
                return result;
            }
            if (method.getName().equals("tryAcquire") && log.readIncompleteSagas().findAny().isPresent()) {
                ACQUIRED_WITH_ENTRIES.incrementAndGet();
            }
            return slowLogs.computeIfAbsent(log, this::slow);
        });
    }

    @Override
    public Map<String, String> configurationOptionsAndDefaults() {
        return memory().configurationOptionsAndDefaults();
    }

    private SagaLog slow(SagaLog log) {
        return proxy(SagaLog.class, (_, method, args) -> {
            if (method.getName().equals("truncate") && method.getParameterCount() == 0) {
                return after(TRUNCATE_MILLIS).thenCompose(_ -> {
                    if (FAILING_TRUNCATES.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                        return CompletableFuture.failedFuture(new IllegalStateException("truncate failed, as the test asked"));
                    }
                    return TRUNCATES.get() ? log.truncate() : CompletableFuture.completedFuture(null);
                });
            }
            if (method.getName().equals("write")) {
                return after(WRITE_MILLIS).thenCompose(_ -> {
                    try {
                        return (CompletableFuture<?>) call(log, method, args);
                    } catch (Throwable t) {
                        return CompletableFuture.failedFuture(t);
                    }
                });
            }
            return call(log, method, args);
        });
    }

    private static CompletableFuture<Void> after(long millis) {
        return CompletableFuture.runAsync(() -> {
        }, CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS));
    }

    /** The memory saga log's initializer; its package is not exported, so it is found as the service it is. */
    private static SagaLogInitializer memory() {
        return ServiceLoader.load(SagaLogInitializer.class).stream()
                .filter(provider -> provider.type().getName().equals("no.cantara.sagalog.memory.MemorySagaLogInitializer"))
                .findFirst().orElseThrow().get();
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
