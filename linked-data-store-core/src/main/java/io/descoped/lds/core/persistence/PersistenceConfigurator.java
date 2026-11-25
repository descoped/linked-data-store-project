package io.descoped.lds.core.persistence;

import io.descoped.lds.core.utils.StartAttempts;
import io.descoped.config.DynamicConfiguration;
import io.descoped.lds.api.persistence.PersistenceInitializer;
import io.descoped.lds.api.persistence.ProviderName;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.specification.Specification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

public class PersistenceConfigurator {

    private static final Logger LOG = LoggerFactory.getLogger(PersistenceConfigurator.class);

    private PersistenceConfigurator() {
    }

    public static RxJsonPersistence configurePersistence(DynamicConfiguration configuration, Specification specification) {
        final String providerId = configuration.find("persistence.provider").filter(id -> !id.isBlank())
                .orElseThrow(() -> new IllegalStateException("persistence.provider is not set: name the persistence"
                        + " provider, such as mem, postgres or neo4j"));
        LOG.info("Using persistence provider: {}", providerId);

        PersistenceInitializer initializer = findInitializer(providerId);
        Map<String, String> configurationByKey = configurationOf(initializer, configuration);

        // Read before the attempts: only the start of the provider is tried again, never a fault of the model.
        String namespace = configuration.find("namespace.default").orElse(null);
        Set<String> managedDomains = specification.getManagedDomains();

        int maxWaitSeconds = configuration.getInt("persistence.initialization.max-wait-seconds");
        RxJsonPersistence persistence = StartAttempts.until("Persistence provider", Duration.ofSeconds(maxWaitSeconds),
                Duration.ofSeconds(1), () -> initializer.initialize(namespace, configurationByKey, managedDomains));
        LOG.info("Persistence service-provider configured");
        return persistence;
    }

    /* The one initializer of the provider: annotated with its id, and giving that id itself. */
    private static PersistenceInitializer findInitializer(String providerId) {
        ServiceLoader<PersistenceInitializer> loader = ServiceLoader.load(PersistenceInitializer.class);

        List<ServiceLoader.Provider<PersistenceInitializer>> providers = loader.stream()
                .filter(p -> {
                    Class<? extends PersistenceInitializer> type = p.type();
                    ProviderName providerName = type.getDeclaredAnnotation(ProviderName.class);
                    return providerName != null && providerId.equals(providerName.value());
                })
                .toList();
        if (providers.isEmpty()) {
            throw new IllegalStateException("No persistence providers found for providerId: " + providerId);
        }
        if (providers.size() > 1) {
            throw new IllegalStateException("More than one persistence provider found for providerId: " + providerId);
        }

        PersistenceInitializer initializer = providers.get(0).get(); // instantiate persistence-initializer through provider

        if (!providerId.equals(initializer.persistenceProviderId())) {
            throw new IllegalStateException("Annotated providerId of persistence-module does not match with the provider-id returned from the initializer instance method");
        }
        return initializer;
    }

    /* The configuration the initializer asks for, every key of it. */
    private static Map<String, String> configurationOf(PersistenceInitializer initializer, DynamicConfiguration configuration) {
        Set<String> configurationKeys = initializer.configurationKeys();
        Set<String> missingConfigurationKeys = new LinkedHashSet<>();
        for (String key : configurationKeys) {
            if (configuration.find(key).isEmpty()) {
                missingConfigurationKeys.add(key);
            }
        }
        if (!missingConfigurationKeys.isEmpty()) {
            throw new IllegalArgumentException("Configuration missing for: " + missingConfigurationKeys);
        }

        Map<String, String> configurationByKey = new LinkedHashMap<>();
        for (String key : configurationKeys) {
            configuration.find(key).ifPresent(value -> configurationByKey.put(key, value));
        }
        return configurationByKey;
    }
}
