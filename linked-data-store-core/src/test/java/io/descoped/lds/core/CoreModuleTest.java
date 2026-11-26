package io.descoped.lds.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Core's main code and module hold no support of its tests: the search index in memory the tests use is in the
 * tests, and the module neither provides it, nor exports a package for the tests, nor requires what only tests
 * would need.
 */
class CoreModuleTest {

    /* The folder of the main classes of core, as the build made it. */
    private static Path mainClasses() throws URISyntaxException {
        return Path.of(UndertowApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    @Test
    void theMainCodeHoldsNoSearchIndexForTheTests() throws URISyntaxException {
        Path search = mainClasses().resolve("io/descoped/lds/core/search");
        assertThat(search.resolve("TestSearchIndex.class")).doesNotExist();
        assertThat(search.resolve("SimpleSearchIndex.class")).doesNotExist();
        assertThat(mainClasses().resolve("io/descoped/lds/test")).doesNotExist();
    }

    @Test
    void theModuleProvidesNothingForTheTests() throws IOException, URISyntaxException {
        ModuleDescriptor module;
        try (InputStream in = Files.newInputStream(mainClasses().resolve("module-info.class"))) {
            module = ModuleDescriptor.read(in);
        }
        assertThat(module.provides()).isEmpty();
        assertThat(module.exports()).extracting(ModuleDescriptor.Exports::source)
                .containsExactlyInAnyOrder("io.descoped.lds.core", "io.descoped.lds.core.lifecycle");
        assertThat(module.requires()).extracting(ModuleDescriptor.Requires::name).doesNotContain("java.xml");
    }
}
