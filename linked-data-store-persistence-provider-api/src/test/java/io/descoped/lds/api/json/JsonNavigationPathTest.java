package io.descoped.lds.api.json;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.expectThrows;

public class JsonNavigationPathTest {

    @Test
    public void thatDeserializationAndSerializationAreCompatible() {
        assertEquals(JsonNavigationPath.from("$").serialize(), "$");
        assertEquals(JsonNavigationPath.from("$[]").serialize(), "$[]");
        assertEquals(JsonNavigationPath.from("$[1]").serialize(), "$[1]");
        assertEquals(JsonNavigationPath.from("$[][]").serialize(), "$[][]");
        assertEquals(JsonNavigationPath.from("$.a.b.c").serialize(), "$.a.b.c");
        assertEquals(JsonNavigationPath.from("$.singleTopLevelField").serialize(), "$.singleTopLevelField");
        assertEquals(JsonNavigationPath.from("$.a[].b[][].c.d[][][]").serialize(), "$.a[].b[][].c.d[][][]");
        assertEquals(JsonNavigationPath.from("$.a[].b[][].c.d[][][].x.y").serialize(), "$.a[].b[][].c.d[][][].x.y");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void thatArrayElementByItselfIsIllegal() {
        JsonNavigationPath.from("$.[].a");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void thatArrayElementMalformed1IsIllegal() {
        JsonNavigationPath.from("$.[]x");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void thatArrayElementMalformed2IsIllegal() {
        JsonNavigationPath.from("$.x[");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void thatArrayElementMalformed3IsIllegal() {
        JsonNavigationPath.from("$.x]");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void thatArrayElementMalformed4IsIllegal() {
        JsonNavigationPath.from("$.x[]y");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void thatArrayElementMalformed5IsIllegal() {
        JsonNavigationPath.from("$.x[aw]y");
    }

    @Test
    public void thatElementWithStrayOpeningBracketIsIllegal() {
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> JsonNavigationPath.from("$", "a", "b[c"));
        assertEquals(e.getClass(), IllegalArgumentException.class);
        assertEquals(e.getMessage(), "json-path element at index 2 contain square bracket symbol without being formatted as a proper array element");
    }

    @Test
    public void thatElementWithStrayClosingBracketIsIllegal() {
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> JsonNavigationPath.from("$", "b]c"));
        assertEquals(e.getClass(), IllegalArgumentException.class);
        assertEquals(e.getMessage(), "json-path element at index 1 contain square bracket symbol without being formatted as a proper array element");
    }

    /**
     * A name that holds a dot is written with an escape before it, as the providers write the path of a value
     * (FieldName), and read back as the name it is.
     */
    @Test
    public void thatADotInANameIsEscaped() {
        assertEquals(JsonNavigationPath.from("$", "a.b", "[]", "c").serialize(), "$.a\\.b[].c");
        assertEquals(JsonNavigationPath.from("$", "a\\").serialize(), "$.a\\\\");
        assertEquals(JsonNavigationPath.from("$.a\\.b[].c").getPath(), new String[]{"$", "a.b", "[]", "c"});
        assertEquals(JsonNavigationPath.from("$.a\\\\.b").getPath(), new String[]{"$", "a\\", "b"});
        assertEquals(JsonNavigationPath.from("$.a\\.b[]").serialize(), "$.a\\.b[]");
        // a name with a bracket is no part of a path of the model
        expectThrows(IllegalArgumentException.class, () -> JsonNavigationPath.from("$.a\\[b"));
    }

    @Test
    public void thatPopBackRemovesTheLastElement() {
        assertEquals(JsonNavigationPath.from("$.a[].b").popBack().getPath(), new String[]{"$", "a", "[]"});
    }
}
