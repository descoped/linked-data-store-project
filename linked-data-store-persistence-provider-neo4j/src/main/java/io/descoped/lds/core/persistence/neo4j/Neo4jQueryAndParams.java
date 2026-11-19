package io.descoped.lds.core.persistence.neo4j;

import java.util.Map;

/**
 * A statement and the parameters to run it with.
 *
 * @param query  the Cypher
 * @param params the parameters by name
 */
record Neo4jQueryAndParams(String query, Map<String, Object> params) {
}
