package io.descoped.lds.core.persistence.neo4j;

/**
 * Writes names and texts into Cypher. A label cannot be a parameter of a statement, and the statement that writes
 * a document is built once for each entity with the paths of its fields in it, so these are quoted and escaped:
 * whatever the name or the text holds, it stays a name or a text.
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

    /**
     * A string literal, in single quotes.
     *
     * @param text the text as it is, any characters
     * @return the text as Cypher reads it
     */
    static String text(String text) {
        return "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
