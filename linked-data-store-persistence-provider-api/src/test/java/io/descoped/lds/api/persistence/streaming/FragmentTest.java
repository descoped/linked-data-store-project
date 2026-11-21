package io.descoped.lds.api.persistence.streaming;

import io.descoped.lds.api.persistence.DocumentKey;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class FragmentTest {

    @Test
    public void thatArrayIndicesUnawarePathAndCorrectIndicesAreExtracted() {
        Fragment fragment = new Fragment(new DocumentKey("", "", "", ZonedDateTime.now(ZoneId.of("Etc/UTC"))), "$.a.b[3103].c.d[2].e", FragmentType.NULL, 0, null);
        ArrayList<Integer> indices = new ArrayList<>();
        String actualPath = Fragment.computeIndexUnawarePath(fragment.path(), indices);
        Assert.assertEquals(actualPath, "$.a.b[].c.d[].e");
        Assert.assertEquals(indices, List.of(3103, 2));
    }

    @Test
    public void thatArrayIndicesAndUnawarePathAreCorrectForEmptyPath() {
        Fragment fragment = new Fragment(new DocumentKey("", "", "", ZonedDateTime.now(ZoneId.of("Etc/UTC"))), "", FragmentType.NULL, 0, null);
        ArrayList<Integer> indices = new ArrayList<>();
        String actualPath = Fragment.computeIndexUnawarePath(fragment.path(), indices);
        Assert.assertEquals(actualPath, "");
        Assert.assertEquals(indices, List.of());
    }

    @Test
    public void thatArrayIndicesAndUnawarePathAreCorrectForPathWithoutArrays() {
        Fragment fragment = new Fragment(new DocumentKey("", "", "", ZonedDateTime.now(ZoneId.of("Etc/UTC"))), "$.a.b.c", FragmentType.NULL, 0, null);
        ArrayList<Integer> indices = new ArrayList<>();
        String actualPath = Fragment.computeIndexUnawarePath(fragment.path(), indices);
        Assert.assertEquals(actualPath, "$.a.b.c");
        Assert.assertEquals(indices, List.of());
    }

    @Test
    public void thatArrayIndicesAndUnawarePathAreCorrectForPathWithoutArrayIndices() {
        Fragment fragment = new Fragment(new DocumentKey("", "", "", ZonedDateTime.now(ZoneId.of("Etc/UTC"))), "$.a.b[].c.d[0].e.f[].g", FragmentType.NULL, 0, null);
        ArrayList<Integer> indices = new ArrayList<>();
        String actualPath = Fragment.computeIndexUnawarePath(fragment.path(), indices);
        Assert.assertEquals(actualPath, "$.a.b[].c.d[].e.f[].g");
        ArrayList<Integer> expected = new ArrayList<>();
        expected.add(null);
        expected.add(0);
        expected.add(null);
        Assert.assertEquals(indices, expected);
    }

    @Test
    public void thatAStreamingControlFragmentComesAfterEveryOtherFragment() {
        ZonedDateTime time = ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneId.of("Etc/UTC"));
        Fragment a = new Fragment(new DocumentKey("ns", "E", "a", time), "$.x", FragmentType.STRING, 0, new byte[]{1});
        Fragment b = new Fragment(new DocumentKey("ns", "E", "b", time), "$.x", FragmentType.STRING, 0, new byte[]{1});
        Fragment control = new Fragment(true, Fragment.LIMITED_CODE, null, null, null, 0, null);
        Assert.assertTrue(control.compareTo(a) > 0);
        Assert.assertTrue(a.compareTo(control) < 0);

        List<Fragment> fragments = new ArrayList<>(List.of(control, b, a));
        fragments.sort(Comparator.naturalOrder());
        Assert.assertEquals(fragments, List.of(a, b, control));
        fragments.sort(Comparator.reverseOrder());
        Assert.assertEquals(fragments, List.of(control, b, a));
    }
}
