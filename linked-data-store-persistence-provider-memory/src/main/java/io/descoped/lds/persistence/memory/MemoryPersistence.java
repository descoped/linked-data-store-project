package io.descoped.lds.persistence.memory;

import com.fasterxml.jackson.databind.JsonNode;
import io.descoped.lds.api.json.JsonNavigationPath;
import io.descoped.lds.persistence.api.Document;
import io.descoped.lds.persistence.api.DocumentKey;
import io.descoped.lds.persistence.api.Identifiers;
import io.descoped.lds.persistence.api.Link;
import io.descoped.lds.persistence.api.Persistence;
import io.descoped.lds.persistence.api.Range;
import io.descoped.lds.persistence.api.Transaction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The persistence over a {@link MemoryStore}. Every method takes the store's lock for its own length.
 */
final class MemoryPersistence implements Persistence {

    /** The stored value of a delete marker. */
    private static final Object DELETE_MARKER = new Object();

    private final MemoryStore store = new MemoryStore();

    @Override
    public Transaction begin(boolean readOnly) {
        requireOpen();
        return new MemoryTransaction(store, readOnly);
    }

    @Override
    public Optional<Document> read(Transaction tx, Instant snapshot, String namespace, String entity, String id) {
        requireNames(namespace, entity, id);
        Objects.requireNonNull(snapshot, "snapshot");
        return reading(tx, view -> Optional.ofNullable(current(view, snapshot, namespace, entity, id)));
    }

    @Override
    public List<Document> list(Transaction tx, Instant snapshot, String namespace, String entity,
                               Range<String> range) {
        requireNames(namespace, entity);
        Objects.requireNonNull(snapshot, "snapshot");
        requireBounds(range);
        return reading(tx, view -> {
            io.descoped.tuple.Range all = Keys.entity(namespace, entity);
            byte[] begin = range.after() == null ? all.begin() : Keys.document(namespace, entity, range.after()).end();
            byte[] end = range.before() == null ? all.end() : Keys.document(namespace, entity, range.before()).begin();
            Instant notAfter = DocumentKey.truncate(snapshot);
            List<Document> documents = new ArrayList<>();
            Iterator<Map.Entry<byte[], Object>> versions = store.scan(view.overlay, begin, end, range.backward());
            // Forward, a document's versions come newest first: the first one not after the snapshot is current.
            // Backward they come oldest first: the last one not after the snapshot is.
            DocumentKey candidate = null;
            Object candidateValue = null;
            String decided = null;
            while (versions.hasNext() && documents.size() < range.limit()) {
                Map.Entry<byte[], Object> version = versions.next();
                DocumentKey key = Keys.documentKey(version.getKey());
                if (candidate != null && !candidate.id().equals(key.id())) {
                    add(documents, candidate, candidateValue);
                    candidate = null;
                }
                if (key.id().equals(decided) || key.version().isAfter(notAfter)) {
                    continue;
                }
                if (range.backward()) {
                    candidate = key;
                    candidateValue = version.getValue();
                } else {
                    decided = key.id();
                    add(documents, key, version.getValue());
                }
            }
            if (candidate != null && documents.size() < range.limit()) {
                add(documents, candidate, candidateValue);
            }
            return documents;
        });
    }

    @Override
    public List<Document> versions(Transaction tx, String namespace, String entity, String id, Range<Instant> range) {
        requireNames(namespace, entity, id);
        Objects.requireNonNull(range, "range");
        return reading(tx, view -> {
            io.descoped.tuple.Range all = Keys.document(namespace, entity, id);
            List<Document> ascending = new ArrayList<>();
            store.scan(view.overlay, all.begin(), all.end(), true).forEachRemaining(version -> ascending.add(
                    document(Keys.documentKey(version.getKey()), version.getValue())));
            return range.map(DocumentKey::truncate)
                    .select(ascending, document -> document.key().version(), Instant::compareTo);
        });
    }

    @Override
    public List<Document> find(Transaction tx, Instant snapshot, String namespace, String entity,
                               JsonNavigationPath path, String value, Range<String> range) {
        requireNames(namespace, entity);
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(value, "value");
        requireBounds(range);
        return reading(tx, view -> {
            // The recorded values name candidates only: another value can share the hash, and the version that
            // had the value need not be the one the snapshot sees. Each candidate is checked against its document.
            io.descoped.tuple.Range recorded = Keys.values(namespace, entity, Keys.path(path), value);
            TreeSet<String> ids = new TreeSet<>(Identifiers.ID_ORDER);
            store.scan(view.overlay, recorded.begin(), recorded.end(), false)
                    .forEachRemaining(entry -> ids.add(Keys.valueId(entry.getKey())));
            List<Document> matches = new ArrayList<>();
            for (String id : ids) {
                Document document = current(view, snapshot, namespace, entity, id);
                if (document != null && document.values(path).stream()
                        .anyMatch(node -> node.isTextual() && node.textValue().equals(value))) {
                    matches.add(document);
                }
            }
            return range.select(matches, document -> document.key().id(), Identifiers.ID_ORDER);
        });
    }

    @Override
    public List<Document> targets(Transaction tx, Instant snapshot, String namespace, String sourceEntity,
                                  String sourceId, JsonNavigationPath path, String targetEntity,
                                  Range<String> range) {
        requireNames(namespace, sourceEntity, sourceId);
        Identifiers.requireEntity(targetEntity);
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(path, "path");
        requireBounds(range);
        return reading(tx, view -> {
            Document source = current(view, snapshot, namespace, sourceEntity, sourceId);
            if (source == null) {
                return List.of();
            }
            TreeSet<String> ids = new TreeSet<>(Identifiers.ID_ORDER);
            for (JsonNode node : source.values(path)) {
                Link.parse(node.textValue()).filter(link -> link.entity().equals(targetEntity))
                        .ifPresent(link -> ids.add(link.id()));
            }
            List<Document> existing = new ArrayList<>();
            for (String id : ids) {
                Document target = current(view, snapshot, namespace, targetEntity, id);
                if (target != null) {
                    existing.add(target);
                }
            }
            return range.select(existing, document -> document.key().id(), Identifiers.ID_ORDER);
        });
    }

    @Override
    public void write(Transaction tx, Collection<Document> documents) {
        for (Document document : documents) {
            if (document.deleted()) {
                throw new IllegalArgumentException("A delete marker is stored with markDeleted: " + document.key());
            }
        }
        writing(tx, view -> {
            for (Document document : documents) {
                DocumentKey key = document.key();
                JsonNode content = document.json().deepCopy();
                forget(view, key);
                view.overlay.put(Keys.version(key), content);
                Keys.strings(content, "$", (path, value) -> view.overlay.put(Keys.value(key, path, value), true));
            }
        });
    }

    @Override
    public void markDeleted(Transaction tx, DocumentKey key) {
        Objects.requireNonNull(key, "key");
        writing(tx, view -> {
            forget(view, key);
            view.overlay.put(Keys.version(key), DELETE_MARKER);
        });
    }

    @Override
    public void deleteVersion(Transaction tx, DocumentKey key) {
        Objects.requireNonNull(key, "key");
        writing(tx, view -> forget(view, key));
    }

    @Override
    public void deleteAllVersions(Transaction tx, String namespace, String entity, String id) {
        requireNames(namespace, entity, id);
        writing(tx, view -> {
            io.descoped.tuple.Range all = Keys.document(namespace, entity, id);
            List<DocumentKey> keys = new ArrayList<>();
            store.scan(view.overlay, all.begin(), all.end(), false)
                    .forEachRemaining(version -> keys.add(Keys.documentKey(version.getKey())));
            keys.forEach(key -> forget(view, key));
        });
    }

    @Override
    public void deleteEntity(Transaction tx, String namespace, String entity) {
        requireNames(namespace, entity);
        writing(tx, view -> {
            for (io.descoped.tuple.Range range : List.of(Keys.entity(namespace, entity), Keys.values(namespace, entity))) {
                List<byte[]> keys = new ArrayList<>();
                store.scan(view.overlay, range.begin(), range.end(), false)
                        .forEachRemaining(entry -> keys.add(entry.getKey()));
                keys.forEach(key -> view.overlay.put(key, MemoryStore.REMOVED));
            }
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>A transaction that is still open is rolled back the next time it is used: it fails, commit included.
     */
    @Override
    public void close() {
        store.closed = true;
    }

    /** How many keys are committed, documents and recorded values together. */
    int committedKeys() {
        store.lock.readLock().lock();
        try {
            return store.size();
        } finally {
            store.lock.readLock().unlock();
        }
    }

    /** The version a snapshot sees, or null when there is none or it is a delete marker. */
    private Document current(MemoryTransaction view, Instant snapshot, String namespace, String entity, String id) {
        Iterator<Map.Entry<byte[], Object>> versions = store.scan(view.overlay,
                Keys.versionsFrom(namespace, entity, id, snapshot), Keys.document(namespace, entity, id).end(), false);
        if (!versions.hasNext()) {
            return null;
        }
        Map.Entry<byte[], Object> newest = versions.next();
        return newest.getValue() == DELETE_MARKER ? null : document(Keys.documentKey(newest.getKey()), newest.getValue());
    }

    /** Removes a version and the values recorded for it, if it exists. */
    private void forget(MemoryTransaction view, DocumentKey key) {
        byte[] versionKey = Keys.version(key);
        Object existing = store.get(view.overlay, versionKey);
        if (existing == null) {
            return;
        }
        if (existing instanceof JsonNode content) {
            Keys.strings(content, "$", (path, value) -> view.overlay.put(Keys.value(key, path, value), MemoryStore.REMOVED));
        }
        view.overlay.put(versionKey, MemoryStore.REMOVED);
    }

    private static void add(List<Document> documents, DocumentKey key, Object value) {
        if (value != DELETE_MARKER) {
            documents.add(document(key, value));
        }
    }

    /** A stored value as a document the caller owns. */
    private static Document document(DocumentKey key, Object value) {
        return value == DELETE_MARKER ? Document.deleted(key) : new Document(key, ((JsonNode) value).deepCopy());
    }

    private <R> R reading(Transaction tx, java.util.function.Function<MemoryTransaction, R> work) {
        MemoryTransaction view = own(tx);
        synchronized (view) {
            view.require(false);
            store.lock.readLock().lock();
            try {
                return work.apply(view);
            } finally {
                store.lock.readLock().unlock();
            }
        }
    }

    private void writing(Transaction tx, java.util.function.Consumer<MemoryTransaction> work) {
        MemoryTransaction view = own(tx);
        synchronized (view) {
            view.require(true);
            store.lock.readLock().lock();
            try {
                work.accept(view);
            } finally {
                store.lock.readLock().unlock();
            }
        }
    }

    private MemoryTransaction own(Transaction tx) {
        requireOpen();
        if (tx instanceof MemoryTransaction view && view.store == store) {
            return view;
        }
        throw new IllegalArgumentException("Not a transaction of this persistence");
    }

    private void requireOpen() {
        if (store.closed) {
            throw new IllegalStateException("The persistence is closed");
        }
    }

    private static void requireNames(String namespace, String entity) {
        Identifiers.requireNamespace(namespace);
        Identifiers.requireEntity(entity);
    }

    private static void requireNames(String namespace, String entity, String id) {
        requireNames(namespace, entity);
        Identifiers.requireId(id);
    }

    private static void requireBounds(Range<String> range) {
        Objects.requireNonNull(range, "range");
        if (range.after() != null) {
            Identifiers.requireId(range.after());
        }
        if (range.before() != null) {
            Identifiers.requireId(range.before());
        }
    }
}
