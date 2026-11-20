package io.descoped.lds.core.persistence.neo4j;

import com.fasterxml.jackson.databind.JsonNode;
import io.descoped.lds.api.json.JsonNavigationPath;
import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.PersistenceDeletePolicy;
import io.descoped.lds.api.persistence.PersistenceException;
import io.descoped.lds.api.persistence.Transaction;
import io.descoped.lds.api.persistence.flattened.FlattenedDocument;
import io.descoped.lds.api.persistence.flattened.FlattenedDocumentLeafNode;
import io.descoped.lds.api.persistence.json.FlattenedDocumentToJson;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.persistence.reactivex.Range;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.persistence.streaming.FragmentType;
import io.descoped.lds.api.specification.Specification;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static io.descoped.lds.core.persistence.neo4j.Neo4jDocumentWriter.LONGEST_TEXT_LOOKED_UP_AS_IT_IS;
import static io.descoped.lds.core.persistence.neo4j.Neo4jDocumentWriter.hashOf;

/**
 * Documents as a graph in Neo4j.
 * <ul>
 * <li>A document is one node, with the entity as its label and the properties {@code namespace} and {@code id}.
 * One database holds any number of namespaces.</li>
 * <li>Each version is a {@code VERSION} relationship from the document node to the root of that version, valid
 * from {@code from} until {@code to}. Both are instants, stored in UTC.</li>
 * <li>Below the root, objects, arrays and values are nodes with the label {@code <entity>_E}, joined by
 * {@code EMBED} relationships that carry the field name, or the index in an array. A node has the type of what
 * the document holds there ({@code map}, {@code array}, {@code string}, {@code number}, {@code boolean},
 * {@code null}). A value node also carries the namespace, for the index that a search for a value looks up.</li>
 * <li>A document is stored as it is given, also what the specification does not describe
 * ({@link Neo4jDocumentWriter}). The specification tells which fields are links.</li>
 * <li>A link is a {@code REF} relationship to the document node it points at, in the same namespace. That node is
 * created with the link when the document is not written yet.</li>
 * <li>A version that marks the document as deleted is a root with {@code deleted: true} and nothing below it. A
 * read at a time leaves it out; with the versions of a document it is read as an empty document.</li>
 * <li>The versions of a document follow each other without a gap: a version is valid until the next one, and a
 * version that is deleted gives its time to the version before it.</li>
 * </ul>
 * A read is one statement that returns one record for each document, and a record is taken from the driver when
 * a document is asked for.
 */
final class Neo4jPersistence implements RxJsonPersistence {

    static final ZoneId UTC = ZoneId.of("Etc/UTC");

    /*
     * The version that is valid at a time, unless it marks the document as deleted: then there is no document at
     * that time. For a statement that names the version v and its root m.
     */
    private static final String VALID_AT_SNAPSHOT = "v.from <= $snapshot AND $snapshot < v.to AND m.deleted IS NULL";

    /*
     * The end of every read. r is the document node, v the version and m its root; "leaves" holds every node
     * below the root with the relationships that lead to it.
     */
    private static final String RETURN_DOCUMENT = "RETURN r, v, m, [p = (m)-[:EMBED|REF*]->(e) | [relationships(p), e]] AS leaves";

    private final Neo4jTransactionFactory transactionFactory;
    private final Neo4jDocumentWriter writer;

    Neo4jPersistence(Neo4jTransactionFactory transactionFactory) {
        this.transactionFactory = transactionFactory;
        this.writer = new Neo4jDocumentWriter();
    }

    /**
     * The instant in the one zone versions are stored with. Neo4j tells two values of the same instant apart
     * when their zones differ, also "Z" and "Etc/UTC".
     */
    static ZonedDateTime inUtc(ZonedDateTime instant) {
        return instant.withZoneSameInstant(UTC);
    }

    /**
     * The document of one record of a read.
     */
    static JsonDocument toDocument(Record row, String namespace, String entityName) {
        String id = row.get("r").get("id").asString();
        ZonedDateTime version = row.get("v").get("from").asZonedDateTime();
        DocumentKey key = new DocumentKey(namespace, entityName, id, version);

        // A version that marks the document as deleted has no leaves, so it is an empty document. It is read with
        // the versions of a document only: the reads at a time leave it out (VALID_AT_SNAPSHOT).
        Map<String, FlattenedDocumentLeafNode> leaves = new TreeMap<>();
        for (Value leaf : row.get("leaves").values()) {
            String path = computePath(leaf.get(0).asList(Value::asRelationship));
            leaves.put(path, toLeaf(key, path, leaf.get(1).asNode()));
        }
        FlattenedDocument flattenedDocument = new FlattenedDocument(key, leaves, false);
        JsonNode jsonObject = new FlattenedDocumentToJson(flattenedDocument).toJsonNode();
        return new JsonDocument(key, jsonObject);
    }

    private static FlattenedDocumentLeafNode toLeaf(DocumentKey key, String path, Node node) {
        if (node.containsKey("id")) {
            // the document node a link points at
            String linkTargetEntity = node.labels().iterator().next();
            String link = "/" + linkTargetEntity + "/" + node.get("id").asString();
            return new FlattenedDocumentLeafNode(key, path, FragmentType.STRING, link, Integer.MAX_VALUE);
        }
        String type = node.get("type").asString();
        Value value = node.get("value");
        return switch (type) {
            case "map" -> new FlattenedDocumentLeafNode(key, path, FragmentType.EMPTY_OBJECT, null, Integer.MAX_VALUE);
            case "array" -> new FlattenedDocumentLeafNode(key, path, FragmentType.EMPTY_ARRAY, null, Integer.MAX_VALUE);
            case "null" -> new FlattenedDocumentLeafNode(key, path, FragmentType.NULL, null, Integer.MAX_VALUE);
            case "string" -> new FlattenedDocumentLeafNode(key, path, FragmentType.STRING, value.asString(), Integer.MAX_VALUE);
            case "number" -> new FlattenedDocumentLeafNode(key, path, FragmentType.NUMERIC, String.valueOf(value.asNumber()), Integer.MAX_VALUE);
            case "boolean" -> new FlattenedDocumentLeafNode(key, path, FragmentType.BOOLEAN, String.valueOf(value.asBoolean()), Integer.MAX_VALUE);
            default -> throw new IllegalStateException("type not supported: " + type);
        };
    }

    static String computePath(List<Relationship> embeds) {
        StringBuilder completePath = new StringBuilder("$");
        for (Relationship embedRel : embeds) {
            String pathElement = embedRel.get("path").asString();
            if ("[]".equals(pathElement)) {
                int index = embedRel.get("index").asNumber().intValue();
                completePath.append("[").append(index).append("]");
            } else {
                completePath.append(".").append(pathElement);
            }
        }
        return completePath.toString();
    }

    /*
     * The bounds, the direction and the limit of a range of ids, for a statement that names the document node r.
     */
    private static String idConditions(Range<String> range, Map<String, Object> params) {
        StringBuilder conditions = new StringBuilder();
        if (range.hasAfter()) {
            conditions.append(" AND $idAfter < r.id");
            params.put("idAfter", range.getAfter());
        }
        if (range.hasBefore()) {
            conditions.append(" AND r.id < $idBefore");
            params.put("idBefore", range.getBefore());
        }
        return conditions.toString();
    }

    private static String orderAndLimit(Range<?> range, String orderBy, Map<String, Object> params) {
        String order = " ORDER BY " + orderBy + (range.isBackward() ? " DESC" : "");
        if (range.isLimited()) {
            params.put("limit", range.getLimit());
            return order + " LIMIT $limit";
        }
        return order;
    }

    private static Flowable<JsonDocument> documents(Transaction tx, String query, Map<String, Object> params, String namespace, String entityName) {
        return ((Neo4jTransaction) tx).executeCypherAsync(query, params).map(row -> toDocument(row, namespace, entityName));
    }

    private static Completable run(Transaction tx, String query, Map<String, Object> params) {
        return ((Neo4jTransaction) tx).executeCypherAsync(query, params).ignoreElements();
    }

    @Override
    public Transaction createTransaction(boolean readOnly) throws PersistenceException {
        return transactionFactory.createTransaction(readOnly);
    }

    @Override
    public Completable createOrOverwrite(Transaction transaction, Flowable<JsonDocument> documentFlowable, Specification specification) {
        return documentFlowable.toList().flatMapCompletable(documents -> {
            Map<String, List<JsonDocument>> documentsByEntity = new LinkedHashMap<>();
            for (JsonDocument document : documents) {
                documentsByEntity.computeIfAbsent(document.key().entity(), e -> new ArrayList<>()).add(document);
            }
            List<Completable> completables = new ArrayList<>();
            for (Map.Entry<String, List<JsonDocument>> entry : documentsByEntity.entrySet()) {
                String entity = entry.getKey();
                List<JsonDocument> documentForEntityList = entry.getValue();
                Neo4jQueryAndParams qp = writer.write(specification, entity, documentForEntityList);
                completables.add(run(transaction, qp.query(), qp.params()));
            }
            return Completable.concat(completables);
        });
    }

    @Override
    public Flowable<JsonDocument> findDocument(Transaction tx, ZonedDateTime snapshot, String namespace, String entityName, JsonNavigationPath path, String value, Range<String> range) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("namespace", namespace);
        params.put("snapshot", inUtc(snapshot));
        params.put("path", path.serialize());
        params.put("value", value.length() > LONGEST_TEXT_LOOKED_UP_AS_IT_IS ? hashOf(value) : value);
        String cypher = "MATCH (e:" + Cypher.label(entityName + "_E") + " {namespace: $namespace, path: $path, hashOrValue: $value})<-[:EMBED*]-(m)<-[v:VERSION]-(r)\n" +
                "WHERE v.from <= $snapshot AND $snapshot < v.to" + idConditions(range, params) + "\n" +
                // DISTINCT: an array that holds the value more than once is one document
                "WITH DISTINCT r, v, m" + orderAndLimit(range, "r.id", params) + "\n" +
                RETURN_DOCUMENT;
        return documents(tx, cypher, params, namespace, entityName);
    }

    @Override
    public Maybe<JsonDocument> readDocument(Transaction tx, ZonedDateTime snapshot, String ns, String entityName, String id) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("namespace", ns);
        params.put("rid", id);
        params.put("snapshot", inUtc(snapshot));
        String cypher = "MATCH (r:" + Cypher.label(entityName) + " {namespace: $namespace, id: $rid})-[v:VERSION]->(m) WHERE " + VALID_AT_SNAPSHOT + "\n" +
                RETURN_DOCUMENT;
        return documents(tx, cypher, params, ns, entityName).firstElement();
    }

    @Override
    public Flowable<JsonDocument> readDocuments(Transaction tx, ZonedDateTime snapshot, String ns, String entityName, Range<String> range) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("namespace", ns);
        params.put("snapshot", inUtc(snapshot));
        String cypher = "MATCH (r:" + Cypher.label(entityName) + " {namespace: $namespace})-[v:VERSION]->(m)\n" +
                "WHERE " + VALID_AT_SNAPSHOT + idConditions(range, params) + "\n" +
                "WITH r, v, m" + orderAndLimit(range, "r.id", params) + "\n" +
                RETURN_DOCUMENT;
        return documents(tx, cypher, params, ns, entityName);
    }

    @Override
    public Flowable<JsonDocument> readDocumentVersions(Transaction tx, String ns, String entityName, String id, Range<ZonedDateTime> range) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("namespace", ns);
        params.put("rid", id);

        // the versions between the bounds, both exclusive
        List<String> conditions = new ArrayList<>();
        if (range.hasAfter()) {
            params.put("snapshotFrom", inUtc(range.getAfter()));
            conditions.add("$snapshotFrom < v.from");
        }
        if (range.hasBefore()) {
            params.put("snapshotTo", inUtc(range.getBefore()));
            conditions.add("v.from < $snapshotTo");
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);

        String cypher = "MATCH (r:" + Cypher.label(entityName) + " {namespace: $namespace, id: $rid})-[v:VERSION]->(m)" + where + "\n" +
                "WITH r, v, m" + orderAndLimit(range, "v.from", params) + "\n" +
                RETURN_DOCUMENT;
        return documents(tx, cypher, params, ns, entityName);
    }

    @Override
    public Flowable<JsonDocument> readTargetDocuments(Transaction tx, ZonedDateTime snapshot, String ns,
                                                      String entityName, String id, JsonNavigationPath jsonNavigationPath,
                                                      String targetEntityName, Range<String> range) {
        return Flowable.defer(() -> {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("namespace", ns);
            params.put("snapshot", inUtc(snapshot));
            params.put("path", jsonNavigationPath.popBack().serialize());
            params.put("lastPathElement", jsonNavigationPath.back());
            params.put("id", id);
            String source = Cypher.label(entityName);
            String sourceNode = Cypher.label(entityName + "_E");
            // "*0..": the node that holds the link is the root itself when the link is at the top of the document
            String cypher = "MATCH (elem:" + source + " {namespace: $namespace, id: $id})-[version:VERSION]->" +
                    "(root:" + sourceNode + ")-[:EMBED*0..]->" +
                    "(edge:" + sourceNode + " {path: $path})-[:REF {path: $lastPathElement}]->" +
                    "(r:" + Cypher.label(targetEntityName) + ")\n" +
                    "WHERE version.from <= $snapshot AND $snapshot < version.to" + idConditions(range, params) + "\n" +
                    // DISTINCT: an array that links to a document more than once reads it once
                    "WITH DISTINCT r\n" +
                    // a link may point at a document that is not written (yet): the limit counts those that are
                    "MATCH (r)-[v:VERSION]->(m) WHERE " + VALID_AT_SNAPSHOT + "\n" +
                    "WITH r, v, m" + orderAndLimit(range, "r.id", params) + "\n" +
                    RETURN_DOCUMENT;
            return documents(tx, cypher, params, ns, targetEntityName);
        });
    }

    @Override
    public Flowable<JsonDocument> readSourceDocuments(Transaction tx, ZonedDateTime snapshot, String ns,
                                                      String targetEntityName, String targetId,
                                                      JsonNavigationPath relationPath, String sourceEntityName,
                                                      Range<String> range) {
        return Flowable.defer(() -> {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("namespace", ns);
            params.put("snapshot", inUtc(snapshot));
            params.put("path", relationPath.popBack().serialize());
            params.put("lastPathElement", relationPath.back());
            params.put("id", targetId);
            String sourceNode = Cypher.label(sourceEntityName + "_E");
            String cypher = "MATCH (target:" + Cypher.label(targetEntityName) + " {namespace: $namespace, id: $id})" +
                    "<-[:REF {path: $lastPathElement}]-(edge:" + sourceNode + " {path: $path})" +
                    "<-[:EMBED*0..]-(m:" + sourceNode + ")" +
                    "<-[v:VERSION]-(r:" + Cypher.label(sourceEntityName) + ")\n" +
                    "WHERE v.from <= $snapshot AND $snapshot < v.to" + idConditions(range, params) + "\n" +
                    // DISTINCT: an array that links to the document more than once is one source
                    "WITH DISTINCT r, v, m" + orderAndLimit(range, "r.id", params) + "\n" +
                    RETURN_DOCUMENT;
            return documents(tx, cypher, params, ns, sourceEntityName);
        });
    }

    @Override
    public Completable deleteDocument(Transaction tx, String ns, String entityName, String id, ZonedDateTime version, PersistenceDeletePolicy policy) {
        String cypher = "MATCH (r:" + Cypher.label(entityName) + " {namespace: $namespace, id: $rid})-[v:VERSION {from: $version}]->(m)\n" +
                // the version before the deleted one is valid until the one after it, as if the deleted one was never written
                "OPTIONAL MATCH (r)-[p:VERSION]->() WHERE p.from < $version WITH r, v, m, p ORDER BY p.from DESC LIMIT 1\n" +
                "SET p.to = v.to\n" +
                "WITH r, m OPTIONAL MATCH (m)-[:EMBED*]->(e) DETACH DELETE m, e " +
                "WITH r MATCH (r) WHERE NOT (r)--() DELETE r";
        return run(tx, cypher, Map.of("namespace", ns, "rid", id, "version", inUtc(version)));
    }

    @Override
    public Completable deleteAllDocumentVersions(Transaction tx, String ns, String entity, String id, PersistenceDeletePolicy policy) {
        String cypher = "MATCH (r:" + Cypher.label(entity) + " {namespace: $namespace, id: $rid}) OPTIONAL MATCH (r)-[:VERSION]->(m) OPTIONAL MATCH (m)-[:EMBED*]->(e) DETACH DELETE m, e " +
                "WITH r MATCH (r) WHERE NOT (r)<--() DELETE r";
        return run(tx, cypher, Map.of("namespace", ns, "rid", id));
    }

    @Override
    public Completable deleteAllEntities(Transaction tx, String namespace, String entity, Specification specification) {
        // DISTINCT: each document node once, where there was a row for every node of every version
        String cypher = "MATCH (r:" + Cypher.label(entity) + " {namespace: $namespace}) OPTIONAL MATCH (r)-[:VERSION]->(m) OPTIONAL MATCH (m)-[:EMBED*]->(e) DETACH DELETE m, e " +
                "WITH DISTINCT r WHERE NOT (r)<--() DELETE r";
        return run(tx, cypher, Map.of("namespace", namespace));
    }

    @Override
    public Completable markDocumentDeleted(Transaction transaction, String ns, String entityName, String id, ZonedDateTime version, PersistenceDeletePolicy policy) {
        String label = Cypher.label(entityName);
        String cypher = "MERGE (r:" + label + " {namespace: $namespace, id: $rid}) WITH r\n" +
                // a version written at this time is replaced, as a write of a document replaces it
                "OPTIONAL MATCH (r)-[:VERSION {from: $version}]->(old) OPTIONAL MATCH (old)-[:EMBED*]->(e) DETACH DELETE old, e WITH DISTINCT r\n" +
                "OPTIONAL MATCH (r)-[v:VERSION]->() WHERE v.from <= $version AND $version < v.to WITH r, v AS prevVersion\n" +
                "OPTIONAL MATCH (r)-[v:VERSION]->() WHERE v.from > $version WITH r, prevVersion, min(v.from) AS nextVersionFrom\n" +
                "CREATE (r)-[:VERSION {from: $version, to: coalesce(prevVersion.to, nextVersionFrom, datetime('9999-01-01T00:00:00.0Z[Etc/UTC]'))}]->(:" + Cypher.label(entityName + "_E") + " {type:'map', path:'$', deleted: true})\n" +
                "SET prevVersion.to = $version";
        return run(transaction, cypher, Map.of("namespace", ns, "rid", id, "version", inUtc(version)));
    }

    @Override
    public Single<Boolean> hasPrevious(Transaction tx, ZonedDateTime snapshot, String ns, String entityName, String id) {
        return readDocuments(tx, snapshot, ns, entityName, Range.lastBefore(1, id)).isEmpty().map(wasEmpty -> !wasEmpty);
    }

    @Override
    public Single<Boolean> hasNext(Transaction tx, ZonedDateTime snapshot, String ns, String entityName, String id) {
        return readDocuments(tx, snapshot, ns, entityName, Range.firstAfter(1, id)).isEmpty().map(wasEmpty -> !wasEmpty);
    }

    @Override
    public void close() throws PersistenceException {
        transactionFactory.close();
    }
}
