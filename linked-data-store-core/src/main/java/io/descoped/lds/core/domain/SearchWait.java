package io.descoped.lds.core.domain;

import io.descoped.lds.api.search.IndexQueue;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.core.domain.resource.ResourceContext;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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
 * {@code false}. A wait holds a request thread, so at most half of them wait at once (decided by the owner): a write
 * past that is answered when it is done, with {@code LDS-Searchable: false}, and other requests are not slowed by
 * an index that does not answer.
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
     * @param atOnce how many writes may wait at once: half of the request threads
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
     * Marks a write that asks to be searchable, so that its handler waits ({@link #awaitIfAsked}).
     *
     * @param exchange the request
     */
    public void attachIfAsked(HttpServerExchange exchange) {
        if (asked(exchange.getQueryParameters())) {
            exchange.putAttachment(ASKED, this);
        }
    }

    /**
     * Waits, when the request asked for it, until the document of the write is searchable, at most the longest wait,
     * and says in the answer whether it is ({@link #SEARCHABLE}). Called on the worker thread of the request, after
     * its saga, before the answer is sent.
     *
     * @param exchange the request
     * @param context  the document of the write
     */
    public static void awaitIfAsked(HttpServerExchange exchange, ResourceContext context) {
        awaitIfAsked(exchange, context.getNamespace(), context.getFirstElement().name(), context.getFirstElement().id());
    }

    static void awaitIfAsked(HttpServerExchange exchange, String namespace, String entity, String id) {
        SearchWait wait = exchange.getAttachment(ASKED);
        if (wait != null) {
            // the saga has completed (a searchable write waits for it), so no other write of the document waits for
            // this one's search
            DocumentLocks.letGoAfterSaga(exchange);
            boolean searchable = wait.ifFree(namespace, entity, id);
            exchange.getResponseHeaders().put(SEARCHABLE, Boolean.toString(searchable));
        }
    }

    /**
     * Waits as {@link #until} does, when fewer writes than the most at once are waiting.
     *
     * @param namespace the namespace
     * @param entity    the entity type
     * @param id        the id of the document
     * @return whether the document is searchable; false at once when as many writes as may are waiting
     */
    boolean ifFree(String namespace, String entity, String id) {
        if (!waiting.tryAcquire()) {
            LOG.debug("A write of /{}/{}/{} that asked to be searchable is answered at once: as many writes as may "
                    + "are waiting for search", namespace, entity, id);
            return false;
        }
        try {
            return until(namespace, entity, id);
        } finally {
            waiting.release();
        }
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
    boolean until(String namespace, String entity, String id) {
        Throwable error = searchable(namespace, entity, id).blockingGet();
        if (error == null) {
            return true;
        }
        if (LOG.isWarnEnabled()) {
            LOG.warn("A write of /{}/{}/{} that asked to be searchable is answered before it is ({})", namespace,
                    entity, id, error.toString());
        }
        return false;
    }

    private Maybe<Throwable> searchable(String namespace, String entity, String id) {
        return Flowable.interval(0, poll.toMillis(), TimeUnit.MILLISECONDS)
                .concatMapSingle(_ -> queue.holds(namespace, entity, id))
                .filter(held -> !held)
                .firstOrError()
                .ignoreElement()
                .andThen(Completable.defer(index::refresh))
                .timeout(longest.toMillis(), TimeUnit.MILLISECONDS)
                .<Throwable>toMaybe()
                .onErrorReturn(error -> error);
    }
}
