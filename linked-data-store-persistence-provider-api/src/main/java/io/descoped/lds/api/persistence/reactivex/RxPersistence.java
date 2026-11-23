package io.descoped.lds.api.persistence.reactivex;

import io.descoped.lds.api.persistence.PersistenceDeletePolicy;
import io.descoped.lds.api.persistence.PersistenceException;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.TransactionFactory;
import io.descoped.lds.api.persistence.streaming.Fragment;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;

import java.time.ZonedDateTime;

/**
 * A store of fragments. A read that gives several documents or versions gives them in the order of its range,
 * forward or {@linkplain Range#isBackward() backward}, and the fragments of each one together, by path and offset, so
 * that the documents can be cut from the stream as it comes, without sorting it.
 */
public interface RxPersistence {

    TransactionFactory transactionFactory() throws PersistenceException;

    Transaction createTransaction(boolean readOnly) throws PersistenceException;

    Completable createOrOverwrite(
            Transaction tx,
            Flowable<Fragment> fragments
    );

    Flowable<Fragment> read(
            Transaction tx,
            ZonedDateTime snapshot,
            String namespace,
            String entity,
            String id
    );

    /**
     * The fragments of every version of every document of the entity, deletion markers included: by id, then by
     * version (in an order the store chooses), then by path, so that the fragments of one version come together.
     *
     * @param tx        the transaction
     * @param namespace the namespace
     * @param entity    the entity type
     * @return the fragments, taken from the store as they are asked for
     */
    Flowable<Fragment> readAllVersions(Transaction tx, String namespace, String entity);

    /**
     * The versions of one document, deletion markers included, oldest first, or newest first for a backward range.
     * {@code after} is not a strict bound: the version that was valid at that time is the first one. {@code before}
     * is exclusive. A store may apply the limit of the range, counting versions, or leave it to the caller.
     *
     * @param tx        the transaction
     * @param namespace the namespace
     * @param entity    the entity type
     * @param id        the id of the document
     * @param range     the versions to read, and the direction
     * @return the fragments, taken from the store as they are asked for
     */
    Flowable<Fragment> readVersions(
            Transaction tx,
            String namespace,
            String entity,
            String id,
            Range<ZonedDateTime> range
    );

    Completable delete(
            Transaction transaction,
            String namespace,
            String entity,
            String id,
            ZonedDateTime version,
            PersistenceDeletePolicy policy
    );

    Completable deleteAllVersions(
            Transaction transaction,
            String namespace,
            String entity,
            String id,
            PersistenceDeletePolicy policy
    );

    Completable deleteAllEntities(Transaction tx, String namespace, String entity, Iterable<String> paths);

    Completable markDeleted(
            Transaction transaction,
            String namespace,
            String entity,
            String id,
            ZonedDateTime version,
            PersistenceDeletePolicy policy
    );

    /**
     * The version valid at the snapshot of every document of the entity whose id is inside the range, both bounds
     * exclusive, by ascending id in the order of {@link io.descoped.lds.api.persistence.IdOrder}, or descending for
     * a backward range. A document that is deleted at the snapshot is left out. A store may apply the limit of the
     * range, counting the documents it gives, or leave it to the caller.
     *
     * @param transaction the transaction
     * @param snapshot    the time the versions are valid at
     * @param namespace   the namespace
     * @param entity      the entity type
     * @param range       the ids to read, and the direction
     * @return the fragments, taken from the store as they are asked for
     */
    Flowable<Fragment> readAll(
            Transaction transaction,
            ZonedDateTime snapshot,
            String namespace,
            String entity,
            Range<String> range
    );

    /**
     * The documents whose version valid at the snapshot holds the value at the path, by ascending id, or descending
     * for a backward range. A store may give a document that does not hold the value (a false match), which the
     * caller leaves out, and may apply the bounds and the limit of the range.
     *
     * @param transaction the transaction
     * @param snapshot    the time the versions are valid at
     * @param namespace   the namespace
     * @param entity      the entity type
     * @param path        the path of the value, without array positions
     * @param value       the value as it is stored
     * @param range       the ids to read, and the direction
     * @return the fragments, taken from the store as they are asked for
     */
    Flowable<Fragment> find(
            Transaction transaction,
            ZonedDateTime snapshot,
            String namespace,
            String entity,
            String path,
            byte[] value,
            Range<String> range
    );

    Single<Boolean> hasPrevious(
            Transaction tx,
            ZonedDateTime snapshot,
            String namespace,
            String entityName,
            String id
    );

    Single<Boolean> hasNext(
            Transaction tx,
            ZonedDateTime snapshot,
            String namespace,
            String entityName,
            String id
    );

    void close() throws PersistenceException;
}
