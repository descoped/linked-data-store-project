package io.descoped.lds.core.domain;

import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the body of a request is read as.
 */
class BodyParserTest {

    private final BodyParser parser = new BodyParser();

    @Test
    void aJsonBodyIsReadAsTheDocument() {
        assertThat(parser.deserializeBody("application/json", "{\"name\":\"Ola\",\"age\":7,\"list\":[1.5,null,true]}"))
                .hasToString("{\"name\":\"Ola\",\"age\":7,\"list\":[1.5,null,true]}");
        assertThat(parser.deserializeBody("application/json; charset=utf-8", "{\"a\":1}\n")).hasToString("{\"a\":1}");
    }

    @Test
    void aBodyThatIsNotJsonIsRefused() {
        assertThatThrownBy(() -> parser.deserializeBody("application/json", "{\"a\":")).isInstanceOf(RuntimeException.class).hasCauseInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> parser.deserializeBody("application/json", "{'a':1}")).isInstanceOf(RuntimeException.class).hasCauseInstanceOf(JacksonException.class);
    }

    /**
     * Jackson 2 read the first document and ignored what came after it.
     */
    @Test
    void aBodyWithSomethingAfterTheDocumentIsRefused() {
        assertThatThrownBy(() -> parser.deserializeBody("application/json", "{\"a\":1} and more")).isInstanceOf(RuntimeException.class).hasCauseInstanceOf(JacksonException.class);
        assertThatThrownBy(() -> parser.deserializeBody("application/json", "{\"a\":1}{\"b\":2}")).isInstanceOf(RuntimeException.class).hasCauseInstanceOf(JacksonException.class);
    }

    @Test
    void anotherContentTypeIsRefused() {
        assertThatThrownBy(() -> parser.deserializeBody("text/plain", "{}")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("text/plain");
    }

    /**
     * As LDS is today: the body of a request reaches the parser as text, and MessagePack is not text, so a
     * MessagePack body has never been read. It is refused, as it was with the earlier library.
     */
    @Test
    void aMessagePackBodyIsRefusedBecauseItIsHandedOverAsText() {
        String body = new String(new byte[]{(byte) 0x81, (byte) 0xa1, 'a', 1}, java.nio.charset.StandardCharsets.ISO_8859_1);

        assertThatThrownBy(() -> parser.deserializeBody("application/msgpack", body)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> parser.deserializeBody("application/x-msgpack", body)).isInstanceOf(RuntimeException.class);
    }
}
