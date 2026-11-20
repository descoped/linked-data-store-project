package io.descoped.lds.api.persistence.json;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.flattened.FlattenedDocument;
import io.descoped.lds.api.persistence.flattened.FlattenedDocumentLeafNode;
import io.descoped.lds.api.persistence.streaming.FragmentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A document that is taken apart into leaves is the same document when it is put together again.
 */
class FlattenedDocumentToJsonTest {

    private static final DocumentKey KEY = new DocumentKey("ns", "E", "1", ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneId.of("Etc/UTC")));

    private static JsonNode json(String text) {
        try {
            return JsonTools.mapper.readTree(text.replace('\'', '"'));
        } catch (JacksonException e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    private static JsonNode rebuilt(JsonNode document) {
        FlattenedDocument leaves = new JsonToFlattenedDocument("ns", "E", "1", KEY.timestamp(), document, 8192).toDocument();
        return new FlattenedDocumentToJson(leaves).toJsonNode();
    }

    private static FlattenedDocumentLeafNode leaf(String path, FragmentType type, String value) {
        return new FlattenedDocumentLeafNode(KEY, path, type, value, 8192);
    }

    private static FlattenedDocument leaves(Object... pathTypeValue) {
        Map<String, FlattenedDocumentLeafNode> leaves = new LinkedHashMap<>();
        for (int i = 0; i < pathTypeValue.length; i += 3) {
            String path = (String) pathTypeValue[i];
            leaves.put(path, new FlattenedDocumentLeafNode(KEY, path, (FragmentType) pathTypeValue[i + 1], (String) pathTypeValue[i + 2], 8192));
        }
        return new FlattenedDocument(KEY, leaves, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{'a':'text','b':7,'c':1.5,'d':true,'e':false,'f':null,'g':'','h':12345678901}",
            "{'object':{},'array':[]}",
            "{'a':{'b':{'c':{'d':'deep'}}}}",
            "{'name':[{'first':'John','last':'Smith'},{'first':'Jane','last':'Doe'}]}",
            "{'list':['a',2,false,null,1.5]}",
            // an array of values, objects, arrays, empty ones and nulls side by side, in every order
            "{'more':[{'a':1},{'a':2,'b':[3,4]},{}]}",
            "{'more':[{},{'a':1},null]}",
            "{'more':[null,{'a':{'b':1}},'text',[],{},[1]]}",
            "{'more':[{'a':[{'b':[{'c':1},{}]},null]},7]}",
            // an array directly in an array
            "{'grid':[[1,2],[3]]}",
            "{'grid':[[[1],[2,3]],[],[[]],[{'a':[[true]]}]]}",
            "{'eleven':[0,1,2,3,4,5,6,7,8,9,10,11],'rows':[[0],[1],[2],[3],[4],[5],[6],[7],[8],[9],[10,'ten']]}",
            // names that are not plain words
            "{'my field':'v','':'no name','7':'digits','007':'more digits','12345678901234567890123':'many digits'}",
            "{'a':{'0':'a name'},'b':['a place']}",
            "{'$':{'$':1}}"
    })
    void aDocumentIsTheSameWhenItIsPutTogetherAgain(String document) {
        assertThat(rebuilt(json(document))).isEqualTo(json(document));
    }

    @Test
    void aDocumentWithoutLeavesIsAnEmptyObject() {
        assertThat(new FlattenedDocumentToJson(leaves()).toJsonNode()).isEqualTo(json("{}"));
    }

    @Test
    void theLeavesMayComeInAnyOrder() {
        FlattenedDocument document = leaves(
                "$.name[1].last", FragmentType.STRING, "Doe",
                "$.name[2]", FragmentType.EMPTY_OBJECT, null,
                "$.name[0].first", FragmentType.STRING, "John",
                "$.name[3]", FragmentType.NULL, null,
                "$.name[1].first", FragmentType.STRING, "Jane");

        assertThat(new FlattenedDocumentToJson(document).toJsonNode())
                .isEqualTo(json("{'name':[{'first':'John'},{'first':'Jane','last':'Doe'},{},null]}"));
    }

    @Test
    void aLeafThatSaysObjectOrArrayTakesNothingAwayWhereverItComes() {
        // as Neo4j gives the leaves: one for every object and array, also those that hold something
        List<FlattenedDocumentLeafNode> leaves = List.of(
                leaf("$.a.b[0].c", FragmentType.STRING, "deep"),
                leaf("$.a", FragmentType.EMPTY_OBJECT, null),
                leaf("$.a.b[0]", FragmentType.EMPTY_OBJECT, null),
                leaf("$.a.b", FragmentType.EMPTY_ARRAY, null),
                leaf("$.a.b[1]", FragmentType.EMPTY_ARRAY, null),
                leaf("$", FragmentType.EMPTY_OBJECT, null),
                leaf("$.a.e", FragmentType.EMPTY_OBJECT, null));
        JsonNode expected = json("{'a':{'b':[{'c':'deep'},[]],'e':{}}}");

        assertThat(FlattenedDocumentToJson.toJsonNode(leaves)).isEqualTo(expected);
        List<FlattenedDocumentLeafNode> reversed = new ArrayList<>(leaves);
        Collections.reverse(reversed);
        assertThat(FlattenedDocumentToJson.toJsonNode(reversed)).isEqualTo(expected);
    }

    @Test
    void aPlaceThatNoLeafNamesLeavesNoGap() {
        FlattenedDocument document = leaves(
                "$.list[5]", FragmentType.STRING, "five",
                "$.list[2]", FragmentType.STRING, "two",
                "$.list[999999999]", FragmentType.STRING, "last");

        assertThat(new FlattenedDocumentToJson(document).toJsonNode()).isEqualTo(json("{'list':['two','five','last']}"));
    }

    @Test
    void aNumberIsTheSmallestOfIntLongAndDoubleThatHoldsIt() {
        JsonNode document = new FlattenedDocumentToJson(leaves(
                "$.int", FragmentType.NUMERIC, "2147483647",
                "$.long", FragmentType.NUMERIC, "2147483648",
                "$.fraction", FragmentType.NUMERIC, "1.5",
                "$.big", FragmentType.NUMERIC, "12345678901234567890")).toJsonNode();

        assertThat(document.get("int").isInt()).isTrue();
        assertThat(document.get("long").isLong()).isTrue();
        assertThat(document.get("long").longValue()).isEqualTo(2147483648L);
        assertThat(document.get("fraction").isDouble()).isTrue();
        assertThat(document.get("big").doubleValue()).isEqualTo(1.2345678901234567E19);
    }

    @Test
    void aDeletedLeafAndATextThatIsNotThereAreNull() {
        JsonNode document = new FlattenedDocumentToJson(leaves(
                "$.deleted", FragmentType.DELETED, null,
                "$.text", FragmentType.STRING, null,
                "$.yes", FragmentType.BOOLEAN, "true")).toJsonNode();

        assertThat(document).isEqualTo(json("{'deleted':null,'text':null,'yes':true}"));
    }

    @Test
    void thePathOfALeafIsNamesAndPlaces() {
        assertThat(FlattenedDocumentToJson.steps("$")).isEmpty();
        assertThat(FlattenedDocumentToJson.steps("$.name[1].first")).containsExactly("name", 1, "first");
        assertThat(FlattenedDocumentToJson.steps("$.grid[0][12]")).containsExactly("grid", 0, 12);
        assertThat(FlattenedDocumentToJson.steps("$.")).containsExactly("");
        // more digits than a place has, and brackets that hold no place, are part of the name
        assertThat(FlattenedDocumentToJson.steps("$.a[1234567890]")).containsExactly("a[1234567890]");
        assertThat(FlattenedDocumentToJson.steps("$.a[x].b[]")).containsExactly("a[x]", "b[]");
    }

    @Test
    void thePlacesAtTheEndOfAPartAreTheLongestRunOfPlaces() {
        assertThat(FlattenedDocumentToJson.steps("$[0]")).containsExactly(0);
        assertThat(FlattenedDocumentToJson.steps("$[0][1].a")).containsExactly(0, 1, "a");
        assertThat(FlattenedDocumentToJson.steps("$.a[0]b[1]")).containsExactly("a[0]b", 1);
        assertThat(FlattenedDocumentToJson.steps("$.a[1][1234567890]")).containsExactly("a[1][1234567890]");
        assertThat(FlattenedDocumentToJson.steps("$.a[1234567890][1]")).containsExactly("a[1234567890]", 1);
        assertThat(FlattenedDocumentToJson.steps("$.a[123456789]")).containsExactly("a", 123456789);
        assertThat(FlattenedDocumentToJson.steps("$.a[]][2]")).containsExactly("a[]]", 2);
        assertThat(FlattenedDocumentToJson.steps("$.a[[3]")).containsExactly("a[", 3);
        assertThat(FlattenedDocumentToJson.steps("$.[4][5]")).containsExactly("", 4, 5);
        assertThat(FlattenedDocumentToJson.steps("$.a\n[6]")).containsExactly("a\n", 6);
        assertThat(FlattenedDocumentToJson.steps("$.a[\u0663]")).containsExactly("a[\u0663]"); // an Arabic-Indic digit is not a place
    }

    @Test
    void aPartWithVeryManyPlacesOrAVeryLongNameDoesNotOverflowTheStack() {
        assertThat(FlattenedDocumentToJson.steps("$.a" + "[1]".repeat(100_000))).hasSize(100_001).startsWith("a", 1);
        assertThat(FlattenedDocumentToJson.steps("$." + "x".repeat(100_000) + "[2]")).containsExactly("x".repeat(100_000), 2);
    }
}
