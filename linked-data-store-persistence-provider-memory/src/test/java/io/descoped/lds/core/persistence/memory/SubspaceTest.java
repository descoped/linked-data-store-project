package io.descoped.lds.core.persistence.memory;

import io.descoped.tuple.Range;
import io.descoped.tuple.Tuple;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatObject;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubspaceTest {

    private final Subspace subspace = new Subspace(new byte[]{0, 7});

    @Test
    void aTupleIsPackedBehindThePrefixAndUnpackedAgain() {
        Tuple tuple = Tuple.of("id", Tuple.of(-2020, -6L), "$.name", new byte[]{4}, 8192L);

        byte[] key = subspace.pack(tuple);

        assertThat(key).startsWith(0, 7).hasSize(2 + tuple.pack().length);
        assertThat(subspace.contains(key)).isTrue();
        Tuple unpacked = subspace.unpack(key);
        assertThatObject(unpacked).isEqualTo(tuple);
        assertThat(unpacked.getString(0)).isEqualTo("id");
        assertThat(unpacked.getNestedTuple(1).getLong(1)).isEqualTo(-6L);
        assertThat(unpacked.getBytes(3)).containsExactly(4);
        assertThat(unpacked.getLong(4)).isEqualTo(8192L);
    }

    @Test
    void aKeyOfAnotherSubspaceIsNotUnpacked() {
        byte[] foreign = new Subspace(new byte[]{0, 8}).pack(Tuple.of("id"));

        assertThat(subspace.contains(foreign)).isFalse();
        assertThat(subspace.contains(new byte[]{0})).isFalse();
        assertThatThrownBy(() -> subspace.unpack(foreign)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRangeOfTheSubspaceHoldsItsTuplesOnly() {
        Range range = subspace.range();

        assertThat(range.begin()).containsExactly(0, 7, 0);
        assertThat(range.end()).containsExactly(0, 7, 0xff);
        assertThat(range.contains(subspace.pack(Tuple.of("id", 1)))).isTrue();
        assertThat(range.contains(subspace.pack(Tuple.of(-1)))).isTrue();
        assertThat(range.contains(new Subspace(new byte[]{0, 8}).pack(Tuple.of("id")))).isFalse();
        assertThat(range.contains(new Subspace(new byte[]{0, 6}).pack(Tuple.of("id")))).isFalse();
    }

    @Test
    void theRangeOfATupleHoldsTheLongerTuplesThatStartWithIt() {
        Range range = subspace.range(Tuple.of("a"));

        assertThat(range.contains(subspace.pack(Tuple.of("a", 1)))).isTrue();
        assertThat(range.contains(subspace.pack(Tuple.of("a", "b", new byte[]{1})))).isTrue();
        assertThat(range.contains(subspace.pack(Tuple.of("a")))).isFalse();
        assertThat(range.contains(subspace.pack(Tuple.of("ab", 1)))).isFalse();
        assertThat(range.contains(subspace.pack(Tuple.of("b", 1)))).isFalse();
        assertThat(range.contains(new Subspace(new byte[]{0, 8}).pack(Tuple.of("a", 1)))).isFalse();
    }

    @Test
    void theSubspaceKeepsItsOwnCopyOfThePrefix() {
        byte[] prefix = {0, 7};
        Subspace copied = new Subspace(prefix);
        prefix[1] = 9;

        assertThat(copied.pack(Tuple.of("id"))).startsWith(0, 7);
    }
}
