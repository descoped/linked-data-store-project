package io.descoped.lds.core.persistence.memory.reactivestreams;

import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.core.persistence.memory.AsyncIterable;
import io.descoped.lds.core.persistence.memory.AsyncIterablePublisher;
import io.descoped.lds.core.persistence.memory.AsyncIterator;
import io.descoped.lds.core.persistence.memory.KeySelector;
import io.descoped.lds.core.persistence.memory.KeyValue;
import io.descoped.lds.core.persistence.memory.MemoryInitializer;
import io.descoped.lds.core.persistence.memory.MemoryRxPersistence;
import io.descoped.lds.core.persistence.memory.MemoryTransaction;
import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;

public class AsyncIterablePublisherTest extends PublisherVerification<KeyValue> {

    public static final long DEFAULT_TIMEOUT_MILLIS = 100;
    public static final long DEFAULT_NO_SIGNALS_TIMEOUT_MILLIS = 100;
    public static final long PUBLISHER_REFERENCE_CLEANUP_TIMEOUT_MILLIS = 500;

    final MemoryInitializer initializer;
    final RxJsonPersistence rxJsonPersistence;
    final MemoryRxPersistence rxPersistence;

    MemoryTransaction tx;

    public AsyncIterablePublisherTest() {
        super(new TestEnvironment(DEFAULT_TIMEOUT_MILLIS, DEFAULT_NO_SIGNALS_TIMEOUT_MILLIS), PUBLISHER_REFERENCE_CLEANUP_TIMEOUT_MILLIS);
        initializer = new MemoryInitializer();
        initializer.initialize("ns",
                Map.of("persistence.fragment.capacity", "8192"),
                Set.of("A")
        );
        rxJsonPersistence = initializer.getRxJsonPersistence();
        rxPersistence = initializer.getPersistence();
    }

    @BeforeMethod
    public void beginTransaction() {
        tx = (MemoryTransaction) rxPersistence.createTransaction(false);
    }

    @AfterMethod
    public void endTransaction() {
        tx.cancel();
    }

    @Override
    public Publisher<KeyValue> createPublisher(long elements) {
        rollbackExistingAndBeginNewTransaction();
        for (int i = 0; i < Math.min(elements, 1000); i++) {
            tx.set(ByteBuffer.allocate(4).putInt(i).array(), new byte[0], "AnyIndex");
        }
        return new AsyncIterablePublisher<>(tx.getRange(KeySelector.firstGreaterOrEqual(new byte[]{(byte) 0}), KeySelector.firstGreaterOrEqual(new byte[]{(byte) -1}), "AnyIndex", (int) elements));
    }

    @Override
    public Publisher<KeyValue> createFailedPublisher() {
        return new AsyncIterablePublisher<>(new AsyncIterable<>() {
            @Override
            public AsyncIterator<KeyValue> iterator() {
                return null;
            }
        });
    }

    private void rollbackExistingAndBeginNewTransaction() {
        tx.cancel();
        tx = (MemoryTransaction) rxPersistence.createTransaction(false);
    }

    @Override
    public long boundedDepthOfOnNextAndRequestRecursion() {
        return 1;
    }
}
