package io.descoped.lds.core.persistence.memory;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.PersistenceDeletePolicy;
import io.descoped.lds.api.persistence.PersistenceException;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.TransactionFactory;
import io.descoped.lds.api.persistence.reactivex.RxPersistence;
import io.descoped.lds.api.persistence.streaming.Fragment;
import io.descoped.lds.api.persistence.streaming.FragmentType;
import io.descoped.tuple.Range;
import io.descoped.tuple.Tuple;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.descoped.lds.core.persistence.memory.OrderedKeyValueTransaction.ROW_LIMIT_UNLIMITED;
import static java.util.Optional.ofNullable;

/**
 * Fragments in a store of ordered keys, the keys packed as tuples. The store is a sorted map in memory
 * ({@link MemoryTransaction}).
 */
public class MemoryRxPersistence implements RxPersistence {

    static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
    static final ZoneId ZONE_ID_UTC = ZoneId.of("Etc/UTC");

    public static final String PRIMARY_INDEX = "Primary";
    public static final String PATH_VALUE_INDEX = "PathValueIndex";

    private static final ZonedDateTime BEGINNING_OF_TIME = ZonedDateTime.of(1, 1, 1, 0, 0, 0, 0, ZONE_ID_UTC);
    private static final ZonedDateTime END_OF_TIME = ZonedDateTime.of(9999, 1, 1, 0, 0, 0, 0, ZONE_ID_UTC);

    public static final KeyValue EMPTY = new KeyValue(new byte[0], new byte[0]);

    final TransactionFactory transactionFactory;
    final MemoryDirectory directory;

    MemoryRxPersistence(TransactionFactory transactionFactory, MemoryDirectory directory) {
        this.transactionFactory = transactionFactory;
        this.directory = directory;
    }

    /**
     * Directory: (NAMESPACE, "Primary", ENTITY)
     * Primary: (ID, (-TIMESTAMP), PATH, OFFSET)  =  VALUE
     *
     * @param namespace
     * @param entity
     * @return
     */
    public Single<? extends Subspace> getPrimary(String namespace, String entity) {
        return Single.fromFuture(directory.createOrOpen(Tuple.of(namespace, PRIMARY_INDEX, entity)));
    }

    /**
     * Directory: (NAMESPACE, "PathIndex", ENTITY, ARRAY-INDEX-UNAWARE-PATH)
     * PathIndex: (VALUE, ID, (-TIMESTAMP), ARRAY-INDICES-FROM-PATH) = EMPTY
     *
     * @param namespace
     * @param entity
     * @param path
     * @return
     */
    public Single<? extends Subspace> getIndex(String namespace, String entity, String path) {
        return Single.fromFuture(directory.createOrOpen(Tuple.of(namespace, PATH_VALUE_INDEX, entity, path)));
    }

    @Override
    public TransactionFactory transactionFactory() throws PersistenceException {
        return transactionFactory;
    }

    @Override
    public Transaction createTransaction(boolean readOnly) throws PersistenceException {
        return transactionFactory.createTransaction(readOnly);
    }

    @Override
    public Completable createOrOverwrite(Transaction tx, Flowable<Fragment> publisher) {
        final OrderedKeyValueTransaction transaction = (OrderedKeyValueTransaction) tx;
        final CopyOnWriteArraySet<Range> clearedRanges = new CopyOnWriteArraySet<>();
        return publisher.flatMapCompletable(fragment -> getPrimary(fragment.namespace(), fragment.entity()).flatMapCompletable(primary -> {
            Tuple fragmentVersion = toTuple(fragment.timestamp());

            // Clear primary of existing document with same version
            Range range = primary.range(Tuple.of(fragment.id(), fragmentVersion));
            if (clearedRanges.add(range)) {
                transaction.clearRange(range, PRIMARY_INDEX);
            }

            // NOTE: With current implementation we do not need to clear the index. False-positive matches in the index
            // are always followed up by a primary lookup. Clearing Index space is expensive as it requires a read to
            // figure out whether there is anything to clear and then another read to get existing document and then finally
            // clearing each document fragment independently from the existing document in the index space which cannot be
            // done with a single range operation and therefore must be done using individual write operations per fragment.

            /*
             * PRIMARY
             */
            Tuple primaryKey = Tuple.of(
                    fragment.id(),
                    fragmentVersion,
                    fragment.path(),
                    new byte[]{fragment.fragmentType().getTypeCode()},
                    fragment.offset()
            );
            byte[] binaryPrimaryKey = primary.pack(primaryKey);
            transaction.set(binaryPrimaryKey, fragment.value(), PRIMARY_INDEX);

            /*
             * INDEX
             */
            ArrayList<Integer> arrayIndices = new ArrayList<>();
            String indexUnawarePath = Fragment.computeIndexUnawarePath(fragment.path(), arrayIndices);
            if (fragment.offset() == 0) {
                return getIndex(fragment.namespace(), fragment.entity(), indexUnawarePath).flatMapCompletable(index -> {
                    Tuple valueIndexKey = Tuple.of(
                            fragment.truncatedValue(),
                            fragment.id(),
                            fragmentVersion,
                            Tuple.of(arrayIndices)
                    );
                    byte[] binaryValueIndexKey = index.pack(valueIndexKey);
                    transaction.set(binaryValueIndexKey, EMPTY_BYTE_ARRAY, PATH_VALUE_INDEX);
                    return Completable.complete();
                });
            }
            return Completable.complete();
        }));
    }

    @Override
    public Flowable<Fragment> read(Transaction tx, ZonedDateTime snapshot, String namespace, String entity, String id) {
        Tuple snapshotTuple = toTuple(snapshot);
        final OrderedKeyValueTransaction transaction = (OrderedKeyValueTransaction) tx;
        return Single.defer(() -> getPrimary(namespace, entity))
                .flatMapPublisher(primary -> findAnyOneMatchingFragmentInPrimary(transaction, primary, id, snapshotTuple)
                        .filter(kv -> primary.contains(kv.key()))
                        .flatMapPublisher(theMatchingFragmentInPrimary -> {
                            Tuple key = primary.unpack(theMatchingFragmentInPrimary.key());
                            Tuple version = key.getNestedTuple(1);
                            FragmentType fragmentType = FragmentType.fromTypeCode(key.getBytes(3)[0]);
                            if (FragmentType.DELETED.equals(fragmentType)) {
                                DocumentKey documentKey = new DocumentKey(namespace, entity, id, toTimestamp(version));
                                Fragment deleteMarkerFragment = new Fragment(documentKey, "", FragmentType.DELETED, 0, EMPTY_BYTE_ARRAY);
                                return Single.just(deleteMarkerFragment).toFlowable();
                            }

                            /*
                             * Get document with given version.
                             */
                            AsyncIterable<KeyValue> asyncIterable = transaction.getRange(primary.range(Tuple.of(id, version)), PRIMARY_INDEX, ROW_LIMIT_UNLIMITED);
                            return Flowable.fromPublisher(new AsyncIterablePublisher<>(asyncIterable))
                                    .map(kv -> toFragment(primary, kv, namespace, entity));
                        })
                );
    }

    static Fragment toFragment(Subspace primary, KeyValue kv, String namespace, String entity) {
        Tuple keyTuple = primary.unpack(kv.key());
        String dbId = keyTuple.getString(0);
        Tuple version = keyTuple.getNestedTuple(1);
        String path = keyTuple.getString(2);
        byte fragmentTypeCode = keyTuple.getBytes(3)[0];
        FragmentType fragmentType = FragmentType.fromTypeCode(fragmentTypeCode);
        long offset = keyTuple.getLong(4);

        DocumentKey documentKey = new DocumentKey(namespace, entity, dbId, toTimestamp(version));
        return new Fragment(documentKey, path, fragmentType, offset, kv.value());
    }

    @Override
    public Flowable<Fragment> readVersions(Transaction tx, String namespace, String entity, String id, io.descoped.lds.api.persistence.reactivex.Range<ZonedDateTime> range) {
        Tuple snapshotFrom = toTuple(ofNullable(range.getAfter()).orElse(BEGINNING_OF_TIME));
        Tuple snapshotTo = toTuple(ofNullable(range.getBefore()).orElse(END_OF_TIME));
        final OrderedKeyValueTransaction transaction = (OrderedKeyValueTransaction) tx;
        return Single.defer(() -> getPrimary(namespace, entity))
                .flatMapPublisher(primary -> findAnyOneMatchingFragmentInPrimary(transaction, primary, id, snapshotFrom)
                        .filter(kv -> primary.contains(kv.key()))
                        .switchIfEmpty(Single.just(EMPTY))
                        .flatMapPublisher(aMatchingKeyValue -> {
                            Tuple lowerBound;
                            if (aMatchingKeyValue == EMPTY || !primary.contains(aMatchingKeyValue.key())) {
                                // no versions older or equals to snapshotFrom exists, use snapshotFrom as lower bound
                                lowerBound = snapshotFrom;
                            } else {
                                Tuple key = primary.unpack(aMatchingKeyValue.key());
                                lowerBound = tickBackward(key.getNestedTuple(1));
                            }

                            /*
                             * Get all fragments of all matching versions.
                             */
                            AsyncIterable<KeyValue> asyncIterable = transaction.getRange(
                                    KeySelector.firstGreaterOrEqual(primary.pack(Tuple.of(id, tickBackward(snapshotTo)))),
                                    KeySelector.firstGreaterOrEqual(primary.pack(Tuple.of(id, lowerBound))),
                                    PRIMARY_INDEX,
                                    ROW_LIMIT_UNLIMITED
                            );

                            return Flowable.fromPublisher(new AsyncIterablePublisher<>(asyncIterable))
                                    .map(kv -> toFragment(primary, kv, namespace, entity));
                        })
                );
    }

    /*
     * The whole primary range of the entity: by id, then by version, newest first (the key holds the negated time),
     * then by path.
     */
    @Override
    public Flowable<Fragment> readAllVersions(Transaction tx, String namespace, String entity) {
        final OrderedKeyValueTransaction transaction = (OrderedKeyValueTransaction) tx;
        return Single.defer(() -> getPrimary(namespace, entity))
                .flatMapPublisher(primary -> Flowable.fromPublisher(
                                new AsyncIterablePublisher<>(transaction.getRange(
                                        KeySelector.firstGreaterOrEqual(primary.range().begin()),
                                        KeySelector.firstGreaterOrEqual(primary.range().end()),
                                        PRIMARY_INDEX,
                                        ROW_LIMIT_UNLIMITED
                                )))
                        .map(kv -> toFragment(primary, kv, namespace, entity)));
    }

    @Override
    public Flowable<Fragment> readAll(Transaction tx, ZonedDateTime snapshot, String namespace, String entity, io.descoped.lds.api.persistence.reactivex.Range<String> range) {
        final OrderedKeyValueTransaction transaction = (OrderedKeyValueTransaction) tx;
        final AtomicReference<String> idRef = new AtomicReference<>();
        final AtomicReference<ZonedDateTime> versionRef = new AtomicReference<>();
        return Single.defer(() -> getPrimary(namespace, entity))
                .flatMapPublisher(primary -> Flowable.fromPublisher(
                                new AsyncIterablePublisher<>(transaction.getRange(
                                        // from the key after every key of the id after, or the start of the entity
                                        KeySelector.firstGreaterOrEqual(range.hasAfter()
                                                ? primary.range(Tuple.of(range.getAfter())).end()
                                                : primary.range().begin()),
                                        // to the first key of the id before, or the end of the entity
                                        KeySelector.firstGreaterOrEqual(range.hasBefore()
                                                ? primary.pack(Tuple.of(range.getBefore()))
                                                : primary.range().end()),
                                        PRIMARY_INDEX,
                                        ROW_LIMIT_UNLIMITED
                                )))
                        .map(kv -> toFragment(primary, kv, namespace, entity))
                        .filter(fragment -> {
                            if (!fragment.id().equals(idRef.get())) {
                                idRef.set(fragment.id());
                                versionRef.set(null);
                            }
                            if (snapshot.isBefore(fragment.timestamp())) {
                                return false;
                            }
                            if (versionRef.get() == null) {
                                versionRef.set(fragment.timestamp());
                            }
                            return fragment.timestamp().equals(versionRef.get());
                        })
                );
    }

    @Override
    public Flowable<Fragment> find(Transaction tx, ZonedDateTime snapshot, String namespace, String
            entity, String path, byte[] value, io.descoped.lds.api.persistence.reactivex.Range<String> range) {
        return Single.defer(() -> getPrimary(namespace, entity)).flatMapPublisher(primary -> {
            String arrayIndexUnawarePath = Fragment.computeIndexUnawarePath(path, new ArrayList<>());
            return getIndex(namespace, entity, arrayIndexUnawarePath).flatMapPublisher(index -> {
                OrderedKeyValueTransaction transaction = (OrderedKeyValueTransaction) tx;
                byte[] truncatedValue = Fragment.hashOf(value);
                AsyncIterablePublisher<KeyValue> indexMatchesPublisher = new AsyncIterablePublisher<>(transaction.getRange(index.range(Tuple.of(truncatedValue)), PATH_VALUE_INDEX, ROW_LIMIT_UNLIMITED));
                AtomicReference<String> idRef = new AtomicReference<>(null);
                return Flowable.fromPublisher(indexMatchesPublisher)
                        .filter(pathValueIndexedKeyValue -> isVisibleAndFirstOfItsResource(index, pathValueIndexedKeyValue, snapshot, idRef))
                        .concatMapEager(pathValueIndexedKeyValue -> readMatchedVersion(transaction, primary, index, pathValueIndexedKeyValue, snapshot, namespace, entity));
            });
        });
    }

    /*
     * Whether an index match is a candidate: its version was visible at snapshot time, and it is not an older version
     * of the resource of the match before it.
     */
    private static boolean isVisibleAndFirstOfItsResource(Subspace index, KeyValue pathValueIndexedKeyValue, ZonedDateTime snapshot, AtomicReference<String> idRef) {
        Tuple key = index.unpack(pathValueIndexedKeyValue.key());
        Tuple matchedVersion = key.getNestedTuple(2);
        if (toTimestamp(matchedVersion).isAfter(snapshot)) {
            return false; // false-positive, version was not yet visible at snapshot time
        }
        String id = key.getString(1);
        if (id.equals(idRef.get())) {
            return false; // false-positive, older version of same resource as previous
        }
        idRef.set(id);
        return true;
    }

    /*
     * The fragments of the version an index match points at, or nothing when that version is not the one visible at
     * snapshot time, or was deleted.
     */
    private Flowable<Fragment> readMatchedVersion(OrderedKeyValueTransaction transaction, Subspace primary, Subspace index, KeyValue pathValueIndexedKeyValue, ZonedDateTime snapshot, String namespace, String entity) {
        Tuple key = index.unpack(pathValueIndexedKeyValue.key());
        Tuple matchedVersion = key.getNestedTuple(2);
        String id = key.getString(1);
        return findAnyOneMatchingFragmentInPrimary(transaction, primary, id, toTuple(snapshot)).flatMapPublisher(aMatchingFragmentKv -> {
            Tuple keyTuple = primary.unpack(aMatchingFragmentKv.key());
            Tuple versionTuple = keyTuple.getNestedTuple(1);
            FragmentType fragmentType = FragmentType.fromTypeCode(keyTuple.getBytes(3)[0]);
            if (!matchedVersion.equals(versionTuple)) {
                return Flowable.empty(); // false-positive index-match on older version
            }
            if (FragmentType.DELETED.equals(fragmentType)) {
                // Version was overwritten in primary by a delete-marker, remove index fragment asynchronously in separate transaction.
                transactionFactory().runAsyncInIsolatedTransaction(otherTx -> {
                    ((OrderedKeyValueTransaction) otherTx).clear(aMatchingFragmentKv.key(), PATH_VALUE_INDEX);
                    return null;
                }, false);
                return Flowable.empty();
            }

            // NOTE It's possible to get false-positive due to either index-value-truncation or value occupying
            // multiple key-value slots. These must be discarded by client or the buffered persistence layer.

            AsyncIterablePublisher<KeyValue> resourcePublisher = new AsyncIterablePublisher<>(transaction.getRange(primary.range(Tuple.of(id, matchedVersion)), PRIMARY_INDEX, ROW_LIMIT_UNLIMITED));
            return Flowable.fromPublisher(resourcePublisher).map(kv -> toFragment(primary, kv, namespace, entity));
        });
    }

    @Override
    public Single<Boolean> hasPrevious(Transaction tx, ZonedDateTime snapshot, String namespace, String
            entityName, String id) {
        return readAll(tx, snapshot, namespace, entityName, io.descoped.lds.api.persistence.reactivex.Range.lastBefore(1, id)).isEmpty()
                .map(wasEmpty -> !wasEmpty);
    }

    @Override
    public Single<Boolean> hasNext(Transaction tx, ZonedDateTime snapshot, String namespace, String
            entityName, String id) {
        return readAll(tx, snapshot, namespace, entityName, io.descoped.lds.api.persistence.reactivex.Range.firstAfter(1, id)).isEmpty()
                .map(wasEmpty -> !wasEmpty);
    }

    /**
     * Determine the correct first version timestamp. Perform a single database access and get range with 0 or 1 key-value.
     *
     * @param transaction
     * @param primary
     * @param id
     * @param snapshot
     * @return a completable-future that will return null if document is not found.
     */
    static Maybe<KeyValue> findAnyOneMatchingFragmentInPrimary(OrderedKeyValueTransaction transaction, Subspace
            primary, String id, Tuple snapshot) {
        /*
         * The range specified is guaranteed to never return more than 1 result. The returned KeyValue list will be one of:
         *   (1) Last fragment of matching resource when resource exists and client-timestamp is greater than or equal to resource timestamp
         *   (2) Last fragment of an unrelated resource when resource does not exist for the specified timestamp
         *   (3) KeyValue of another key-space than PRIMARY
         *   (4) Empty when there are no resources with the given id that are an older (or same) version than snapshot
         */

        AsyncIterable<KeyValue> version = transaction.getRange(
                KeySelector.firstGreaterOrEqual(primary.pack(Tuple.of(id, snapshot))),
                KeySelector.firstGreaterOrEqual(primary.range(Tuple.of(id)).end()),
                PRIMARY_INDEX,
                1
        );

        return Flowable.fromPublisher(new AsyncIterablePublisher<>(version)).firstElement().filter(kv -> {
            if (!primary.contains(kv.key())) {
                // (3) KeyValue of another key-space than PRIMARY
                return false;
            }

            Tuple keyTuple = primary.unpack(kv.key());
            String resourceId = keyTuple.getString(0);
            // (1) Match found, or (2) fragment of an unrelated resource
            return id.equals(resourceId);
        });
    }

    @Override
    public Completable delete(Transaction transaction, String namespace, String entity, String
            id, ZonedDateTime version, PersistenceDeletePolicy policy) throws PersistenceException {
        return doDelete((OrderedKeyValueTransaction) transaction, namespace, entity, id, toTuple(version));
    }

    private Completable doDelete(OrderedKeyValueTransaction transaction, String namespace, String
            entity, String resourceId, Tuple resourceVersion) {
        return Single.defer(() -> getPrimary(namespace, entity).doOnSuccess(primary -> {
            /*
             * Get all fragments of given versioned resource.
             */
            Range range = primary.range(Tuple.of(resourceId, resourceVersion));
            Flowable.fromPublisher(new AsyncIterablePublisher<>(transaction.getRange(range, PRIMARY_INDEX, ROW_LIMIT_UNLIMITED)))
                    .flatMapCompletable(kv -> deleteFragmentFromPathValueIndex(transaction, namespace, entity, primary, kv));
            transaction.clearRange(range, PRIMARY_INDEX);
        })).ignoreElement();
    }

    private Completable deleteFragmentFromPathValueIndex(OrderedKeyValueTransaction transaction, String namespace, String entity, Subspace primary, KeyValue kv) {
        Tuple key = primary.unpack(kv.key());
        String id = key.getString(0);
        Tuple version = key.getNestedTuple(1);
        String path = key.getString(2);
        long offset = key.getLong(4);

        if (offset > 0) {
            return Completable.complete();
        }

        ArrayList<Integer> indices = new ArrayList<>();
        String indexUnawarePath = Fragment.computeIndexUnawarePath(path, indices);
        Tuple arrayIndices = Tuple.of(indices);
        byte[] truncatedValue = Fragment.hashOf(kv.value());
        Tuple valueIndexKey = Tuple.of(
                truncatedValue,
                id,
                version,
                arrayIndices
        );

        return getIndex(namespace, entity, indexUnawarePath).doOnSuccess(index -> {
            byte[] binaryValueIndexKey = index.pack(valueIndexKey);
            transaction.clear(binaryValueIndexKey, PATH_VALUE_INDEX);
        }).ignoreElement();
    }

    @Override
    public Completable deleteAllVersions(Transaction transaction, String namespace, String
            entity, String id, PersistenceDeletePolicy policy) throws PersistenceException {
        return doDeleteAllVersions((OrderedKeyValueTransaction) transaction, namespace, entity, id);
    }

    private Completable doDeleteAllVersions(OrderedKeyValueTransaction transaction, String
            namespace, String entity, String resourceId) {
        return Completable.defer(() -> getPrimary(namespace, entity)
                .flatMapCompletable(primary -> Flowable.fromPublisher(new AsyncIterablePublisher<>(transaction.getRange(primary.range(Tuple.of(resourceId)), PRIMARY_INDEX, ROW_LIMIT_UNLIMITED)))
                        .flatMapCompletable(kv -> deleteFragmentFromPathValueIndex(transaction, namespace, entity, primary, kv))
                        .doOnComplete(() -> transaction.clearRange(primary.range(Tuple.of(resourceId)), PRIMARY_INDEX))));
    }

    @Override
    public Completable deleteAllEntities(Transaction transaction, String namespace, String entity, Iterable<String> paths) {
        return doDeleteAllEntities((OrderedKeyValueTransaction) transaction, namespace, entity, paths);
    }

    private Completable doDeleteAllEntities(OrderedKeyValueTransaction transaction, String namespace, String entity, Iterable<String> paths) {
        return Completable.merge(List.of(
                Completable.defer(() -> getPrimary(namespace, entity).flatMapCompletable(primary -> {
                    transaction.clearRange(primary.range(), PRIMARY_INDEX);
                    return Completable.complete();
                })),
                Flowable.fromIterable(paths).flatMapCompletable(path -> getIndex(namespace, entity, path).flatMapCompletable(index -> {
                    transaction.clearRange(index.range(), PATH_VALUE_INDEX);
                    return Completable.complete();
                }))
        ));
    }

    @Override
    public Completable markDeleted(Transaction transaction, String namespace, String entity, String
            id, ZonedDateTime version, PersistenceDeletePolicy policy) throws PersistenceException {
        return doMarkDeleted((OrderedKeyValueTransaction) transaction, toTuple(version), namespace, entity, id, policy);
    }

    Completable doMarkDeleted(OrderedKeyValueTransaction transaction, Tuple version, String
            namespace, String entity, String id, PersistenceDeletePolicy policy) {
        return getPrimary(namespace, entity).doOnSuccess(primary -> {
            // Clear primary of existing document with same version
            transaction.clearRange(primary.range(Tuple.of(id, version)), PRIMARY_INDEX);

            /*
             * Insert delete marker in PRIMARY
             */
            Tuple primaryKey = Tuple.of(
                    id,
                    version,
                    "",
                    new byte[]{FragmentType.DELETED.getTypeCode()},
                    0
            );
            byte[] binaryPrimaryKey = primary.pack(primaryKey);
            transaction.set(binaryPrimaryKey, EMPTY_BYTE_ARRAY, PRIMARY_INDEX);
        }).ignoreElement();
    }

    @Override
    public void close() throws PersistenceException {
        transactionFactory.close();
    }

    public static Tuple toTuple(ZonedDateTime timestamp) {
        return Tuple.of(
                -timestamp.getYear(),
                -timestamp.getMonth().getValue(),
                -timestamp.getDayOfMonth(),
                -timestamp.getHour(),
                -timestamp.getMinute(),
                -timestamp.getSecond(),
                -TimeUnit.NANOSECONDS.toMillis(timestamp.getNano())
        );
    }

    public static ZonedDateTime toTimestamp(Tuple timestampTuple) {
        return ZonedDateTime.of(
                (int) -timestampTuple.getLong(0),
                (int) -timestampTuple.getLong(1),
                (int) -timestampTuple.getLong(2),
                (int) -timestampTuple.getLong(3),
                (int) -timestampTuple.getLong(4),
                (int) -timestampTuple.getLong(5),
                (int) -TimeUnit.MILLISECONDS.toNanos(timestampTuple.getLong(6)),
                ZONE_ID_UTC
        );
    }

    static Tuple tickForward(Tuple documentTimestampTuple) {
        return withoutLast(documentTimestampTuple).add(documentTimestampTuple.getLong(6) - 1);
    }

    static Tuple tickBackward(Tuple documentTimestampTuple) {
        return withoutLast(documentTimestampTuple).add(documentTimestampTuple.getLong(6) + 1);
    }

    private static Tuple withoutLast(Tuple tuple) {
        return Tuple.fromItems(tuple.items().subList(0, tuple.size() - 1));
    }


}
