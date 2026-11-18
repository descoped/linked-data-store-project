package io.descoped.lds.core.persistence;

import io.descoped.config.DynamicConfiguration;
import io.descoped.config.StoreBasedDynamicConfiguration;
import io.descoped.lds.api.persistence.PersistenceInitializer;
import io.descoped.lds.api.persistence.ProviderName;
import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.core.specification.JsonSchemaBasedSpecification;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

public class PersistenceConfiguratorTest {

    @Test
    public void thatServiceLoadingWorks() {
        DynamicConfiguration configuration = new StoreBasedDynamicConfiguration.Builder()
                .values("persistence.provider", "mem",
                        "persistence.initialization.max-wait-seconds", "0")
                .build();
        Specification specification = JsonSchemaBasedSpecification.create("spec/schemas/contact.json", "spec/schemas/provisionagreement.json");
        RxJsonPersistence persistence = PersistenceConfigurator.configurePersistence(configuration, specification);
        Assert.assertNotNull(persistence);
    }

    @Test
    public void thatAnUnknownProviderIsRejected() {
        DynamicConfiguration configuration = new StoreBasedDynamicConfiguration.Builder()
                .values("persistence.provider", "no-such-provider")
                .build();
        Specification specification = JsonSchemaBasedSpecification.create("spec/schemas/contact.json", "spec/schemas/provisionagreement.json");
        try {
            PersistenceConfigurator.configurePersistence(configuration, specification);
            Assert.fail("A provider that is not on the class path must not be found");
        } catch (RuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("no-such-provider"));
        }
    }
}
