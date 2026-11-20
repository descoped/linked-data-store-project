package io.descoped.lds.core.persistence.neo4j;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.api.specification.SpecificationElement;
import io.descoped.lds.api.specification.SpecificationElementType;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes documents: the statement that writes the documents of one entity, and a document as the parameters of
 * that statement.
 * <p>
 * A document is written as it is given. Every object, array and value in it becomes a node, also where the
 * specification does not know the field, or describes it as something else than the document holds. The
 * specification tells one thing only: which fields are links, and to which entities. A text in such a field that
 * names one of those entities ({@code /Entity/id}) becomes a {@code REF} relationship to the document it points
 * at; any other value there is written as the value it is.
 * <p>
 * The statement is the same for every document of an entity, so Neo4j plans it once. The nodes of a document are
 * a list in the order of the document, parents before their children, and each node names its parent by its
 * place in that list. The statement creates the nodes, and then joins each to its parent. No name of a field is
 * part of the Cypher: names, paths and values are parameters. The names of the entity and of the entities it
 * may link to are labels ({@link Cypher#label}).
 */
final class Neo4jDocumentWriter {

    /**
     * A text longer than this is looked up by its hash.
     */
    static final int LONGEST_TEXT_LOOKED_UP_AS_IT_IS = 40;

    /*
     * A link is written /Entity/id, as it is for the providers that keep it as text (RxJsonPersistenceBridge), so
     * that every provider follows the same texts and reads back the text that was written. A text without an id
     * (/Entity/) leads to no document in any provider; here it is a text, so that no document node without an id
     * is made for it.
     */
    private static final Pattern LINK = Pattern.compile("/([^/]+)/([^/]+)");

    private static final HexFormat HEX = HexFormat.of();

    /**
     * The statement that writes the documents of one entity, and the entities a document of it may link to, in
     * the order the statement expects their links: {@code record.links[n]} holds the links to
     * {@code linkTargets.get(n)}.
     */
    record Statement(String cypher, List<String> linkTargets) {
    }

    /*
     * What a statement is made from: the entity, and the entities a document of it may link to.
     */
    private record StatementKey(String entity, List<String> linkTargets) {
    }

    /*
     * The statements made so far. There are as many as the models LDS is given have entities, whatever number of
     * specifications is made from those models.
     */
    private final Map<StatementKey, Statement> statements = new ConcurrentHashMap<>();

    /**
     * The statement and the parameters that write documents of one entity.
     *
     * @param specification the specification the documents are written with
     * @param entity        the entity of every document in the list
     * @param documents     the documents
     * @return the statement and its parameters
     */
    Neo4jQueryAndParams write(Specification specification, String entity, List<JsonDocument> documents) {
        SpecificationElement entityElement = specification.getRootElement().getProperties().get(entity);
        // An entity the specification does not have is written all the same, without links: nothing says where
        // they are. Its statement is not kept, so that names from outside do not fill the memory. Such an entity
        // has no constraint and no indexes either (they are created at start for the entities of the
        // specification), so its documents are found by a scan of the label and two writers at once can make
        // two document nodes for one id. Core refuses a write to an entity the specification does not have.
        Statement statement = entityElement == null
                ? statement(entity, List.of())
                : statements.computeIfAbsent(new StatementKey(entity, linkTargets(entityElement)), key -> statement(key.entity(), key.linkTargets()));
        List<Object> batch = new ArrayList<>(documents.size());
        for (JsonDocument document : documents) {
            batch.add(documentParameters(document, entityElement, statement.linkTargets()));
        }
        return new Neo4jQueryAndParams(statement.cypher(), Map.of("batch", batch));
    }

    /**
     * Every entity that a document of this entity may link to, sorted.
     */
    static List<String> linkTargets(SpecificationElement entityElement) {
        TreeSet<String> targets = new TreeSet<>();
        collectLinkTargets(entityElement, targets);
        return List.copyOf(targets);
    }

    private static void collectLinkTargets(SpecificationElement element, TreeSet<String> targets) {
        if (SpecificationElementType.REF.equals(element.getSpecificationElementType()) && element.getRefTypes() != null) {
            targets.addAll(element.getRefTypes());
        }
        if (element.getProperties() != null) {
            for (SpecificationElement property : element.getProperties().values()) {
                collectLinkTargets(property, targets);
            }
        }
        if (element.getItems() != null) {
            collectLinkTargets(element.getItems(), targets);
        }
    }

    /**
     * The statement that writes documents of an entity. Each document is written by itself, one after the other,
     * so that several versions of one document in a batch see each other.
     */
    static Statement statement(String entity, List<String> linkTargets) {
        String document = Cypher.label(entity);
        String node = Cypher.label(entity + "_E");
        StringBuilder cypher = new StringBuilder();
        cypher.append("UNWIND $batch AS record\n");
        cypher.append("CALL (record) {\n");
        cypher.append("  MERGE (r:").append(document).append(" {namespace: record.namespace, id: record.id})\n");
        cypher.append("  WITH r, record\n");
        // The version that is overwritten goes with everything below it. Its root is matched on its own: a version
        // that holds nothing, or links only, has no node below the root.
        cypher.append("  CALL (r, record) {\n");
        cypher.append("    OPTIONAL MATCH (r)-[:VERSION {from: record.version}]->(old)\n");
        cypher.append("    OPTIONAL MATCH (old)-[:EMBED*]->(below)\n");
        cypher.append("    DETACH DELETE old, below\n");
        cypher.append("  }\n");
        // The new version is valid until the version after it, and the version before it until the new one.
        cypher.append("  OPTIONAL MATCH (r)-[before:VERSION]->() WHERE before.from <= record.version AND record.version < before.to\n");
        cypher.append("  CALL (r, record) {\n");
        cypher.append("    OPTIONAL MATCH (r)-[after:VERSION]->() WHERE after.from > record.version\n");
        cypher.append("    RETURN min(after.from) AS next\n");
        cypher.append("  }\n");
        cypher.append("  CREATE (r)-[:VERSION {from: record.version, to: coalesce(before.to, next, datetime('9999-01-01T00:00:00.0Z[Etc/UTC]'))}]->(m:").append(node).append(" {type: 'map', path: '$'})\n");
        cypher.append("  SET before.to = record.version\n");
        cypher.append("  WITH m, record\n");
        // The nodes, in the order of the list. A property that is null is not written: only a value node has a
        // value, what it is looked up by (the hash of a long text, or the value) and the namespace.
        cypher.append("  CALL (record) {\n");
        cypher.append("    UNWIND range(0, size(record.nodes) - 1) AS i\n");
        cypher.append("    WITH i, record.nodes[i] AS n ORDER BY i\n");
        cypher.append("    CREATE (x:").append(node).append(" {type: n.type, path: n.path, value: n.value, hashOrValue: coalesce(n.hash, n.value), namespace: CASE WHEN n.value IS NULL THEN null ELSE record.namespace END})\n");
        // the order is stated again after the CREATE: the places of the list are what joins a node to its parent
        cypher.append("    WITH i, x ORDER BY i\n");
        cypher.append("    RETURN collect(x) AS created\n");
        cypher.append("  }\n");
        // Place 0 is the root of the version, place n the n-th node of the list.
        cypher.append("  WITH [m] + created AS nodes, record\n");
        cypher.append("  CALL (nodes, record) {\n");
        cypher.append("    UNWIND range(0, size(record.nodes) - 1) AS i\n");
        cypher.append("    WITH nodes[record.nodes[i].parent] AS parent, nodes[i + 1] AS child, record.nodes[i] AS n\n");
        cypher.append("    CREATE (parent)-[:EMBED {path: n.name, index: n.index}]->(child)\n");
        cypher.append("  }\n");
        // A link points at a document node, which is created with the link when the document is not written yet.
        for (int i = 0; i < linkTargets.size(); i++) {
            cypher.append("  CALL (nodes, record) {\n");
            cypher.append("    UNWIND record.links[").append(i).append("] AS link\n");
            cypher.append("    MERGE (target:").append(Cypher.label(linkTargets.get(i))).append(" {namespace: record.namespace, id: link.id})\n");
            cypher.append("    WITH nodes[link.parent] AS parent, link, target\n");
            cypher.append("    CREATE (parent)-[:REF {path: link.name, index: link.index}]->(target)\n");
            cypher.append("  }\n");
        }
        cypher.append("}");
        return new Statement(cypher.toString(), linkTargets);
    }

    /**
     * A document as the statement takes it: its key, its nodes and its links.
     *
     * @param document      the document, a JSON object
     * @param entityElement what the specification says about the entity, or null when it does not have it
     * @param linkTargets   the entities the statement writes links to
     * @return the parameters of one document
     */
    static Map<String, Object> documentParameters(JsonDocument document, SpecificationElement entityElement, List<String> linkTargets) {
        DocumentKey key = document.key();
        JsonNode root = document.jackson();
        if (!root.isObject()) {
            throw new IllegalArgumentException("A document is a JSON object, and this is not: " + key);
        }
        Rows rows = new Rows(linkTargets);
        addFields(rows, root, entityElement, 0, "$");
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("namespace", key.namespace());
        parameters.put("id", key.id());
        parameters.put("version", Neo4jPersistence.inUtc(key.timestamp()));
        parameters.put("nodes", rows.nodes);
        parameters.put("links", rows.links);
        return parameters;
    }

    /* What a document is made of, while it is collected. */
    private static final class Rows {
        final List<String> linkTargets;
        final List<Map<String, Object>> nodes = new ArrayList<>();
        final List<List<Map<String, Object>>> links = new ArrayList<>();

        Rows(List<String> linkTargets) {
            this.linkTargets = linkTargets;
            for (int i = 0; i < linkTargets.size(); i++) {
                links.add(new ArrayList<>());
            }
        }

        /*
         * Adds a node and gives its place: 0 is the root of the version, so the first node is 1. Only what the
         * node has is sent: a place in an array, a value, the hash of a long text.
         */
        int add(int parent, String name, Integer index, String type, String path, Object value, String hash) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("parent", parent);
            node.put("name", name);
            if (index != null) {
                node.put("index", index);
            }
            node.put("type", type);
            node.put("path", path);
            if (value != null) {
                node.put("value", value);
            }
            if (hash != null) {
                node.put("hash", hash);
            }
            nodes.add(node);
            return nodes.size();
        }
    }

    private static void addFields(Rows rows, JsonNode object, SpecificationElement element, int place, String path) {
        Map<String, SpecificationElement> properties = element == null ? null : element.getProperties();
        for (Map.Entry<String, JsonNode> field : object.properties()) {
            String name = field.getKey();
            SpecificationElement fieldElement = properties == null ? null : properties.get(name);
            add(rows, field.getValue(), fieldElement, place, name, null, path + "." + name);
        }
    }

    /*
     * Adds what is at one place of a document: a field of an object (index is null), or an item of an array
     * (name is "[]"). The element is what the specification says about that place, or null when it says nothing.
     */
    private static void add(Rows rows, JsonNode node, SpecificationElement element, int parent, String name, Integer index, String path) {
        if (node.isObject()) {
            int place = rows.add(parent, name, index, "map", path, null, null);
            addFields(rows, node, element, place, path);
        } else if (node.isArray()) {
            int place = rows.add(parent, name, index, "array", path, null, null);
            SpecificationElement items = element == null ? null : element.getItems();
            for (int i = 0; i < node.size(); i++) {
                add(rows, node.get(i), items, place, "[]", i, path + "[]");
            }
        } else {
            addValue(rows, node, element, parent, name, index, path);
        }
    }

    /* Adds a value: a null, a text (or a link), a boolean or a number. */
    private static void addValue(Rows rows, JsonNode node, SpecificationElement element, int parent, String name, Integer index, String path) {
        if (node.isNull()) {
            rows.add(parent, name, index, "null", path, null, null);
        } else if (node.isString()) {
            String text = node.stringValue();
            if (!addLink(rows, text, element, parent, name, index)) {
                rows.add(parent, name, index, "string", path, text, text.length() > LONGEST_TEXT_LOOKED_UP_AS_IT_IS ? hashOf(text) : null);
            }
        } else if (node.isBoolean()) {
            rows.add(parent, name, index, "boolean", path, node.booleanValue(), null);
        } else if (node.isNumber()) {
            rows.add(parent, name, index, "number", path, numberOf(node), null);
        } else {
            throw new IllegalArgumentException("A value that JSON does not have, at " + path + ": " + node.getNodeType());
        }
    }

    /* A whole number that a long does not hold is written as the nearest double, as a number with a fraction is. */
    private static Number numberOf(JsonNode node) {
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            return node.longValue();
        }
        return node.doubleValue();
    }

    /*
     * Adds the text as a link, when the specification says the place holds links and the text names an entity
     * that it may link to. Otherwise nothing is added and the text is a value.
     */
    private static boolean addLink(Rows rows, String text, SpecificationElement element, int parent, String name, Integer index) {
        SpecificationElement linkElement = linkElement(element);
        if (linkElement == null || linkElement.getRefTypes() == null) {
            return false;
        }
        Matcher link = LINK.matcher(text);
        if (!link.matches() || !linkElement.getRefTypes().contains(link.group(1))) {
            return false;
        }
        int target = rows.linkTargets.indexOf(link.group(1));
        if (target < 0) {
            return false;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("parent", parent);
        row.put("name", name);
        if (index != null) {
            row.put("index", index);
        }
        row.put("id", link.group(2));
        rows.links.get(target).add(row);
        return true;
    }

    /* The element that says a place holds links: the element of a field, or of the array an item is in. */
    private static SpecificationElement linkElement(SpecificationElement element) {
        if (element == null) {
            return null;
        }
        if (SpecificationElementType.REF.equals(element.getSpecificationElementType())) {
            return element;
        }
        SpecificationElement parent = element.getParent();
        if (parent != null && SpecificationElementType.REF.equals(parent.getSpecificationElementType())) {
            return parent;
        }
        return null;
    }

    /**
     * The hash a long text is looked up by.
     *
     * @param text the text
     * @return its SHA-1 hash, as hexadecimal digits
     */
    static String hashOf(String text) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
