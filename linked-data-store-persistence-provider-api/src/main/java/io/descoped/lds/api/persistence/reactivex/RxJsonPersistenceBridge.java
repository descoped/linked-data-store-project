package io.descoped.lds.api.persistence.reactivex;

import io.descoped.lds.api.json.JsonNavigationPath;
import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.IdOrder;
import io.descoped.lds.api.persistence.PersistenceDeletePolicy;
import io.descoped.lds.api.persistence.PersistenceException;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.flattened.FlattenedDocument;
import io.descoped.lds.api.persistence.flattened.FlattenedDocumentLeafNode;
import io.descoped.lds.api.persistence.json.FlattenedDocumentToJson;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.json.JsonToFlattenedDocument;
import io.descoped.lds.api.persistence.streaming.Fragment;
import io.descoped.lds.api.persistence.streaming.FragmentType;
import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.api.specification.SpecificationElement;
import io.descoped.lds.api.specification.SpecificationTraversal;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Optional.ofNullable;

/**
 *
 */
public class RxJsonPersistenceBridge implements RxJsonPersistence {

    private static final Pattern LINK_PATTERN = Pattern.compile("/(?<entity>[^/]*)/(?<id>[^/]*)");
    private final RxPersistence persistence;
    private final int fragmentSize;

    public RxJsonPersistenceBridge(RxPersistence persistence, int fragmentSize) {
        this.persistence = Objects.requireNonNull(persistence);
        this.fragmentSize = fragmentSize;
    }

    static Maybe<JsonDocument> doReadDocument(Flowable<Fragment> fragments, int fragmentSize) {
        return toDocuments(fragments, fragmentSize, false).firstElement();
    }

    /**
     * Common logic for cursor pagination.
     * <p>
     * The {@link Flowable<E>} must be ordered so that the first or last value of the {@link Range} always represent
     * the first n elements.
     * <p>
     * Strictly speaking, we could have only one implementation since [first:x, after:y] = [last:x, before:y] if the
     * stream is reversed. Maybe something to consider later.
     */
    static <E, T> Flowable<E> limit(
            Flowable<E> fragments, Function<E, T> keyExtractor, Range<T> range, Comparator<? super T> order) {
        if (range.isBackward()) {
            // Flowable MUST return values in reverse order.
            if (range.hasBefore()) {
                fragments = fragments.skipWhile(fragment ->
                        order.compare(keyExtractor.apply(fragment), range.getBefore()) >= 0);
            }
            if (range.hasAfter()) {
                fragments = fragments.takeWhile(fragment ->
                        order.compare(keyExtractor.apply(fragment), range.getAfter()) > 0);
            }
            if (range.hasLast()) {
                fragments = fragments.take(range.getLast());
            }
        } else {
            if (range.hasAfter()) {
                fragments = fragments.skipWhile(fragment ->
                        order.compare(keyExtractor.apply(fragment), range.getAfter()) <= 0);
            }
            if (range.hasBefore()) {
                fragments = fragments.takeWhile(fragment ->
                        order.compare(keyExtractor.apply(fragment), range.getBefore()) < 0);
            }
            if (range.hasFirst()) {
                fragments = fragments.take(range.getFirst());
            }
        }
        return fragments;
    }

    /**
     * Convert a flowable of {@link Fragment}s to a flowable of {@link JsonDocument}.
     * <p>
     * The received fragments must be ordered by id <strong>before</strong> it is ordered by path..
     */
    static Flowable<JsonDocument> toDocuments(Flowable<Fragment> fragmentFlowable, int fragmentSize, boolean includeDeleted) {
        return toFlattenedDocuments(fragmentFlowable, fragmentSize).filter(flattenedDocument -> {
            // Filter out the deleted documents.
            return includeDeleted || !flattenedDocument.deleted();

        }).map(flattenedDocument -> {
            // Convert to JsonDocument.
            return new JsonDocument(
                    flattenedDocument.key(),
                    new FlattenedDocumentToJson(flattenedDocument).toJsonNode()
            );
        });
    }

    /*
     * One document for each group of fragments with the same key, asking the source for more one document at a time.
     */
    private static Flowable<FlattenedDocument> toFlattenedDocuments(Flowable<Fragment> fragmentFlowable, int fragmentSize) {
        return RxGroupByTools.groupByOrdered(
                fragmentFlowable.takeWhile(fragment -> !fragment.isStreamingControl()),
                DocumentKey::from
        ).concatMap(group -> group.flowable()
                .toMultimap(Fragment::path)
                .map(map -> FlattenedDocument.decodeDocument(group.key(), map, fragmentSize))
                .toFlowable());
    }

    static Flowable<JsonDocument> doReadDocuments(Flowable<Fragment> fragments, Range<String> range, int fragmentSize) {
        Flowable<JsonDocument> documents = toDocuments(fragments, fragmentSize, false);
        return limit(documents, d -> d.key().id(), range, IdOrder.CODE_POINTS);
    }

    static Flowable<JsonDocument> doReadDocumentVersions(Flowable<Fragment> fragments, Range<ZonedDateTime> range, int fragmentSize) {
        Flowable<JsonDocument> documents = toDocuments(fragments, fragmentSize, true);
        // ZonedDateTime is a bad choice for the API: internal temporal values should be Instant.
        Range<Instant> instantRange = Range.copy(range, ZonedDateTime::toInstant);
        return limit(documents, document -> document.key().timestamp().toInstant(), instantRange, Comparator.naturalOrder());
    }

    @Override
    public Maybe<JsonDocument> readDocument(Transaction tx, ZonedDateTime snapshot, String ns, String entityName, String id) {
        Flowable<Fragment> fragments = persistence.read(tx, snapshot, ns, entityName, id);
        return doReadDocument(fragments, fragmentSize);
    }

    @Override
    public Flowable<JsonDocument> readDocuments(Transaction tx, ZonedDateTime snapshot, String ns, String entityName, Range<String> range) {
        // The store gives the documents in the order of the range and leaves out the deleted ones, so it may apply
        // the limit.
        return doReadDocuments(persistence.readAll(tx, snapshot, ns, entityName, range), range, fragmentSize);
    }

    @Override
    public Flowable<JsonDocument> readDocumentVersions(Transaction tx, String ns, String entityName, String id,
                                                       Range<ZonedDateTime> range) {
        // Without the limit: the first version the store gives is the one valid at after, which the limit here leaves
        // out. The store gives the versions in the order of the range.
        Flowable<Fragment> fragments = persistence.readVersions(tx, ns, entityName, id, Range.unlimited(range));
        return doReadDocumentVersions(fragments, range, fragmentSize);
    }

    /*
     * The store gives the fragments by id, version and path, so they are cut into documents as they come: no sort.
     */
    @Override
    public Flowable<JsonDocument> readAllDocumentVersions(Transaction tx, String ns, String entityName) {
        return toFlattenedDocuments(persistence.readAllVersions(tx, ns, entityName), fragmentSize)
                .map(flattenedDocument -> flattenedDocument.deleted()
                        ? new JsonDocument(flattenedDocument.key(), (JsonNode) null)
                        : new JsonDocument(flattenedDocument.key(), new FlattenedDocumentToJson(flattenedDocument).toJsonNode()));
    }

    @Override
    public Flowable<JsonDocument> findDocument(Transaction tx, ZonedDateTime snapshot, String namespace,
                                               String entityName, JsonNavigationPath navigationPath, String value, Range<String> range) {
        // Only a text value is supported.
        Map<Integer, byte[]> valueByOffset = FlattenedDocumentLeafNode.valueByOffset(FragmentType.STRING, fragmentSize, value);
        byte[] bytesValue = valueByOffset.get(0);
        // Without the limit: a false match the store gives is left out before the limit, so it takes no place in
        // the page. The store gives the documents in the order of the range.
        Flowable<Fragment> fragments = persistence.find(tx, snapshot, namespace, entityName, navigationPath.serialize(), bytesValue,
                Range.unlimited(range));
        Flowable<JsonDocument> matches = toDocuments(fragments, fragmentSize, false).filter(document -> {
            // A store can give a false match: a value of another type, or a text that only begins the same.
            AtomicBoolean match = new AtomicBoolean(false);
            document.traverseField(navigationPath, (node, _) -> {
                if (node.isString() && node.stringValue().equals(value)) {
                    match.set(true);
                }
            });
            return match.get();
        });
        return limit(matches, d -> d.key().id(), range, IdOrder.CODE_POINTS);
    }

    @Override
    public Flowable<JsonDocument> readTargetDocuments(Transaction tx, ZonedDateTime snapshot, LinkQuery query,
                                                      Range<String> range) {
        String ns = query.namespace();
        JsonNavigationPath jsonNavigationPath = query.relationPath();
        String targetEntityName = query.otherEntity();
        // The whole source document is read from RxPersistence, not only what is at the path.
        return readDocument(tx, snapshot, ns, query.entity(), query.id())
                .flattenAsFlowable(document -> {
                    List<String> links = new ArrayList<>();
                    document.traverseField(jsonNavigationPath, (node, _) -> {
                        if (!node.isString()) {
                            // a link is a text
                            return;
                        }
                        String link = node.stringValue();
                        Matcher m = LINK_PATTERN.matcher(link);
                        if (!m.matches()) {
                            return;
                        }
                        if (!targetEntityName.equals(m.group("entity"))) {
                            return;
                        }
                        links.add(m.group("id"));
                    });
                    return links;
                })
                .sorted((o1, o2) -> range.isBackward() ? IdOrder.compare(o2, o1) : IdOrder.compare(o1, o2))
                .take(ofNullable(range).map(Range::getLimit).orElse(Integer.MAX_VALUE))
                .concatMapMaybe(targetId -> readDocument(tx, snapshot, ns, targetEntityName, targetId));
    }

    @Override
    public Flowable<JsonDocument> readSourceDocuments(Transaction tx, ZonedDateTime snapshot, LinkQuery query,
                                                      Range<String> range) {
        // Uses find to implement back links.
        byte[] idValue = String.format("/%s/%s", query.entity(), query.id()).getBytes();
        Flowable<Fragment> fragments = persistence.find(tx, snapshot, query.namespace(), query.otherEntity(),
                query.relationPath().serialize(), idValue, range);
        return doReadDocuments(fragments, range, fragmentSize);
    }

    @Override
    public Completable createOrOverwrite(Transaction tx, Flowable<JsonDocument> documentFlowable, Specification specification) {
        return persistence.createOrOverwrite(tx, documentFlowable.concatMap(document -> {
            DocumentKey key = document.key();
            JsonNode json = document.jackson();
            JsonToFlattenedDocument converter = new JsonToFlattenedDocument(key.namespace(), key.entity(), key.id(),
                    key.timestamp(), json, fragmentSize);
            return Flowable.fromIterable(() -> converter.toDocument().fragmentIterator());
        }));
    }

    @Override
    public Completable deleteDocument(Transaction tx, String ns, String entityName, String id, ZonedDateTime
            version, PersistenceDeletePolicy policy) {
        return persistence.delete(tx, ns, entityName, id, version, policy);
    }

    @Override
    public Completable deleteAllDocumentVersions(Transaction tx, String ns, String entity, String
            id, PersistenceDeletePolicy policy) {
        return persistence.deleteAllVersions(tx, ns, entity, id, policy);
    }

    @Override
    public Completable deleteAllEntities(Transaction tx, String namespace, String entity, Specification
            specification) {
        List<String> paths = new ArrayList<>();
        SpecificationElement entityElement = JsonNavigationPath.from("$").toSpecificationElement(specification, entity);
        SpecificationTraversal.depthFirstPreOrderFullTraversal(entityElement, (_, element) -> paths.add(JsonNavigationPath.from(element).serialize()));
        return persistence.deleteAllEntities(tx, namespace, entity, paths);
    }

    @Override
    public Completable markDocumentDeleted(Transaction transaction, String ns, String entityName, String
            id, ZonedDateTime version, PersistenceDeletePolicy policy) {
        return persistence.markDeleted(transaction, ns, entityName, id, version, policy);
    }

    @Override
    public Single<Boolean> hasPrevious(Transaction tx, ZonedDateTime snapshot, String ns, String entityName, String
            id) {
        return readDocuments(tx, snapshot, ns, entityName, Range.lastBefore(1, id)).isEmpty()
                .map(wasEmpty -> !wasEmpty);
    }

    @Override
    public Single<Boolean> hasNext(Transaction tx, ZonedDateTime snapshot, String ns, String entityName, String id) {
        return readDocuments(tx, snapshot, ns, entityName, Range.firstAfter(1, id)).isEmpty()
                .map(wasEmpty -> !wasEmpty);
    }

    @Override
    public Transaction createTransaction(boolean readOnly) throws PersistenceException {
        return persistence.createTransaction(readOnly);
    }

    @Override
    public void close() throws PersistenceException {
        persistence.close();
    }
}
