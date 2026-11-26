package io.descoped.lds.core.persistence.memory;

import io.descoped.lds.api.persistence.PersistenceInitializer;
import io.descoped.lds.api.persistence.ProviderName;
import io.descoped.lds.api.persistence.TransactionFactory;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistenceBridge;

import java.util.Map;
import java.util.Set;

@ProviderName("mem")
public class MemoryInitializer implements PersistenceInitializer {

    private static final String FRAGMENT_CAPACITY = "persistence.fragment.capacity";

    MemoryRxPersistence persistence;
    RxJsonPersistence rxJsonPersistence;

    @Override
    public String persistenceProviderId() {
        return "mem";
    }

    @Override
    public Set<String> configurationKeys() {
        return Set.of(
                FRAGMENT_CAPACITY
        );
    }

    public MemoryRxPersistence getPersistence() {
        return persistence;
    }

    public RxJsonPersistence getRxJsonPersistence() {
        return rxJsonPersistence;
    }

    @Override
    public RxJsonPersistence initialize(String defaultNamespace, Map<String, String> configuration, Set<String> managedDomains) {
        int fragmentCapacityBytes = fragmentCapacity(configuration.getOrDefault(FRAGMENT_CAPACITY, "8192"));
        TransactionFactory transactionFactory = new MemoryTransactionFactory();
        persistence = new MemoryRxPersistence(transactionFactory, new MemoryDirectory());
        rxJsonPersistence = new RxJsonPersistenceBridge(persistence, fragmentCapacityBytes);
        return rxJsonPersistence;
    }

    /*
     * The largest number of bytes of one piece of a text: a whole number above 0. With 0 a text would be cut for ever
     * while the write holds the lock of the store, and a negative value would fail every write.
     */
    private static int fragmentCapacity(String value) {
        int capacity;
        try {
            capacity = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(FRAGMENT_CAPACITY + " is not a whole number: '" + value + "'", e);
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException(FRAGMENT_CAPACITY + " must be above 0, it is " + capacity);
        }
        return capacity;
    }
}
