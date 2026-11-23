package io.descoped.lds.core.domain;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
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
 * The lock is in this process only: several LDS that write the same documents do not see each other's locks.
 * <p>
 * The locks are striped: a document key is hashed to one of a fixed number of locks, so the memory they take does
 * not grow with the documents, and two documents that share a stripe are written one after the other.
 */
public final class DocumentLocks {

    private static final AttachmentKey<Held> HELD = AttachmentKey.create(Held.class);

    private final Semaphore[] stripes;
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
        this.stripes = new Semaphore[stripes];
        for (int i = 0; i < stripes; i++) {
            this.stripes[i] = new Semaphore(1);
        }
        this.timeout = Objects.requireNonNull(timeout);
    }

    /**
     * Takes the lock of a document for the request, and lets it go when the request has been answered, or, when
     * {@link #holdUntil(HttpServerExchange, CompletionStage)} was called, when that has completed as well.
     *
     * @param exchange  the request
     * @param namespace the namespace of the document
     * @param entity    the entity type of the document
     * @param id        the id of the document
     * @return false when the lock was not free within the time this waits, and is not taken
     * @throws InterruptedException when the thread is interrupted while it waits
     */
    public boolean acquire(HttpServerExchange exchange, String namespace, String entity, String id) throws InterruptedException {
        Semaphore stripe = stripes[Math.floorMod(Objects.hash(namespace, entity, id), stripes.length)];
        if (!stripe.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            return false;
        }
        Held held = new Held(stripe);
        exchange.putAttachment(HELD, held);
        exchange.addExchangeCompleteListener((ex, next) -> {
            try {
                held.answered();
            } finally {
                next.proceed();
            }
        });
        return true;
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
     * waiting.
     *
     * @param exchange the request
     */
    public static void letGoAfterSaga(HttpServerExchange exchange) {
        Held held = exchange.getAttachment(HELD);
        if (held != null) {
            held.answered();
        }
    }

    private static final class Held {
        private final Semaphore stripe;
        private final AtomicBoolean released = new AtomicBoolean(false);
        private final AtomicReference<CompletionStage<?>> until = new AtomicReference<>();

        Held(Semaphore stripe) {
            this.stripe = stripe;
        }

        void holdUntil(CompletionStage<?> completion) {
            until.set(completion);
        }

        void answered() {
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
