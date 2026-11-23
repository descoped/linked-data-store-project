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

    Flowable<Fragment> readAll(
            Transaction transaction,
            ZonedDateTime snapshot,
            String namespace,
            String entity,
            Range<String> range
    );

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
