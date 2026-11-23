package io.descoped.lds.core.domain;

import io.descoped.lds.api.search.IndexQueue;
import io.descoped.lds.api.search.SearchIndex;
import io.descoped.lds.core.domain.resource.ResourceContext;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A write that is to be searchable when it is answered: the query parameter {@code searchable=true} (decided by the
 * owner: only when a client asks, so that no other write waits for search, nor is slowed when the index is down).
 * After its saga, the write lets the lock of its document go and waits until the index queue holds no update of its
 * document, asking every
 * {@code 100 ms}, then asks the index to make what it has taken searchable, and is answered. It waits at most
 * {@code 10 s} (decision L3); then it is answered as any write is, and the update reaches the index as every update
 * does. The wait is the same whichever LDS takes the update from a queue that several share.
 */
public final class SearchWait {

    private static final Logger LOG = LoggerFactory.getLogger(SearchWait.class);
    private static final AttachmentKey<SearchWait> ASKED = AttachmentKey.create(SearchWait.class);

    private final IndexQueue queue;
    private final SearchIndex index;
    private final Duration poll;
    private final Duration longest;

    /**
     * Waits that ask every 100 ms, for at most 10 seconds.
     *
     * @param queue the index queue
     * @param index the search index
     */
    public SearchWait(IndexQueue queue, SearchIndex index) {
        this(queue, index, Duration.ofMillis(100), Duration.ofSeconds(10));
    }

    /**
     * Waits that ask as often as given, for at most the time given.
     *
     * @param queue   the index queue
     * @param index   the search index
     * @param poll    how often the queue is asked
     * @param longest the longest wait
     */
    public SearchWait(IndexQueue queue, SearchIndex index, Duration poll, Duration longest) {
        this.queue = Objects.requireNonNull(queue);
        this.index = Objects.requireNonNull(index);
        this.poll = Objects.requireNonNull(poll);
        this.longest = Objects.requireNonNull(longest);
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
     * Waits, when the request asked for it, until the document of the write is searchable, at most the longest wait.
     * Called on the worker thread of the request, after its saga.
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
            wait.until(namespace, entity, id);
        }
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
