package io.descoped.lds.core.persistence.neo4j;

/**
 * Writes the name of an entity into Cypher, as a label. Everything else a statement is given is a parameter:
 * namespaces, ids, values, paths and field names.
 * <p>
 * A label can be a parameter too ({@code (r:$($entity))}), and then Neo4j plans without the label: measured on
 * Neo4j 2026.10, a page of three from a list of 50,000 documents read all 50,000 and sorted them, where the label
 * in the statement gives a seek in the index of namespace and {@code idOrder}, already in order. A constraint or an index
 * cannot be created with a label as a parameter at all. So the label is quoted: whatever the name holds, it stays
 * a name.
 */
final class Cypher {

    private Cypher() {
    }

    /**
     * A label or a relationship type, in backticks.
     *
     * @param name the name as it is, any characters
     * @return the name as Cypher reads it
     */
    static String label(String name) {
        return "`" + name.replace("`", "``") + "`";
    }
}
