package io.descoped.lds.api.persistence.flattened;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

public class PathComparatorTest {

    private TreeMap<String, String> map;

    @BeforeMethod
    public void setUp() {
        map = new TreeMap<>(new PathComparator());
    }

    @Test
    public void testSortSimple() {

        map.put("$.c.c", "");
        map.put("$.c.b", "");
        map.put("$.c.a", "");
        map.put("$.b.c", "");
        map.put("$.b.b", "");
        map.put("$.b.a", "");
        map.put("$.a.c", "");
        map.put("$.a.b", "");
        map.put("$.a.a", "");

        assertThat(map.keySet()).containsExactly(
                "$.a.a",
                "$.a.b",
                "$.a.c",
                "$.b.a",
                "$.b.b",
                "$.b.c",
                "$.c.a",
                "$.c.b",
                "$.c.c"
        );

    }

    @Test
    public void testSortIndices() {
        map.put("$.a[10].c", "");
        map.put("$.a[10].b", "");
        map.put("$.a[10].a", "");
        map.put("$.a[9].c", "");
        map.put("$.a[9].b", "");
        map.put("$.a[9].a", "");
        map.put("$.a[0].c", "");
        map.put("$.a[0].b", "");
        map.put("$.a[0].a", "");

        assertThat(map.keySet()).containsExactly(
                "$.a[0].a",
                "$.a[0].b",
                "$.a[0].c",

                "$.a[9].a",
                "$.a[9].b",
                "$.a[9].c",

                "$.a[10].a",
                "$.a[10].b",
                "$.a[10].c"
        );

    }

    @Test
    public void testSortLeaf() {
        map.put("$.a[9]", "");
        map.put("$.a[10]", "");
        map.put("$.a[0]", "");

        assertThat(map.keySet()).containsExactly(
                "$.a[0]",
                "$.a[9]",
                "$.a[10]"
        );

    }

    @Test
    public void testSortNested() {
        map.put("$.a[10].b[9].b", "");
        map.put("$.a[10].b[9].a", "");
        map.put("$.a[10].b[10].b", "");
        map.put("$.a[10].b[10].a", "");
        map.put("$.a[9].b[10].b", "");
        map.put("$.a[9].b[10].a", "");
        map.put("$.a[9].b[9].b", "");
        map.put("$.a[9].b[9].a", "");

        assertThat(map.keySet()).containsExactly(
                "$.a[9].b[9].a",
                "$.a[9].b[9].b",
                "$.a[9].b[10].a",
                "$.a[9].b[10].b",
                "$.a[10].b[9].a",
                "$.a[10].b[9].b",
                "$.a[10].b[10].a",
                "$.a[10].b[10].b"
        );

    }

    @Test
    public void testSortNestedLeaf() {
        map.put("$.a[10].b[10]", "");
        map.put("$.a[10].b[9]", "");
        map.put("$.a[9].b[10]", "");
        map.put("$.a[9].b[9]", "");

        assertThat(map.keySet()).containsExactly(
                "$.a[9].b[9]",
                "$.a[9].b[10]",
                "$.a[10].b[9]",
                "$.a[10].b[10]"
        );
    }

    @Test
    public void testSortArraysInArrays() {
        map.put("$.a[1][0]", "");
        map.put("$.a[0][10]", "");
        map.put("$.a[0][9]", "");
        map.put("$.a[10][0].b", "");
        map.put("$.a[9][0].b", "");

        assertThat(map.keySet()).containsExactly(
                "$.a[0][9]",
                "$.a[0][10]",
                "$.a[1][0]",
                "$.a[9][0].b",
                "$.a[10][0].b"
        );
    }

    @Test
    public void testSortNamesOfDigits() {
        map.put("$.12345678901234567890123", "");
        map.put("$.9", "");
        map.put("$.10", "");
        map.put("$.007", "");
        map.put("$.7", "");
        map.put("$.", "");

        // "$." is the field without a name: one part less than the others, so it is first
        assertThat(map.keySet()).containsExactly(
                "$.",
                "$.7",
                "$.007",
                "$.9",
                "$.10",
                "$.12345678901234567890123"
        );
    }

    @Test
    public void testNamesOfDigitsComeBeforeOtherNames() {
        map.put("$.1a", "");
        map.put("$.10", "");
        map.put("$.9", "");
        map.put("$.a", "");
        map.put("$.9a", "");

        assertThat(map.keySet()).containsExactly("$.9", "$.10", "$.1a", "$.9a", "$.a");
        assertThat(map).containsKeys("$.9", "$.10", "$.1a", "$.9a", "$.a");
    }

    @Test
    public void testPathsOfTheSamePartsAreNotTheSamePath() {
        map.put("$.a[0]", "a place");
        map.put("$.a.0", "a name");

        assertThat(map).hasSize(2).containsEntry("$.a[0]", "a place").containsEntry("$.a.0", "a name");
    }
}
