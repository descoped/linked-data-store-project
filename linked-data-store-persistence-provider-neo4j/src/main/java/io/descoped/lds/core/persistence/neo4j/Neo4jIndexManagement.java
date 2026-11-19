package io.descoped.lds.core.persistence.neo4j;

import org.neo4j.driver.Result;
import org.neo4j.driver.SimpleQueryRunner;
import org.neo4j.driver.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The constraints and indexes the provider needs, for each entity it manages:
 * <ul>
 * <li>a document is one node with the entity as its label, unique by namespace and id;</li>
 * <li>a value is a node with the label {@code <entity>_E}, indexed by namespace, path and value (or the hash of a
 * long value), which is what a search for a value looks up.</li>
 * </ul>
 * What is missing is created at start. An index that is there and not needed is left alone and named in the log,
 * with the statement that drops it.
 */
final class Neo4jIndexManagement {

    private static final Logger LOG = LoggerFactory.getLogger(Neo4jIndexManagement.class);

    private final Set<Index> wantedIndexes;
    private final Set<Index> currentIndexes;

    static final class Index {
        final String label;
        final List<String> properties;
        final boolean uniqueConstraint;
        /* The name of the constraint, or of the index when there is no constraint; null for one that is wanted. */
        final String name;

        Index(String label, List<String> properties, boolean uniqueConstraint, String name) {
            this.label = label;
            this.properties = List.copyOf(properties);
            this.uniqueConstraint = uniqueConstraint;
            this.name = name;
        }

        private String propertiesOfN() {
            return properties.stream().map(k -> "n." + Cypher.label(k)).collect(Collectors.joining(", "));
        }

        /* "IF NOT EXISTS": instances that start together all ask for the same constraints and indexes. */
        String createCypherStatement() {
            if (uniqueConstraint) {
                return "CREATE CONSTRAINT IF NOT EXISTS FOR (n:" + Cypher.label(label) + ") REQUIRE (" + propertiesOfN() + ") IS UNIQUE";
            }
            return "CREATE INDEX IF NOT EXISTS FOR (n:" + Cypher.label(label) + ") ON (" + propertiesOfN() + ")";
        }

        String deleteCypherStatement() {
            return (uniqueConstraint ? "DROP CONSTRAINT " : "DROP INDEX ") + Cypher.label(name);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Index index = (Index) o;
            return uniqueConstraint == index.uniqueConstraint &&
                    label.equals(index.label) &&
                    properties.equals(index.properties);
        }

        @Override
        public int hashCode() {
            return Objects.hash(label, properties, uniqueConstraint);
        }

        @Override
        public String toString() {
            return "Index{" +
                    "label='" + label + '\'' +
                    ", properties=" + properties +
                    ", unique=" + uniqueConstraint +
                    '}';
        }
    }

    Neo4jIndexManagement(SimpleQueryRunner transaction, Set<String> managedDomains) {
        currentIndexes = new LinkedHashSet<>();
        Result indexes = transaction.run(
                "SHOW INDEXES YIELD name, labelsOrTypes, properties, owningConstraint WHERE labelsOrTypes IS NOT NULL");
        indexes.forEachRemaining(row -> {
            List<String> labels = row.get("labelsOrTypes").asList(Value::asString);
            if (!labels.isEmpty()) {
                List<String> propertyList = row.get("properties").asList(Value::asString);
                Value constraint = row.get("owningConstraint");
                currentIndexes.add(constraint.isNull()
                        ? new Index(labels.getFirst(), propertyList, false, row.get("name").asString())
                        : new Index(labels.getFirst(), propertyList, true, constraint.asString()));
            }
        });
        wantedIndexes = new LinkedHashSet<>();
        for (String managedDomain : managedDomains) {
            wantedIndexes.add(new Index(managedDomain, List.of("namespace", "id"), true, null));
            wantedIndexes.add(new Index(managedDomain + "_E", List.of("namespace", "path", "hashOrValue"), false, null));
        }
    }

    void createMissingIndices(SimpleQueryRunner transaction) {
        for (Index index : currentIndexes) {
            if (!wantedIndexes.contains(index) && LOG.isInfoEnabled()) {
                LOG.info("Not using index: {}. Use the following cypher statement to drop: {}", index, index.deleteCypherStatement());
            }
        }
        for (Index index : wantedIndexes) {
            if (!currentIndexes.contains(index)) {
                String statement = index.createCypherStatement();
                LOG.info("Creating: {}", statement);
                transaction.run(statement).consume();
            }
        }
    }
}
