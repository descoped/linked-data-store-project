package io.descoped.lds.persistence.api;

import io.descoped.lds.api.json.JsonNavigationPath;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Versioned JSON documents in an engine. The rules that hold for every provider are in the
 * {@linkplain io.descoped.lds.persistence.api package description}.
 *
 * <p>Every method that takes names throws {@link IllegalArgumentException} for a name that {@link Identifiers}
 * rejects, {@link IllegalStateException} for a transaction that is committed, closed or (for a write) read-only, and
 * {@link PersistenceException} when the engine fails. A {@code Persistence} may be used by many threads at once,
 * each with its own {@link Transaction}.
 */
public interface Persistence extends AutoCloseable {

    /**
     * Starts a transaction.
     *
     * @param readOnly {@code true} when the transaction will only read, which lets a provider avoid locks and
     *                 conflicts; {@code false} when it will or may write
     * @return the transaction, to be closed by the caller
     */
    Transaction begin(boolean readOnly);

    /**
     * Reads a document as it was at a point in time.
     *
     * @param tx        the transaction
     * @param snapshot  the point in time
     * @param namespace the namespace
     * @param entity    the entity name
     * @param id        the document id
     * @return the newest version not after the snapshot, or empty when there is none or it is a delete marker
     */
    Optional<Document> read(Transaction tx, Instant snapshot, String namespace, String entity, String id);

    /**
     * Lists the documents of an entity as they were at a point in time.
     *
     * @param tx        the transaction
     * @param snapshot  the point in time
     * @param namespace the namespace
     * @param entity    the entity name
     * @param range     which ids to return
     * @return for each id in the range, what {@link #read read} returns for it, ordered by id
     */
    List<Document> list(Transaction tx, Instant snapshot, String namespace, String entity, Range<String> range);

    /**
     * Lists the versions of a document, delete markers included.
     *
     * @param tx        the transaction
     * @param namespace the namespace
     * @param entity    the entity name
     * @param id        the document id
     * @param range     which versions to return
     * @return the versions, ordered by time
     */
    List<Document> versions(Transaction tx, String namespace, String entity, String id, Range<Instant> range);

    /**
     * Finds the documents of an entity that, at a point in time, have a string value at a path.
     *
     * @param tx        the transaction
     * @param snapshot  the point in time
     * @param namespace the namespace
     * @param entity    the entity name
     * @param path      where the value is; an array element without an index matches any element
     * @param value     the string the value must equal
     * @param range     which ids to return
     * @return the matching documents as {@link #read read} returns them, ordered by id
     */
    List<Document> find(Transaction tx, Instant snapshot, String namespace, String entity, JsonNavigationPath path,
                        String value, Range<String> range);

    /**
     * Follows the links at a path of one document to the documents of one entity.
     *
     * @param tx           the transaction
     * @param snapshot     the point in time, for the source and the targets
     * @param namespace    the namespace
     * @param sourceEntity the entity name of the document that holds the links
     * @param sourceId     its id
     * @param path         where the links are in the source document
     * @param targetEntity the entity name of the documents linked to; links to other entities are left out
     * @param range        which target ids to return
     * @return the documents linked to that exist at the snapshot, each once, ordered by id
     */
    List<Document> targets(Transaction tx, Instant snapshot, String namespace, String sourceEntity, String sourceId,
                           JsonNavigationPath path, String targetEntity, Range<String> range);

    /**
     * Finds the documents of one entity that link to a document at a path.
     *
     * @param tx           the transaction
     * @param snapshot     the point in time, for the sources
     * @param namespace    the namespace
     * @param targetEntity the entity name of the document linked to
     * @param targetId     its id
     * @param path         where the links are in the source documents
     * @param sourceEntity the entity name of the documents that hold the links
     * @param range        which source ids to return
     * @return the documents that link to the target at the snapshot, ordered by id
     */
    default List<Document> sources(Transaction tx, Instant snapshot, String namespace, String targetEntity,
                                   String targetId, JsonNavigationPath path, String sourceEntity,
                                   Range<String> range) {
        return find(tx, snapshot, namespace, sourceEntity, path, new Link(targetEntity, targetId).toString(), range);
    }

    /**
     * Whether an entity has a document after an id at a point in time.
     *
     * @param tx        the transaction
     * @param snapshot  the point in time
     * @param namespace the namespace
     * @param entity    the entity name
     * @param id        the id to look after
     * @return {@code true} when {@link #list list} has a document after the id
     */
    default boolean hasNext(Transaction tx, Instant snapshot, String namespace, String entity, String id) {
        return !list(tx, snapshot, namespace, entity, Range.firstAfter(1, id)).isEmpty();
    }

    /**
     * Whether an entity has a document before an id at a point in time.
     *
     * @param tx        the transaction
     * @param snapshot  the point in time
     * @param namespace the namespace
     * @param entity    the entity name
     * @param id        the id to look before
     * @return {@code true} when {@link #list list} has a document before the id
     */
    default boolean hasPrevious(Transaction tx, Instant snapshot, String namespace, String entity, String id) {
        return !list(tx, snapshot, namespace, entity, Range.lastBefore(1, id)).isEmpty();
    }

    /**
     * Stores versions of documents. A version that exists is replaced, whether it was content or a delete marker.
     *
     * @param tx        the transaction
     * @param documents the versions to store, none of them a delete marker
     * @throws IllegalArgumentException if a document has no content
     */
    void write(Transaction tx, Collection<Document> documents);

    /**
     * Stores a delete marker: from this version on the document does not exist, until a later version. A version
     * that exists at the same instant is replaced.
     *
     * @param tx  the transaction
     * @param key the document and the instant it is deleted from
     */
    void markDeleted(Transaction tx, DocumentKey key);

    /**
     * Removes one version, content or delete marker, as if it had never been stored. Does nothing when there is no
     * such version.
     *
     * @param tx  the transaction
     * @param key the version to remove
     */
    void deleteVersion(Transaction tx, DocumentKey key);

    /**
     * Removes every version of a document.
     *
     * @param tx        the transaction
     * @param namespace the namespace
     * @param entity    the entity name
     * @param id        the document id
     */
    void deleteAllVersions(Transaction tx, String namespace, String entity, String id);

    /**
     * Removes every version of every document of an entity in a namespace.
     *
     * @param tx        the transaction
     * @param namespace the namespace
     * @param entity    the entity name
     */
    void deleteEntity(Transaction tx, String namespace, String entity);

    /**
     * Releases the connections and other resources this persistence holds. Transactions that are still open are
     * rolled back.
     */
    @Override
    void close();
}
