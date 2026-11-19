package io.descoped.lds.core.persistence.neo4j;

import com.fasterxml.jackson.databind.JsonNode;
import io.descoped.lds.api.json.JsonNavigationPath;
import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.json.JsonDocument;
import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.api.specification.SpecificationElement;
import io.descoped.lds.api.specification.SpecificationElementType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class Neo4jCreationalPatternFactory {

    /**
     * The statement that writes the documents of one entity, and the texts it refers to as {@code $texts[n]}:
     * the paths and the field names of the entity. They are parameters, so that no name of a field is part of
     * the Cypher.
     */
    record CreationalPattern(String cypher, List<String> texts) {
    }

    /* A statement while it is written. */
    private static final class Statement {
        final StringBuilder cypher = new StringBuilder();
        final List<String> texts = new ArrayList<>();

        String text(String value) {
            texts.add(value);
            return "$texts[" + (texts.size() - 1) + "]";
        }
    }

    private final Map<SpecificationElement, CreationalPattern> creationalPatternByEntity = new ConcurrentHashMap<>();

    Neo4jQueryAndParams creationalQueryAndParams(Specification specification, String entity, List<JsonDocument> documentForEntityList) {
        SpecificationElement entitySpecificationElement = specification.getRootElement().getProperties().get(entity);
        CreationalPattern pattern = creationalCypherForEntity(entitySpecificationElement);
        List<Object> batch = new ArrayList<>();
        for (JsonDocument document : documentForEntityList) {
            DocumentKey key = document.key();
            JsonNode documentRootNode = document.jackson();
            List<Object> data = new ArrayList<>();
            convertJsonDocumentToMultiDimensionalCypherData(data, documentRootNode, entitySpecificationElement);
            List<Object> row = new ArrayList<>();
            row.add(key.id());
            row.add(Neo4jPersistence.inUtc(key.timestamp()));
            row.add(data);
            row.add(key.namespace());
            batch.add(row);
        }
        Map<String, Object> params = Map.of(
                "batch", batch,
                "texts", pattern.texts()
        );
        return new Neo4jQueryAndParams(pattern.cypher(), params);
    }

    CreationalPattern creationalCypherForEntity(SpecificationElement specificationElement) {
        if (!SpecificationElementType.MANAGED.equals(specificationElement.getSpecificationElementType())) {
            throw new IllegalArgumentException("specificationElement must be of type MANAGED");
        }
        return creationalPatternByEntity.computeIfAbsent(specificationElement, Neo4jCreationalPatternFactory::buildCreationalCypherForEntity);
    }

    static final Pattern linkPattern = Pattern.compile("/?([^/]*)/([^/]*)");

    static CreationalPattern buildCreationalCypherForEntity(SpecificationElement specificationElement) {
        String entity = Cypher.label(specificationElement.getName());
        String entityNode = Cypher.label(specificationElement.getName() + "_E");
        Statement statement = new Statement();
        StringBuilder cypher = statement.cypher;
        cypher.append("UNWIND $batch AS record\n");
        cypher.append("MERGE (r:").append(entity).append(" {namespace: record[3], id: record[0]}) WITH r, record[1] AS version, record[2] AS data, record[3] AS ns\n");
        // The version that is overwritten goes with everything below it. Its root is matched on its own: a version
        // that holds nothing, or links only, has no node below the root.
        cypher.append("OPTIONAL MATCH (r:").append(entity).append(")-[v:VERSION {from: version}]->(m) OPTIONAL MATCH (m)-[:EMBED*]->(e) DETACH DELETE m, e WITH r, version, data, ns\n");
        cypher.append("OPTIONAL MATCH (r:").append(entity).append(")-[v:VERSION]->() WHERE v.from <= version AND version < v.to WITH r, version, data, ns, v AS prevVersion\n");
        cypher.append("OPTIONAL MATCH (r:").append(entity).append(")-[v:VERSION]->() WHERE v.from > version WITH r, version, data, ns, prevVersion, min(v.from) AS nextVersionFrom\n");
        cypher.append("FOREACH(d IN data |\n");
        cypher.append("  MERGE (r)-[v:VERSION {from: version, to: coalesce(prevVersion.to, nextVersionFrom, datetime('9999-01-01T00:00:00.0Z[Etc/UTC]'))}]->(m:").append(entityNode).append(" {type:'map', path:'$'})\n");
        cypher.append("  SET prevVersion.to = version");
        int i = 0;
        for (Map.Entry<String, SpecificationElement> entry : specificationElement.getProperties().entrySet()) {
            traverseSpecification(statement, entityNode, entry.getValue(), 1, "    ", "m", "m" + i, "d[" + i + "]");
            i++;
        }
        cypher.append(")");
        return new CreationalPattern(cypher.toString(), List.copyOf(statement.texts));
    }

    static boolean isArrayElementNode(String nodeIdentifier) {
        return nodeIdentifier.endsWith("i");
    }

    private static void traverseSpecification(Statement statement, String entityNode, SpecificationElement element, int depth, String indentation, String parentNodeIdentifier, String nodeIdentifier, String dataListIdentifier) {
        StringBuilder cypher = statement.cypher;
        String path = JsonNavigationPath.from(element).serialize();
        cypher.append("\n").append(indentation).append("FOREACH(d").append(depth).append(" IN ").append(dataListIdentifier).append(" |");
        indentation += "  ";
        dataListIdentifier = "d" + depth;
        String relationPath = element.getName();
        boolean parentIsArray = isArrayElementNode(nodeIdentifier);
        if (parentIsArray) {
            relationPath = "[]";
        }
        if (element.getJsonTypes().contains("object")) {
            cypher.append("\n").append(indentation).append("CREATE (").append(parentNodeIdentifier).append(")-[:").append("EMBED").append("{path: ").append(statement.text(relationPath));
            if (parentIsArray) {
                cypher.append(", index: ").append(dataListIdentifier).append("[0]");
            }
            cypher.append("}]->");
            cypher.append("(").append(nodeIdentifier).append(":").append(entityNode).append(" {type: 'map', path: ").append(statement.text(path)).append("})");
            int i = 0;
            for (Map.Entry<String, SpecificationElement> entry : element.getProperties().entrySet()) {
                String childDataListIdentifier = dataListIdentifier + (parentIsArray ? "[1]" : "") + "[" + i + "]";
                String childNodeIdentifier = nodeIdentifier + "p" + i;
                traverseSpecification(statement, entityNode, entry.getValue(), depth + 1, indentation, nodeIdentifier, childNodeIdentifier, childDataListIdentifier);
                i++;
            }
        } else if (element.getJsonTypes().contains("array")) {
            cypher.append("\n").append(indentation).append("CREATE (").append(parentNodeIdentifier).append(")-[:").append("EMBED").append("{path: ").append(statement.text(relationPath));
            if (parentIsArray) {
                cypher.append(", index: ").append(dataListIdentifier).append("[0]");
            }
            cypher.append("}]->");
            cypher.append("(").append(nodeIdentifier).append(":").append(entityNode).append(" {type:'array', path: ").append(statement.text(path)).append("})");
            String childDataListIdentifier = dataListIdentifier + (parentIsArray ? "[1]" : "");
            String childNodeIdentifier = nodeIdentifier + "i";
            traverseSpecification(statement, entityNode, element.getItems(), depth + 1, indentation, nodeIdentifier, childNodeIdentifier, childDataListIdentifier);
        } else {

            // value node

            boolean isReference = SpecificationElementType.REF.equals(parentIsArray
                    ? element.getParent().getSpecificationElementType()
                    : element.getSpecificationElementType());

            if (isReference) {

                List<String> sortedRefTypes = new ArrayList<>(new TreeSet<>(parentIsArray ? element.getParent().getRefTypes() : element.getRefTypes()));

                for (int i = 0; i < sortedRefTypes.size(); i++) {
                    String refType = sortedRefTypes.get(i);
                    String refDataIdentifier = "d" + (depth + 1);
                    if (i > 0 && i < sortedRefTypes.size() - 1) {
                        cypher.append("\n");
                    }
                    cypher.append("\n").append(indentation);
                    cypher.append("FOREACH(").append(refDataIdentifier).append(" IN ").append(dataListIdentifier);
                    cypher.append(parentIsArray ? "[1]" : "").append("[").append(i).append("]").append(" |");

                    cypher.append("\n").append(indentation).append("  ");
                    cypher.append("MERGE (").append(nodeIdentifier).append(":").append(Cypher.label(refType)).append(" {namespace: ns, id: ").append(refDataIdentifier).append("})");

                    cypher.append("\n").append(indentation).append("  ");
                    cypher.append("CREATE (").append(parentNodeIdentifier).append(")-[:").append("REF {path: ").append(statement.text(relationPath));
                    if (parentIsArray) {
                        cypher.append(", index: ").append(dataListIdentifier).append("[0]");
                    }
                    cypher.append("}]->(").append(nodeIdentifier).append(")");

                    cypher.append(")"); // end foreach
                }

            } else {

                cypher.append("\n").append(indentation).append("CREATE (").append(parentNodeIdentifier).append(")-[:").append("EMBED").append("{path: ").append(statement.text(relationPath));
                if (parentIsArray) {
                    cypher.append(", index: ").append(dataListIdentifier).append("[0]");
                }
                cypher.append("}]->");

                // start node and properties
                cypher.append("(").append(nodeIdentifier).append(":").append(entityNode).append(" {");

                // type property
                String jsonType = element.getJsonTypes().contains("string") ? "string" : element.getJsonTypes().contains("number") ? "number" : element.getJsonTypes().contains("boolean") ? "boolean" : element.getJsonTypes().stream().collect(Collectors.joining(","));
                cypher.append("type: ").append(statement.text(jsonType));

                // path property
                cypher.append(", path: ").append(statement.text(path));

                // value property
                cypher.append(", value: ");
                cypher.append(dataListIdentifier);
                if (parentIsArray) {
                    cypher.append("[1]");
                }
                cypher.append("[0]"); // value

                // hashOrValue property
                cypher.append(", hashOrValue: ");
                cypher.append(dataListIdentifier);
                if (parentIsArray) {
                    cypher.append("[1]");
                }
                cypher.append("[1]"); // hashOrValue

                // namespace property: the value index starts with it
                cypher.append(", namespace: ns");

                // end properties
                cypher.append("})");
            }
        }
        cypher.append(")"); // end foreach
    }

    static void convertJsonDocumentToMultiDimensionalCypherData(List<Object> data, JsonNode node, SpecificationElement element) {
        if (node.isNull()) {
            data.add(Collections.emptyList());
        } else if (node.isContainerNode()) {
            ArrayList<Object> containerValue = new ArrayList<>();
            data.add(containerValue);
            if (node.isObject()) {
                for (Map.Entry<String, SpecificationElement> entry : element.getProperties().entrySet()) {
                    String fieldName = entry.getKey();
                    SpecificationElement childElement = entry.getValue();
                    if (node.has(fieldName)) {
                        JsonNode childNode = node.get(fieldName);
                        ArrayList<Object> childData = new ArrayList<>();
                        containerValue.add(childData);
                        convertJsonDocumentToMultiDimensionalCypherData(childData, childNode, childElement);
                    } else {
                        containerValue.add(Collections.emptyList());
                    }
                }
            } else if (node.isArray()) {
                SpecificationElement childElement = element.getItems();
                for (int i = 0; i < node.size(); i++) {
                    JsonNode childNode = node.get(i);
                    List<Object> childData = new ArrayList<>();
                    containerValue.add(childData);
                    childData.add(i);
                    convertJsonDocumentToMultiDimensionalCypherData(childData, childNode, childElement);
                }
            }
        } else {
            // non-container node

            boolean parentIsRef = SpecificationElementType.REF.equals(element.getParent().getSpecificationElementType());
            boolean nodeIsRef = SpecificationElementType.REF.equals(element.getSpecificationElementType());

            if (parentIsRef || nodeIsRef) {
                // ref node
                TreeSet<String> sortedRefTypes = new TreeSet<>();
                List<Object> effectiveData;
                effectiveData = new ArrayList<>();
                data.add(effectiveData);
                if (parentIsRef) {
                    sortedRefTypes.addAll(element.getParent().getRefTypes());
                } else {
                    //effectiveData = data;
                    sortedRefTypes.addAll(element.getRefTypes());
                }
                Matcher m = linkPattern.matcher(node.textValue());
                if (!m.matches()) {
                    throw new IllegalArgumentException("Bad link format: \"" + node.textValue() + "\"");
                }
                String entity = m.group(1);
                String id = m.group(2);
                boolean typeIsLegal = false;
                for (String refType : sortedRefTypes) {
                    List<Object> linkData = new ArrayList<>();
                    effectiveData.add(linkData);
                    if (refType.equals(entity)) {
                        linkData.add(id);
                        typeIsLegal = true;
                    }
                }
                if (!typeIsLegal) {
                    throw new IllegalArgumentException("Bad link type: \"" + node.textValue() + "\"");
                }

            } else {
                // value node

                if (node.isTextual()) {
                    String str = node.textValue();
                    if (str.length() > 40) {
                        data.add(List.of(str, hashOf(str)));
                    } else {
                        data.add(List.of(str, str));
                    }
                } else if (node.isIntegralNumber()) {
                    data.add(List.of(node.longValue(), node.longValue()));
                } else if (node.isFloatingPointNumber()) {
                    data.add(List.of(node.doubleValue(), node.doubleValue()));
                } else if (node.isBoolean()) {
                    data.add(List.of(node.booleanValue(), node.booleanValue()));
                } else {
                    throw new IllegalStateException("Got unsupported jackson-specific JsonNodeType " + node.getNodeType().name() + ", value: " + node.toString());
                }
            }
        }
    }

    static String hashOf(String input) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digest.length; ++i) {
            sb.append(Integer.toHexString((digest[i] & 0xFF) | 0x100), 1, 3);
        }
        return sb.toString();
    }
}
