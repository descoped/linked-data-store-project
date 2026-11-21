package io.descoped.lds.api.persistence.flattened;

import io.descoped.lds.api.persistence.DocumentKey;
import io.descoped.lds.api.persistence.streaming.Fragment;
import io.descoped.lds.api.persistence.streaming.FragmentType;
import org.junit.jupiter.api.Test;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A document made of its fragments, path by path: a delete marker anywhere makes the document a deleted one, a path
 * without fragments and bytes that are not UTF-8 are errors; and what a flattened document answers.
 */
class FlattenedDocumentDecodeTest {

    private static final DocumentKey KEY =
            new DocumentKey("ns", "E", "1", ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));

    private static Fragment fragment(String path, FragmentType type, byte[] value) {
        return new Fragment(KEY, path, type, 0, value);
    }

    private static Fragment text(String path, String value) {
        return fragment(path, FragmentType.STRING, value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aDocumentAnswersItsLeavesByPathAndByValue() {
        Map<String, List<Fragment>> fragments = new LinkedHashMap<>();
        fragments.put("$.name", List.of(text("$.name", "Ann")));
        fragments.put("$.age", List.of(fragment("$.age", FragmentType.NUMERIC, "42".getBytes(StandardCharsets.UTF_8))));

        FlattenedDocument document = FlattenedDocument.decodeDocument(KEY, fragments, 8);

        assertThat(document.key()).isEqualTo(KEY);
        assertThat(document.deleted()).isFalse();
        assertThat(document.leaf("$.name").value()).isEqualTo("Ann");
        assertThat(document.leaf("$.missing")).isNull();
        assertThat(document.contains("$.name", "Ann")).isTrue();
        assertThat(document.contains("$.name", "Bob")).isFalse();
        assertThat(document.contains("$.missing", "Ann")).isFalse();
        assertThat(document.toString()).startsWith("FlattenedDocument{key=").endsWith(", deleted=false}");
    }

    @Test
    void documentsWithTheSameKeyLeavesAndDeletionAreEqual() {
        Map<String, List<Fragment>> fragments = Map.of("$.name", List.of(text("$.name", "Ann")));
        FlattenedDocument document = FlattenedDocument.decodeDocument(KEY, fragments, 8);
        FlattenedDocument same = FlattenedDocument.decodeDocument(KEY, fragments, 8);

        assertThat(document)
                .isEqualTo(document)
                .isEqualTo(same)
                .hasSameHashCodeAs(same)
                .isNotEqualTo(null)
                .isNotEqualTo("a document")
                .isNotEqualTo(new FlattenedDocument(KEY, document.leafNodesByPath(), true))
                .isNotEqualTo(new FlattenedDocument(KEY, Map.of(), false));
    }

    @Test
    void aDeleteMarkerAmongTheFragmentsOfATextMakesTheWholeDocumentDeleted() {
        Map<String, List<Fragment>> fragments = new LinkedHashMap<>();
        fragments.put("$.a", List.of(text("$.a", "kept until the marker")));
        fragments.put("$.b", List.of(text("$.b", "half"), fragment("$.b", FragmentType.DELETED, new byte[0])));

        FlattenedDocument document = FlattenedDocument.decodeDocument(KEY, fragments, 8);

        assertThat(document.deleted()).isTrue();
        assertThat(document.leafNodesByPath()).isEmpty();
    }

    @Test
    void aPathWithoutFragmentsIsAnError() {
        Map<String, List<Fragment>> fragments = Map.of("$.a", List.of());

        assertThatThrownBy(() -> FlattenedDocument.decodeDocument(KEY, fragments, 8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No fragments for path: $.a");
    }

    @Test
    void aFragmentWithoutATypeIsAnError() {
        Map<String, List<Fragment>> fragments = Map.of("$.a", List.of(fragment("$.a", null, new byte[0])));

        assertThatThrownBy(() -> FlattenedDocument.decodeDocument(KEY, fragments, 8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unknown FragmentType: null");
    }

    @Test
    void bytesThatAreNotUtf8AreAnError() {
        Map<String, List<Fragment>> fragments =
                Map.of("$.a", List.of(fragment("$.a", FragmentType.STRING, new byte[]{'b', (byte) 0xE5, 'r'})));

        assertThatThrownBy(() -> FlattenedDocument.decodeDocument(KEY, fragments, 8))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(CharacterCodingException.class);
    }
}
