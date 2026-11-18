package io.descoped.lds.api.persistence.reactivex;

import io.descoped.lds.api.json.JsonNavigationPath;
import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.PersistenceDeletePolicy;
import io.descoped.lds.api.persistence.PersistenceException;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.TransactionStatistics;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.persistence.api.Document;
import io.descoped.lds.persistence.api.Persistence;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Maybe;
import io.reactivex.Single;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Lets code written for {@link RxJsonPersistence} run on a provider of the 3.0 interface,
 * {@link io.descoped.lds.persistence.api.Persistence}. It exists while core still uses the reactive interface and
 * is deleted, with that interface, when core moves to the 3.0 one.
 *
 * <p>Timestamps are converted to instants, so the zone a caller used does not matter. A transaction made here
 * keeps the pre-3.0 behaviour its callers rely on: closing it commits. Delete policies are ignored, as every
 * pre-3.0 provider ignored them.
 */
public final class PersistenceAdapter implements RxJsonPersistence {

    /** The zone pre-3.0 providers returned versions in; core prints it. */
    private static final ZoneId UTC = ZoneId.of("Etc/UTC");

    private final Persistence persistence;

    /**
     * Creates the adapter.
     *
     * @param persistence the persistence to run on; closed when the adapter is closed
     */
    public PersistenceAdapter(Persistence persistence) {
        this.persistence = persistence;
    }

    @Override
    public Maybe<JsonDocument> readDocument(Transaction tx, ZonedDateTime snapshot, String ns, String entityName, String id) {
        return Maybe.defer(() -> persistence.read(unwrap(tx), snapshot.toInstant(), ns, entityName, id)
                .map(document -> Maybe.just(legacy(document))).orElseGet(Maybe::empty));
    }

    @Override
    public Flowable<JsonDocument> readDocuments(Transaction tx, ZonedDateTime snapshot, String ns, String entityName, Range<String> range) {
        return flowable(() -> persistence.list(unwrap(tx), snapshot.toInstant(), ns, entityName, range(range, id -> id)));
    }

    @Override
    public Flowable<JsonDocument> readDocumentVersions(Transaction tx, String ns, String entityName, String id, Range<ZonedDateTime> range) {
        return flowable(() -> persistence.versions(unwrap(tx), ns, entityName, id, range(range, ZonedDateTime::toInstant)));
    }

    @Override
    public Flowable<JsonDocument> readTargetDocuments(Transaction tx, ZonedDateTime snapshot, String ns, String sourceEntityName, String sourceId, JsonNavigationPath relationPath, String targetEntityName, Range<String> range) {
        return flowable(() -> persistence.targets(unwrap(tx), snapshot.toInstant(), ns, sourceEntityName, sourceId,
                relationPath, targetEntityName, range(range, id -> id)));
    }

    @Override
    public Flowable<JsonDocument> readSourceDocuments(Transaction tx, ZonedDateTime snapshot, String ns, String targetEntityName, String targetId, JsonNavigationPath relationPath, String sourceEntityName, Range<String> range) {
        return flowable(() -> persistence.sources(unwrap(tx), snapshot.toInstant(), ns, targetEntityName, targetId,
                relationPath, sourceEntityName, range(range, id -> id)));
    }

    @Override
    public Flowable<JsonDocument> findDocument(Transaction tx, ZonedDateTime snapshot, String namespace, String entityName, JsonNavigationPath path, String value, Range<String> range) {
        return flowable(() -> persistence.find(unwrap(tx), snapshot.toInstant(), namespace, entityName, path, value,
                range(range, id -> id)));
    }

    @Override
    public Completable createOrOverwrite(Transaction tx, Flowable<JsonDocument> documentFlowable, Specification specification) {
        return documentFlowable.toList().flatMapCompletable(documents -> Completable.fromAction(() ->
                persistence.write(unwrap(tx), documents.stream()
                        .map(document -> new Document(key(document.key()), document.jackson())).toList())));
    }

    @Override
    public Completable deleteDocument(Transaction tx, String ns, String entityName, String id, ZonedDateTime version, PersistenceDeletePolicy policy) {
        return Completable.fromAction(() -> persistence.deleteVersion(unwrap(tx), key(ns, entityName, id, version)));
    }

    @Override
    public Completable deleteAllDocumentVersions(Transaction tx, String ns, String entity, String id, PersistenceDeletePolicy policy) {
        return Completable.fromAction(() -> persistence.deleteAllVersions(unwrap(tx), ns, entity, id));
    }

    @Override
    public Completable deleteAllEntities(Transaction tx, String namespace, String entity, Specification specification) {
        return Completable.fromAction(() -> persistence.deleteEntity(unwrap(tx), namespace, entity));
    }

    @Override
    public Completable markDocumentDeleted(Transaction transaction, String ns, String entityName, String id, ZonedDateTime version, PersistenceDeletePolicy policy) {
        return Completable.fromAction(() -> persistence.markDeleted(unwrap(transaction), key(ns, entityName, id, version)));
    }

    @Override
    public Single<Boolean> hasPrevious(Transaction tx, ZonedDateTime snapshot, String ns, String entityName, String id) {
        return Single.fromCallable(() -> persistence.hasPrevious(unwrap(tx), snapshot.toInstant(), ns, entityName, id));
    }

    @Override
    public Single<Boolean> hasNext(Transaction tx, ZonedDateTime snapshot, String ns, String entityName, String id) {
        return Single.fromCallable(() -> persistence.hasNext(unwrap(tx), snapshot.toInstant(), ns, entityName, id));
    }

    @Override
    public Transaction createTransaction(boolean readOnly) throws PersistenceException {
        return new AdaptedTransaction(persistence.begin(readOnly));
    }

    @Override
    public void close() throws PersistenceException {
        persistence.close();
    }

    private static Flowable<JsonDocument> flowable(java.util.concurrent.Callable<List<Document>> read) {
        return Flowable.defer(() -> Flowable.fromIterable(read.call()).map(PersistenceAdapter::legacy));
    }

    private static JsonDocument legacy(Document document) {
        io.descoped.lds.persistence.api.DocumentKey key = document.key();
        return new JsonDocument(new DocumentKey(key.namespace(), key.entity(), key.id(),
                key.version().atZone(UTC)), document.json());
    }

    private static io.descoped.lds.persistence.api.DocumentKey key(DocumentKey key) {
        return key(key.namespace(), key.entity(), key.id(), key.timestamp());
    }

    private static io.descoped.lds.persistence.api.DocumentKey key(String namespace, String entity, String id, ZonedDateTime version) {
        return new io.descoped.lds.persistence.api.DocumentKey(namespace, entity, id, version.toInstant());
    }

    /** The pre-3.0 range reads backward when it has a last-limit, or an upper bound and no lower bound. */
    private static <F, T> io.descoped.lds.persistence.api.Range<T> range(Range<F> range, Function<F, T> bound) {
        if (range == null) {
            return io.descoped.lds.persistence.api.Range.unbounded();
        }
        Integer limit = range.getLimit();
        return new io.descoped.lds.persistence.api.Range<>(
                range.hasAfter() ? bound.apply(range.getAfter()) : null,
                range.hasBefore() ? bound.apply(range.getBefore()) : null,
                limit == null ? io.descoped.lds.persistence.api.Range.UNLIMITED : limit,
                range.isBackward());
    }

    private static io.descoped.lds.persistence.api.Transaction unwrap(Transaction tx) {
        if (tx instanceof AdaptedTransaction adapted) {
            return adapted.delegate;
        }
        throw new IllegalArgumentException("Not a transaction of this persistence");
    }

    private static final class AdaptedTransaction implements Transaction {

        private final io.descoped.lds.persistence.api.Transaction delegate;
        private boolean done;

        private AdaptedTransaction(io.descoped.lds.persistence.api.Transaction delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized CompletableFuture<TransactionStatistics> commit() {
            if (!done) {
                done = true;
                try {
                    delegate.commit();
                } catch (RuntimeException e) {
                    return CompletableFuture.failedFuture(e);
                } finally {
                    delegate.close();
                }
            }
            return CompletableFuture.completedFuture(new TransactionStatistics());
        }

        @Override
        public synchronized CompletableFuture<TransactionStatistics> cancel() {
            done = true;
            delegate.close();
            return CompletableFuture.completedFuture(new TransactionStatistics());
        }
    }
}
