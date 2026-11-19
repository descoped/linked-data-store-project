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

import static io.descoped.lds.core.persistence.neo4j.Neo4jCreationalPatternFactory.hashOf;

/**
 * Documents as a graph in Neo4j.
 * <ul>
 * <li>A document is one node, with the entity as its label and the properties {@code namespace} and {@code id}.
 * One database holds any number of namespaces.</li>
 * <li>Each version is a {@code VERSION} relationship from the document node to the root of that version, valid
 * from {@code from} until {@code to}. Both are instants, stored in UTC.</li>
 * <li>Below the root, objects, arrays and values are nodes with the label {@code <entity>_E}, joined by
 * {@code EMBED} relationships that carry the field name, or the index in an array. A value node also carries the
 * namespace, for the index that a search for a value looks up.</li>
 * <li>A link is a {@code REF} relationship to the document node it points at, in the same namespace. That node is
 * created with the link when the document is not written yet.</li>
 * </ul>
 * A read is one statement that returns one record for each document, and a record is taken from the driver when
 * a document is asked for.
 */
final class Neo4jPersistence implements RxJsonPersistence {

    static final ZoneId UTC = ZoneId.of("Etc/UTC");

    /*
     * The end of every read. r is the document node, v the version and m its root; "leaves" holds every node
     * below the root with the relationships that lead to it.
     */
    private static final String RETURN_DOCUMENT = "RETURN r, v, m, [p = (m)-[:EMBED|REF*]->(e) | [relationships(p), e]] AS leaves";

    private final Neo4jTransactionFactory transactionFactory;
    private final Neo4jCreationalPatternFactory creationalPatternFactory;

    Neo4jPersistence(Neo4jTransactionFactory transactionFactory) {
        this.transactionFactory = transactionFactory;
        this.creationalPatternFactory = new Neo4jCreationalPatternFactory();
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

        Map<String, FlattenedDocumentLeafNode> leaves = new TreeMap<>();
        Node root = row.get("m").asNode();
        if (root.containsKey("deleted") && root.get("deleted").asBoolean(false)) {
            // a version that marks the document as deleted
            leaves.put("$", new FlattenedDocumentLeafNode(key, null, FragmentType.DELETED, null, Integer.MAX_VALUE));
        } else {
            for (Value leaf : row.get("leaves").values()) {
                String path = computePath(leaf.get(0).asList(Value::asRelationship));
                leaves.put(path, toLeaf(key, path, leaf.get(1).asNode()));
            }
        }

        // The document is never marked as deleted here: a version that marks it holds the one leaf above.
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
            case "string", "number", "boolean" -> {
                if (value.isNull()) {
                    // written as null: the node has no value
                    yield new FlattenedDocumentLeafNode(key, path, FragmentType.NULL, null, Integer.MAX_VALUE);
                }
                yield switch (type) {
                    case "string" -> new FlattenedDocumentLeafNode(key, path, FragmentType.STRING, value.asString(), Integer.MAX_VALUE);
                    case "number" -> new FlattenedDocumentLeafNode(key, path, FragmentType.NUMERIC, String.valueOf(value.asNumber()), Integer.MAX_VALUE);
                    default -> new FlattenedDocumentLeafNode(key, path, FragmentType.BOOLEAN, String.valueOf(value.asBoolean()), Integer.MAX_VALUE);
                };
            }
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
                Neo4jQueryAndParams qp = creationalPatternFactory.creationalQueryAndParams(specification, entity, documentForEntityList);
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
        // TODO: Make configurable.
        params.put("value", value.length() > 40 ? hashOf(value) : value);
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
        String cypher = "MATCH (r:" + Cypher.label(entityName) + " {namespace: $namespace, id: $rid})-[v:VERSION]->(m) WHERE v.from <= $snapshot AND $snapshot < v.to\n" +
                RETURN_DOCUMENT;
        return documents(tx, cypher, params, ns, entityName).firstElement();
    }

    @Override
    public Flowable<JsonDocument> readDocuments(Transaction tx, ZonedDateTime snapshot, String ns, String entityName, Range<String> range) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("namespace", ns);
        params.put("snapshot", inUtc(snapshot));
        String cypher = "MATCH (r:" + Cypher.label(entityName) + " {namespace: $namespace})-[v:VERSION]->(m)\n" +
                "WHERE v.from <= $snapshot AND $snapshot < v.to" + idConditions(range, params) + "\n" +
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
                    "MATCH (r)-[v:VERSION]->(m) WHERE v.from <= $snapshot AND $snapshot < v.to\n" +
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
        String cypher = "MATCH (r:" + Cypher.label(entityName) + " {namespace: $namespace, id: $rid})-[:VERSION {from: $version}]->(m) OPTIONAL MATCH (m)-[:EMBED*]->(e) DETACH DELETE m, e " +
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
                "OPTIONAL MATCH (r:" + label + ")-[v:VERSION]->() WHERE v.from <= $version AND $version < v.to WITH r, v AS prevVersion\n" +
                "OPTIONAL MATCH (r:" + label + ")-[v:VERSION]->() WHERE v.from > $version WITH r, prevVersion, v AS nextVersion ORDER BY v.from LIMIT 1\n" +
                "MERGE (r)-[v:VERSION {from: $version, to: coalesce(prevVersion.to, nextVersion.from, datetime('9999-01-01T00:00:00.0Z[Etc/UTC]'))}]->(m:" + Cypher.label(entityName + "_E") + " {type:'map', path:'$'})\n" +
                "SET prevVersion.to = $version, m.deleted = true\n" +
                "WITH m OPTIONAL MATCH (m)-[:EMBED*]->(e) DETACH DELETE e";
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
