package io.descoped.lds.api.persistence.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.descoped.lds.api.json.JsonNavigationPath;
import io.descoped.lds.api.persistence.DocumentKey;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a walk along a path visits in a document.
 */
class JsonDocumentTest {

    private static List<String> visited(String document, String path) {
        List<String> visited = new ArrayList<>();
        try {
            new JsonDocument(new DocumentKey("ns", "E", "1", ZonedDateTime.now()), document == null ? null : JsonTools.mapper.readTree(document.replace('\'', '"')))
                    .traverseField(JsonNavigationPath.from(path), (node, at) -> visited.add(at + "=" + node));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(document, e);
        }
        return visited;
    }

    @Test
    void aFieldIsVisitedWithItsPath() {
        assertThat(visited("{'a':{'b':'x'}}", "$.a.b")).containsExactly("$.a.b=\"x\"");
        assertThat(visited("{'a':{'b':null}}", "$.a.b")).containsExactly("$.a.b=null");
    }

    @Test
    void everyElementOfAnArrayIsVisitedWithItsPlace() {
        assertThat(visited("{'list':['a','b']}", "$.list[]")).containsExactly("$.list[0]=\"a\"", "$.list[1]=\"b\"");
        assertThat(visited("{'name':[{'first':'J'},{'first':'K'}]}", "$.name[].first")).containsExactly("$.name[0].first=\"J\"", "$.name[1].first=\"K\"");
    }

    @Test
    void whatADocumentDoesNotHaveIsNotVisited() {
        // a field that is not there, in the document and in one element of an array
        assertThat(visited("{'a':1}", "$.b")).isEmpty();
        assertThat(visited("{'a':1}", "$.b.c")).isEmpty();
        assertThat(visited("{'name':[{'first':'J','middle':'M'},{'last':'D'}]}", "$.name[].middle")).containsExactly("$.name[0].middle=\"M\"");
        // not an array where the path says array, not an object where it says object
        assertThat(visited("{'list':'text'}", "$.list[]")).isEmpty();
        assertThat(visited("{'list':{'a':1}}", "$.list[]")).isEmpty();
        assertThat(visited("{'a':'text'}", "$.a.b")).isEmpty();
        assertThat(visited("{'a':['x']}", "$.a.b")).isEmpty();
        assertThat(visited("{'a':null}", "$.a.b")).isEmpty();
        assertThat(visited("{'list':[null,{'b':2}]}", "$.list[].b")).containsExactly("$.list[1].b=2");
        // a document that is deleted
        assertThat(visited(null, "$.a")).isEmpty();
    }
}
