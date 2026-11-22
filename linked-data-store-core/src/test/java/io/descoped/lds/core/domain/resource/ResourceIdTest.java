package io.descoped.lds.core.domain.resource;

import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.core.specification.JsonSchemaBasedSpecification;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The id in a path: decoded once, and refused when it is longer than 512 characters, holds a '/' or a control
 * character.
 */
class ResourceIdTest {

    private static final Specification SPECIFICATION = JsonSchemaBasedSpecification.create(
            "spec/schemas/contact.json", "spec/schemas/provisionagreement.json");

    private static ResourceContext context(String path) {
        return ResourceContext.createResourceContext(SPECIFICATION, path, ZonedDateTime.now(ZoneId.of("Etc/UTC")));
    }

    private static String id(String path) {
        return context(path).getFirstElement().id();
    }

    @Test
    void anIdIsDecodedOnce() {
        assertThat(id("/ns/contact/a+b")).isEqualTo("a+b");
        assertThat(id("/ns/contact/a%2Bb")).isEqualTo("a+b");
        assertThat(id("/ns/contact/100%25")).isEqualTo("100%");
        assertThat(id("/ns/contact/100%2525")).isEqualTo("100%25");
        assertThat(id("/ns/contact/a%20b")).isEqualTo("a b");
        assertThat(id("/ns/contact/%C3%A5se")).isEqualTo("åse");
        assertThat(id("/ns/contact/~x")).isEqualTo("~x");
    }

    @Test
    void aCharacterOutsideAsciiStandsForItself() {
        assertThat(id("/ns/contact/øl日本")).isEqualTo("øl日本");
    }

    @Test
    void anEscapeThatIsNotOneIsRefused() {
        assertThatThrownBy(() -> context("/ns/contact/100%")).isInstanceOf(ResourceException.class)
                .hasMessage("Not a valid escape in the path: \"100%\". Write a '%' as %25");
        assertThatThrownBy(() -> context("/ns/contact/%zz")).isInstanceOf(ResourceException.class);
        assertThatThrownBy(() -> context("/ns/contact/%C3")).isInstanceOf(ResourceException.class)
                .hasMessage("Not UTF-8 in the path: \"%C3\"");
    }

    @Test
    void anIdWithASlashIsRefused() {
        assertThatThrownBy(() -> context("/ns/contact/a%2Fb")).isInstanceOf(ResourceException.class)
                .hasMessage("An id cannot hold a '/': \"a/b\"");
    }

    @Test
    void anIdWithAControlCharacterIsRefused() {
        assertThatThrownBy(() -> context("/ns/contact/a%0Ab")).isInstanceOf(ResourceException.class)
                .hasMessage("An id cannot hold a control character");
        assertThatThrownBy(() -> context("/ns/contact/a%7Fb")).isInstanceOf(ResourceException.class);
    }

    @Test
    void anIdIsAtMost512Characters() {
        assertThat(id("/ns/contact/" + "日".repeat(512))).hasSize(512);
        assertThatThrownBy(() -> context("/ns/contact/" + "a".repeat(513))).isInstanceOf(ResourceException.class)
                .hasMessage("An id is at most 512 characters");
    }

    @Test
    void theIdOfALinkFollowsTheSameRules() {
        assertThat(context("/ns/provisionagreement/p1/contacts/contact/a+b").getFirstElement().next().id())
                .isEqualTo("/contact/a+b");
        assertThatThrownBy(() -> context("/ns/provisionagreement/p1/contacts/contact/a%2Fb"))
                .isInstanceOf(ResourceException.class);
    }
}
