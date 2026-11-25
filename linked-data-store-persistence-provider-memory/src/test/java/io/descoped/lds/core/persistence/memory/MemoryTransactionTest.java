package io.descoped.lds.core.persistence.memory;

import io.descoped.tuple.Range;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static io.descoped.lds.core.persistence.memory.OrderedKeyValueTransaction.ROW_LIMIT_UNLIMITED;

public class MemoryTransactionTest {

    @Test
    public void thatTransactionRollsBackOnCancel() {
        MemoryTransactionFactory factory = new MemoryTransactionFactory(false);
        try (MemoryTransaction tx = factory.createTransaction(false)) {
            tx.set(new byte[]{1, 2, 2}, new byte[]{1, 2, 2}, "i1");
            tx.set(new byte[]{1, 2, 3}, new byte[]{1, 2, 3}, "i1");
            tx.set(new byte[]{1, 2, 3, 0}, new byte[]{1, 2, 3, 0}, "i1");
            tx.set(new byte[]{1, 2, 3, 1}, new byte[]{1, 2, 3, 1}, "i1");
            tx.set(new byte[]{1, 2, 3, 2}, new byte[]{1, 2, 3, 2}, "i1");
            tx.set(new byte[]{1, 2, 3, 3}, new byte[]{1, 2, 3, 3}, "i1");
            tx.set(new byte[]{1, 2, 3, 4}, new byte[]{1, 2, 3, 4}, "i1");
            tx.set(new byte[]{1, 2, 3, 5}, new byte[]{1, 2, 3, 5}, "i1");
            tx.set(new byte[]{1, 2, 3, 5, 1}, new byte[]{1, 2, 3, 5, 1}, "i1");
            tx.set(new byte[]{1, 2, 3, 6}, new byte[]{1, 2, 3, 6}, "i1");
            tx.set(new byte[]{1, 2, 3, 7}, new byte[]{1, 2, 3, 7}, "i1");
            tx.set(new byte[]{1, 2, 3, 8}, new byte[]{1, 2, 3, 8}, "i1");
            tx.set(new byte[]{1, 2, 3, 9}, new byte[]{1, 2, 3, 9}, "i1");
            tx.set(new byte[]{1, 2, 4}, new byte[]{1, 2, 4}, "i1");
            tx.set(new byte[]{1, 2, 5}, new byte[]{1, 2, 5}, "i1");
        }

        // capture initial state
        List<KeyValue> expected = copyDatabase(factory);

        // change data within a transaction and assert that change is visible within transaction context, then roll-back
        try (MemoryTransaction tx = factory.createTransaction(false)) {

            // clear elements in a specific range
            List<KeyValue> beforeClearRange = getRange(tx,
                    firstGreaterOrEqual(new byte[]{1, 2, 3, 4, 5}),
                    KeySelector.lastLessOrEqual(new byte[]{1, 2, 3, 6}), "i1");
            Assert.assertEquals(beforeClearRange.size(), 2); // confirm that range contains 2 elements
            tx.clearRange(startsWith(new byte[]{1, 2, 3, 5}), "i1");
            List<KeyValue> afterClearRange = getRange(tx,
                    firstGreaterOrEqual(new byte[]{1, 2, 3, 4, 5}),
                    KeySelector.lastLessOrEqual(new byte[]{1, 2, 3, 6}), "i1");
            Assert.assertEquals(afterClearRange.size(), 0); // confirm that range was cleared

            // replace an existing value with a new value
            List<KeyValue> rangeResultBeforeNewValue = getRange(tx, startsWith(new byte[]{1, 2, 3, 7}), "i1");
            tx.set(new byte[]{1, 2, 3, 7}, new byte[]{93, 12, 51, 67, 125, -42}, "i1");
            List<KeyValue> rangeResultAfterNewValue = getRange(tx, startsWith(new byte[]{1, 2, 3, 7}), "i1");
            Assert.assertNotEquals(rangeResultAfterNewValue, rangeResultBeforeNewValue); // confirm that value has changed

            List<KeyValue> withAllChanges = copyDatabase(factory);
            Assert.assertNotEquals(withAllChanges, expected); // confirm that database state is not the initial state

            tx.cancel();

            try {
                getRange(tx, startsWith(new byte[]{1, 2, 3, 7}), "i1");
                Assert.fail("Accessing database from within a cancelled transaction is expected to be illegal.");
            } catch (IllegalStateException _) {
                // expected
            }
        }

        // check that cancel works
        List<KeyValue> actual = copyDatabase(factory);
        Assert.assertEquals(actual, expected); // confirm that database state is identical with the initial state
    }

    @Test
    public void thatACancelAfterACommitLeavesTheCommit() {
        MemoryTransactionFactory factory = new MemoryTransactionFactory(false);
        MemoryTransaction tx = factory.createTransaction(false);
        tx.set(new byte[]{1}, new byte[]{1}, "i1");
        tx.commit().join();
        tx.cancel().join();
        tx.close();

        try (MemoryTransaction reading = factory.createTransaction(true)) {
            Assert.assertEquals(getRange(reading, startsWith(new byte[]{1}), "i1").size(), 1);
        }
        factory.close();
    }

    /*
     * The lock of the store belongs to the thread that began the transaction. Ended on another thread, the
     * transaction fails and stays as it was, so its own thread can still end it and let the lock go.
     */
    @Test
    public void thatATransactionEndedOnAnotherThreadCanStillBeEndedOnItsOwn() throws Exception {
        MemoryTransactionFactory factory = new MemoryTransactionFactory(false);
        MemoryTransaction tx = factory.createTransaction(false);
        tx.set(new byte[]{1}, new byte[]{1}, "i1");

        Throwable[] failure = new Throwable[1];
        Thread other = Thread.ofPlatform().start(() -> {
            try {
                tx.commit().join();
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        other.join(10_000);
        Assert.assertFalse(other.isAlive());
        Assert.assertNotNull(failure[0], "a commit on another thread fails");

        tx.commit().join();
        Assert.assertFalse(((ReentrantReadWriteLock) factory.readWriteLock).isWriteLocked(), "the lock is let go");
        factory.close();
    }

    @Test
    public void thatAReadOnlyTransactionDoesNotWrite() {
        MemoryTransactionFactory factory = new MemoryTransactionFactory(false);
        try (MemoryTransaction tx = factory.createTransaction(true)) {
            Assert.assertThrows(IllegalStateException.class, () -> tx.set(new byte[]{1}, new byte[]{1}, "i1"));
            Assert.assertThrows(IllegalStateException.class, () -> tx.clear(new byte[]{1}, "i1"));
            Assert.assertThrows(IllegalStateException.class, () -> tx.clearRange(startsWith(new byte[]{1}), "i1"));
        }
        try (MemoryTransaction tx = factory.createTransaction(true)) {
            Assert.assertEquals(getRange(tx, startsWith(new byte[]{1}), "i1").size(), 0);
        }
        factory.close();
    }

    private List<KeyValue> getRange(MemoryTransaction tx, Range range, String index) {
        List<KeyValue> rangeResult = new ArrayList<>();
        AsyncIterable<KeyValue> iterable = tx.getRange(range, index, ROW_LIMIT_UNLIMITED);
        AsyncIterator<KeyValue> iterator = iterable.iterator();
        while (iterator.hasNext()) {
            KeyValue kv = iterator.next();
            rangeResult.add(kv);
        }
        return rangeResult;
    }

    private List<KeyValue> getRange(MemoryTransaction tx, KeySelector fromInclusive, KeySelector toExclusive, String index) {
        List<KeyValue> rangeResult = new ArrayList<>();
        AsyncIterable<KeyValue> asyncIterable = tx.getRange(fromInclusive, toExclusive, index, ROW_LIMIT_UNLIMITED);
        AsyncIterator<KeyValue> iterator = asyncIterable.iterator();
        while (iterator.hasNext()) {
            KeyValue kv = iterator.next();
            rangeResult.add(kv);
        }
        return rangeResult;
    }

    private List<KeyValue> copyDatabase(MemoryTransactionFactory factory) {
        List<KeyValue> copyOfState = new ArrayList<>();
        // capture entire database state
        try (MemoryTransaction tx = factory.createTransaction(true)) {
            AsyncIterable<KeyValue> asyncIterable = tx.getRange(firstGreaterOrEqual(new byte[]{1, 2, 3}), KeySelector.lastLessOrEqual(new byte[]{1, 2, 4}), "i1", ROW_LIMIT_UNLIMITED);
            AsyncIterator<KeyValue> iterator = asyncIterable.iterator();
            while (iterator.hasNext()) {
                KeyValue kv = iterator.next();
                copyOfState.add(kv);
            }
        }
        return copyOfState;
    }

    private KeySelector firstGreaterOrEqual(byte[] key) {
        return new KeySelector(key, true, 1);
    }

    /* Every key that starts with the prefix: up to the prefix with its last byte one higher. */
    private Range startsWith(byte[] prefix) {
        byte[] end = prefix.clone();
        end[end.length - 1]++;
        return new Range(prefix, end);
    }
}
