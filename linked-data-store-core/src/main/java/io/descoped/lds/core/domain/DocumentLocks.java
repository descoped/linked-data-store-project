package io.descoped.lds.core.domain;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One write at a time to a document, in this LDS: a write holds the lock of its document from before it reads the
 * document (a part or a link is merged into what is there) until its saga has completed, so a part written at the
 * same time as another is not lost. A write that does not wait for its saga ({@code sync=false}) still holds the
 * lock until the saga has completed, and a write that is answered before its saga starts (refused, or failed) lets
 * it go when it is answered.
 * <p>
 * A write that waits for the lock holds no thread: it is a future that the write before it completes when it lets
 * the lock go, in the order the writes asked, or that completes with {@code false} when the longest wait is over.
 * <p>
 * The lock is in this process only: several LDS that write the same documents do not see each other's locks.
 * <p>
 * The locks are striped: a document key is hashed to one of a fixed number of locks, so the memory they take does
 * not grow with the documents, and two documents that share a stripe are written one after the other.
 */
public final class DocumentLocks {

    private static final AttachmentKey<Held> HELD = AttachmentKey.create(Held.class);

    private final Stripe[] stripes;
    private final Duration timeout;

    /**
     * Locks with 1024 stripes, waited for at most 30 seconds.
     */
    public DocumentLocks() {
        this(1024, Duration.ofSeconds(30));
    }

    /**
     * Locks with the given number of stripes and the longest wait.
     *
     * @param stripes the number of locks
     * @param timeout how long a write waits for the lock of its document
     */
    public DocumentLocks(int stripes, Duration timeout) {
        if (stripes < 1) {
            throw new IllegalArgumentException("stripes must be at least 1");
        }
        this.stripes = new Stripe[stripes];
        for (int i = 0; i < stripes; i++) {
            this.stripes[i] = new Stripe();
        }
        this.timeout = Objects.requireNonNull(timeout);
    }

    /**
     * Asks for the lock of a document for the request. The lock is let go when the request has been answered, or,
     * when {@link #holdUntil(HttpServerExchange, CompletionStage)} was called, when that has completed as well; a
     * request that is answered before it got the lock does not take it.
     *
     * @param exchange  the request
     * @param namespace the namespace of the document
     * @param entity    the entity type of the document
     * @param id        the id of the document
     * @return true when the request has the lock; false when it was not free within the longest wait
     */
    public CompletableFuture<Boolean> acquire(HttpServerExchange exchange, String namespace, String entity, String id) {
        Stripe stripe = stripes[Math.floorMod(Objects.hash(namespace, entity, id), stripes.length)];
        CompletableFuture<Boolean> taken = stripe.take(timeout);
        Held held = new Held(stripe, taken);
        exchange.putAttachment(HELD, held);
        exchange.addExchangeCompleteListener((ex, next) -> {
            try {
                held.answered();
            } finally {
                next.proceed();
            }
        });
        return taken;
    }

    /**
     * How many writes wait for the lock of their document now.
     *
     * @return the writes that wait
     */
    public int waiting() {
        int waiting = 0;
        for (Stripe stripe : stripes) {
            waiting += stripe.waiting();
        }
        return waiting;
    }

    /**
     * How long a write waits for the lock of its document.
     *
     * @return the longest wait
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Keeps the lock the request holds, if any, until the stage has completed too: the saga of the write.
     *
     * @param exchange   the request
     * @param completion the completion of the saga
     */
    public static void holdUntil(HttpServerExchange exchange, CompletionStage<?> completion) {
        Held held = exchange.getAttachment(HELD);
        if (held != null) {
            held.holdUntil(completion);
        }
    }

    /**
     * Lets the lock the request holds, if any, go as soon as the saga it was held for has completed, before the
     * request is answered: a write that waits for search after its saga does not keep other writes of the document
     * waiting. Also a request that will not be answered (its connection is gone) lets it go so.
     *
     * @param exchange the request
     */
    public static void letGoAfterSaga(HttpServerExchange exchange) {
        Held held = exchange.getAttachment(HELD);
        if (held != null) {
            held.answered();
        }
    }

    /*
     * One lock: free, or held, with the writes that wait for it in the order they asked. A wait is a future that the
     * write before it completes with true when it lets the lock go; one that is over (the longest wait, or the
     * request answered) is completed with false and is passed over.
     */
    private static final class Stripe {
        private final Deque<CompletableFuture<Boolean>> waiting = new ArrayDeque<>();
        private boolean held; // guarded by this

        CompletableFuture<Boolean> take(Duration timeout) {
            CompletableFuture<Boolean> taken;
            synchronized (this) {
                if (!held) {
                    held = true;
                    return CompletableFuture.completedFuture(true);
                }
                taken = new CompletableFuture<>();
                waiting.add(taken);
            }
            taken.completeOnTimeout(false, timeout.toMillis(), TimeUnit.MILLISECONDS);
            taken.thenAccept(got -> {
                if (!got) {
                    synchronized (this) {
                        waiting.remove(taken);
                    }
                }
            });
            return taken;
        }

        synchronized int waiting() {
            return waiting.size();
        }

        void release() {
            while (true) {
                CompletableFuture<Boolean> next;
                synchronized (this) {
                    next = waiting.poll();
                    if (next == null) {
                        held = false;
                        return;
                    }
                }
                if (next.complete(true)) {
                    return; // handed to the next write
                }
                // its wait was over: the next one
            }
        }
    }

    private static final class Held {
        private final Stripe stripe;
        private final CompletableFuture<Boolean> taken;
        private final AtomicBoolean released = new AtomicBoolean(false);
        private final AtomicReference<CompletionStage<?>> until = new AtomicReference<>();

        Held(Stripe stripe, CompletableFuture<Boolean> taken) {
            this.stripe = stripe;
            this.taken = taken;
        }

        void holdUntil(CompletionStage<?> completion) {
            until.set(completion);
        }

        void answered() {
            if (taken.complete(false)) {
                return; // answered before it got the lock, which it now never takes
            }
            if (!Boolean.TRUE.equals(taken.getNow(false))) {
                return; // it did not get the lock
            }
            CompletionStage<?> completion = until.get();
            if (completion == null) {
                release();
            } else {
                completion.whenComplete((_, _) -> release());
            }
        }

        private void release() {
            if (released.compareAndSet(false, true)) {
                stripe.release();
            }
        }
    }
}
