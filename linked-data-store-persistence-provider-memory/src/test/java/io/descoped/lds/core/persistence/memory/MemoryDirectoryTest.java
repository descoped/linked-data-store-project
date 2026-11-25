package io.descoped.lds.core.persistence.memory;

import io.descoped.tuple.Tuple;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MemoryDirectoryTest {

    /*
     * Every subspace has keys of its own, also past 65536 subspaces: no key of one is in another. When no prefix
     * begins another, no key can be in two subspaces, and of the prefixes in order it is enough to compare each with
     * the next.
     */
    @Test
    public void thatNoTwoSubspacesShareAKey() {
        MemoryDirectory directory = new MemoryDirectory();
        int subspaces = 65536 * 2 + 1;
        List<Subspace> allocated = new ArrayList<>();
        for (int i = 0; i < subspaces; i++) {
            allocated.add(directory.createOrOpen(Tuple.of("ns", "Primary", "Entity" + i)).join());
        }

        byte[] keyOfTheFirst = allocated.get(0).pack(Tuple.of("id"));
        Assert.assertFalse(allocated.get(65536).contains(keyOfTheFirst));
        Assert.assertFalse(allocated.get(65536 * 2).contains(keyOfTheFirst));

        List<byte[]> prefixes = new ArrayList<>();
        for (Subspace subspace : allocated) {
            prefixes.add(subspace.range().begin()); // the prefix followed by 0x00
        }
        prefixes.sort(Arrays::compareUnsigned);
        for (int i = 1; i < prefixes.size(); i++) {
            byte[] previous = Arrays.copyOf(prefixes.get(i - 1), prefixes.get(i - 1).length - 1);
            byte[] next = prefixes.get(i);
            boolean begins = next.length - 1 >= previous.length
                    && Arrays.equals(next, 0, previous.length, previous, 0, previous.length);
            Assert.assertFalse(begins, "a prefix begins the next one");
        }
    }

    @Test
    public void thatTheSameTupleOpensTheSameSubspace() {
        MemoryDirectory directory = new MemoryDirectory();
        Subspace first = directory.createOrOpen(Tuple.of("ns", "Primary", "Person")).join();
        directory.createOrOpen(Tuple.of("ns", "Primary", "Address")).join();
        Assert.assertSame(directory.createOrOpen(Tuple.of("ns", "Primary", "Person")).join(), first);
    }
}
