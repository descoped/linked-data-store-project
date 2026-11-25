package io.descoped.lds.api.persistence.reactivex;

import io.descoped.lds.api.json.JsonNavigationPath;
import io.descoped.lds.api.persistence.PersistenceDeletePolicy;
import io.descoped.lds.api.persistence.PersistenceException;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.specification.Specification;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;

import java.time.ZonedDateTime;

/**
 * Temporary interface
 */
public interface RxJsonPersistence {

    /**
     * Read a single {@link JsonDocument}.
     * <p>
     * Not settled: whether a failure of the store is given as {@code onError(PersistenceException)}.
     *
     * @param tx         The transaction to use.
     * @param snapshot   Upper bound of the returned version
     * @param ns         the name space
     * @param entityName the entity name
     * @param id         the entity id
     * @return a {@link Maybe<JsonDocument>} instance
     * @throws PersistenceException in case of persistence exception.
     */
    Maybe<JsonDocument> readDocument(Transaction tx, ZonedDateTime snapshot, String ns, String entityName, String id);

    /**
     * Read {@link JsonDocument}s.
     * <p>
     * Not settled: whether a failure of the store is given as {@code onError(PersistenceException)}.
     *
     * @param tx         the transaction
     * @param snapshot   upper bound of the returned version
     * @param ns         the name space
     * @param entityName the entity name
     * @param range      lower and upper id bounds
     */
    Flowable<JsonDocument> readDocuments(Transaction tx, ZonedDateTime snapshot, String ns, String entityName,
                                         Range<String> range);

    /**
     * Read {@link JsonDocument}s.
     * <p>
     * Not settled: whether a failure of the store is given as {@code onError(PersistenceException)}.
     *
     * @param tx         the transaction
     * @param ns         the name space
     * @param entityName the entity name
     * @param range      lower and upper id bounds
     */
    Flowable<JsonDocument> readDocumentVersions(Transaction tx, String ns, String entityName, String id,
                                                Range<ZonedDateTime> range);

    /**
     * Every version of every document of an entity type, as the store holds them, for a rebuild of what is derived
     * from the store, such as the search index. A version that marks a document as deleted is included, as a document
     * whose {@link JsonDocument#deleted()} is true, so that the versions of a deleted document are found. The documents
     * come by id, in the order of their code points ({@link io.descoped.lds.api.persistence.IdOrder}), with the
     * versions of one id together, in an order the store chooses; they are taken from the store as they are asked for.
     *
     * @param tx         the transaction
     * @param ns         the namespace
     * @param entityName the entity type
     * @return the versions
     */
    Flowable<JsonDocument> readAllDocumentVersions(Transaction tx, String ns, String entityName);


    /**
     * Read the <b>target</b> {@link JsonDocument}s linked to a <b>source</b> document.
     *
     * @param tx       the transaction
     * @param snapshot upper bound of the returned version
     * @param query    the source document (namespace, entity type, id), the path of the link field in it, and the
     *                 entity type of the target documents ({@link LinkQuery#otherEntity()})
     * @param range    lower and upper id bounds
     * @see #readSourceDocuments(Transaction, ZonedDateTime, LinkQuery, Range)
     */
    Flowable<JsonDocument> readTargetDocuments(Transaction tx, ZonedDateTime snapshot, LinkQuery query,
                                               Range<String> range);

    /**
     * Read the <b>source</b> {@link JsonDocument}s that links to a <b>target</b> document.
     * <p>
     * Note that the range applies to the source documents.
     *
     * @param tx       the transaction
     * @param snapshot upper bound of the returned version
     * @param query    the target document (namespace, entity type, id), the path of the link field in the source
     *                 documents, and the entity type of the source documents ({@link LinkQuery#otherEntity()})
     * @param range    lower and upper id bounds
     * @see #readTargetDocuments(Transaction, ZonedDateTime, LinkQuery, Range)
     */
    Flowable<JsonDocument> readSourceDocuments(Transaction tx, ZonedDateTime snapshot, LinkQuery query,
                                               Range<String> range);

    /**
     * Not settled: whether a failure of the store is given as {@code onError(PersistenceException)}.
     */
    default Completable createOrOverwrite(Transaction tx, JsonDocument document, Specification specification) {
        return createOrOverwrite(tx, Flowable.just(document), specification);
    }

    /**
     * Not settled: whether a failure of the store is given as {@code onError(PersistenceException)}.
     */
    Completable createOrOverwrite(Transaction tx, Flowable<JsonDocument> documentFlowable, Specification specification);

    /**
     * Not settled: whether a failure of the store is given as {@code onError(PersistenceException)}.
     */
    Completable deleteDocument(Transaction tx, String ns, String entityName, String id, ZonedDateTime version,
                               PersistenceDeletePolicy policy);

    /**
     * Not settled: whether a failure of the store is given as {@code onError(PersistenceException)}.
     */
    Completable deleteAllDocumentVersions(Transaction tx, String ns, String entity, String id,
                                          PersistenceDeletePolicy policy);

    /**
     * Deletes every version of every document of the entity in the namespace, also of an entity that the
     * specification does not have.
     *
     * @param tx            the transaction
     * @param namespace     the namespace
     * @param entity        the entity
     * @param specification the specification of the documents
     * @return completes when every document is deleted
     */
    Completable deleteAllEntities(Transaction tx, String namespace, String entity, Specification specification);

    /**
     * Not settled: whether a failure of the store is given as {@code onError(PersistenceException)}.
     */
    Completable markDocumentDeleted(Transaction transaction, String ns, String entityName, String id,
                                    ZonedDateTime version, PersistenceDeletePolicy policy);

    /**
     * Checks if there is a {@link JsonDocument} before the given id.
     * <p>
     * Not settled: whether a failure of the store is given as {@code onError(PersistenceException)}.
     */
    Single<Boolean> hasPrevious(Transaction tx, ZonedDateTime snapshot, String ns, String entityName, String id);

    /**
     * Checks if there is a {@link JsonDocument} after the given id.
     * <p>
     * Not settled: whether a failure of the store is given as {@code onError(PersistenceException)}.
     */
    Single<Boolean> hasNext(Transaction tx, ZonedDateTime snapshot, String ns, String entityName, String id);

    /**
     * Uses the transaction-factory to create a new transaction. This method is equivalent
     * to calling <code>transactionFactory.createTransaction()</code>.
     *
     * @param readOnly true if the transaction will only perform read operations, false if at least one write operation
     *                 will be performed, and false if the caller is unsure. Note that the underlying persistence
     *                 provider may be able to optimize performance and contention related issues when read-only
     *                 transactions are involved.
     * @return the newly created transaction
     * @throws PersistenceException
     */
    Transaction createTransaction(boolean readOnly) throws PersistenceException;

    Flowable<JsonDocument> findDocument(Transaction tx, ZonedDateTime snapshot, String namespace, String entityName,
                                        JsonNavigationPath path, String value, Range<String> range);

    /**
     * Clean up resources
     *
     * @throws PersistenceException
     */
    void close() throws PersistenceException;
}
