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

    @Test
    void aCharacterAboveTheBasicPlaneIsOneCharacter() {
        assertThat(id("/ns/contact/a😀b")).isEqualTo("a😀b");
        assertThat(id("/ns/contact/%F0%9F%98%80")).isEqualTo("😀");
        assertThat(id("/ns/contact/" + "😀".repeat(512))).hasSize(1024);
        assertThatThrownBy(() -> context("/ns/contact/" + "😀".repeat(513))).isInstanceOf(ResourceException.class)
                .hasMessage("An id is at most 512 characters");
        assertThatThrownBy(() -> context("/ns/contact/" + "%F0%9F%98%80".repeat(513)))
                .isInstanceOf(ResourceException.class).hasMessage("An id is at most 512 characters");
    }

    @Test
    void bytesThatAreNotUtf8AreRefusedAndNeverDecodedToASlash() {
        // an overlong '/', a surrogate written as UTF-8, a byte that never starts a character, a lone continuation
        for (String escape : new String[]{"%C0%AF", "%E0%80%AF", "%ED%A0%80", "%FF", "%80", "a%C3"}) {
            assertThatThrownBy(() -> context("/ns/contact/" + escape)).as(escape)
                    .isInstanceOf(ResourceException.class).hasMessage("Not UTF-8 in the path: \"" + escape + "\"");
        }
    }

    @Test
    void everyControlCharacterIsRefused() {
        for (String escape : new String[]{"%00", "%1F", "%7F", "%C2%80", "%C2%85", "%C2%9F"}) {
            assertThatThrownBy(() -> context("/ns/contact/a" + escape)).as(escape)
                    .isInstanceOf(ResourceException.class).hasMessage("An id cannot hold a control character");
        }
        assertThat(id("/ns/contact/a%C2%A0b")).isEqualTo("a\u00a0b");
    }

    @Test
    void anEscapeIsReadInEitherCase() {
        assertThat(id("/ns/contact/%c3%a5se")).isEqualTo("åse");
        assertThatThrownBy(() -> context("/ns/contact/a%2fb")).isInstanceOf(ResourceException.class)
                .hasMessage("An id cannot hold a '/': \"a/b\"");
        assertThatThrownBy(() -> context("/ns/contact/a%4")).isInstanceOf(ResourceException.class)
                .hasMessage("Not a valid escape in the path: \"a%4\". Write a '%' as %25");
    }
}
