package io.descoped.lds.core.domain;

import io.descoped.lds.api.search.IndexQueue;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.core.domain.resource.ResourceContext;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import io.undertow.util.HttpString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A write that is to be searchable when it is answered: the query parameter {@code searchable=true} (decided by the
 * owner: only when a client asks, so that no other write waits for search, nor is slowed when the index is down).
 * After its saga, the write lets the lock of its document go and waits until the index queue holds no update of its
 * document, asking every
 * {@code 100 ms}, then asks the index to make what it has taken searchable, and is answered. It waits at most
 * {@code 10 s} (decision L3); then it is answered as any write is, and the update reaches the index as every update
 * does. The wait is the same whichever LDS takes the update from a queue that several share.
 * <p>
 * The answer says whether a search finds the write: the header {@code LDS-Searchable}, {@code true} or
 * {@code false}. A wait holds no thread: the write is answered when the wait completes. At most half as many writes
 * as there are request threads wait at once (decided by the owner): a write past that is answered when it is done,
 * with {@code LDS-Searchable: false}, so that waits do not pile up on an index that does not answer.
 */
public final class SearchWait {

    private static final Logger LOG = LoggerFactory.getLogger(SearchWait.class);
    private static final AttachmentKey<SearchWait> ASKED = AttachmentKey.create(SearchWait.class);

    /**
     * The header of the answer to a write that asked to be searchable: whether a search finds it.
     */
    public static final HttpString SEARCHABLE = new HttpString("LDS-Searchable");

    private final IndexQueue queue;
    private final SearchIndex index;
    private final Duration poll;
    private final Duration longest;
    private final Semaphore waiting;

    /**
     * Waits that ask every 100 ms, for at most 10 seconds, at most as many at once as given.
     *
     * @param queue  the index queue
     * @param index  the search index
     * @param atOnce how many writes may wait at once: half as many as the request threads
     */
    public SearchWait(IndexQueue queue, SearchIndex index, int atOnce) {
        this(queue, index, Duration.ofMillis(100), Duration.ofSeconds(10), atOnce);
    }

    /**
     * Waits that ask as often as given, for at most the time given, at most as many at once as given.
     *
     * @param queue   the index queue
     * @param index   the search index
     * @param poll    how often the queue is asked
     * @param longest the longest wait
     * @param atOnce  how many writes may wait at once
     */
    public SearchWait(IndexQueue queue, SearchIndex index, Duration poll, Duration longest, int atOnce) {
        if (atOnce < 1) {
            throw new IllegalArgumentException("atOnce must be at least 1: " + atOnce);
        }
        this.queue = Objects.requireNonNull(queue);
        this.index = Objects.requireNonNull(index);
        this.poll = Objects.requireNonNull(poll);
        this.longest = Objects.requireNonNull(longest);
        this.waiting = new Semaphore(atOnce);
    }

    /**
     * Whether a request asks to be searchable when it is answered.
     *
     * @param queryParameters the query parameters of the request
     * @return true when a {@code searchable} parameter is {@code true}, in any letter case
     */
    public static boolean asked(Map<String, Deque<String>> queryParameters) {
        return queryParameters.getOrDefault("searchable", new ArrayDeque<>()).stream().anyMatch("true"::equalsIgnoreCase);
    }

    /**
     * Marks a write that asks to be searchable, so that its answer waits ({@link #whenAsked}).
     *
     * @param exchange the request
     */
    public void attachIfAsked(HttpServerExchange exchange) {
        if (asked(exchange.getQueryParameters())) {
            exchange.putAttachment(ASKED, this);
        }
    }

    /**
     * When the request asked for it, waits until the document of the write is searchable, at most the longest wait,
     * and says in the answer whether it is ({@link #SEARCHABLE}); no thread waits. Called after the saga of the
     * write, before it is answered.
     *
     * @param exchange the request
     * @param context  the document of the write
     * @return complete when the answer may be sent
     */
    public static CompletionStage<Void> whenAsked(HttpServerExchange exchange, ResourceContext context) {
        return whenAsked(exchange, context.getNamespace(), context.getFirstElement().name(), context.getFirstElement().id());
    }

    static CompletionStage<Void> whenAsked(HttpServerExchange exchange, String namespace, String entity, String id) {
        SearchWait wait = exchange.getAttachment(ASKED);
        if (wait == null) {
            return CompletableFuture.completedFuture(null);
        }
        // the saga has completed (a searchable write waits for it), so no other write of the document waits for this
        // one's search
        DocumentLocks.letGoAfterSaga(exchange);
        return wait.ifFree(namespace, entity, id).toCompletionStage()
                .thenAccept(searchable -> exchange.getResponseHeaders().put(SEARCHABLE, Boolean.toString(searchable)));
    }

    /**
     * Waits as {@link #until} does, when fewer writes than the most at once are waiting.
     *
     * @param namespace the namespace
     * @param entity    the entity type
     * @param id        the id of the document
     * @return whether the document is searchable; false at once when as many writes as may are waiting
     */
    Single<Boolean> ifFree(String namespace, String entity, String id) {
        return Single.defer(() -> {
            if (!waiting.tryAcquire()) {
                LOG.debug("A write of /{}/{}/{} that asked to be searchable is answered at once: as many writes as "
                        + "may are waiting for search", namespace, entity, id);
                return Single.just(false);
            }
            // the room is given back before the answer is (doFinally would give it after), once, also when the wait
            // is given up
            AtomicBoolean held = new AtomicBoolean(true);
            Runnable giveBack = () -> {
                if (held.compareAndSet(true, false)) {
                    waiting.release();
                }
            };
            return until(namespace, entity, id)
                    .doOnEvent((searchable, failure) -> giveBack.run())
                    .doOnDispose(giveBack::run);
        });
    }

    /* How many more writes may wait now. */
    int free() {
        return waiting.availablePermits();
    }

    /**
     * Waits until the queue holds no update of the document and the index has made what it took searchable.
     *
     * @param namespace the namespace
     * @param entity    the entity type
     * @param id        the id of the document
     * @return whether the document is searchable; false when the longest wait was not enough or the index failed
     */
    Single<Boolean> until(String namespace, String entity, String id) {
        return searchable(namespace, entity, id)
                .toSingleDefault(true)
                .onErrorReturn(error -> {
                    if (LOG.isWarnEnabled()) {
                        LOG.warn("A write of /{}/{}/{} that asked to be searchable is answered before it is ({})",
                                namespace, entity, id, error.toString());
                    }
                    return false;
                });
    }

    private Completable searchable(String namespace, String entity, String id) {
        return Flowable.interval(0, poll.toMillis(), TimeUnit.MILLISECONDS)
                .concatMapSingle(_ -> queue.holds(namespace, entity, id))
                .filter(held -> !held)
                .firstOrError()
                .ignoreElement()
                .andThen(Completable.defer(index::refresh))
                .timeout(longest.toMillis(), TimeUnit.MILLISECONDS);
    }
}
