package io.descoped.lds.core.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the body of a request is read as, and the status a body LDS does not read is answered with.
 */
class BodyParserTest {

    private final BodyParser parser = new BodyParser();

    private static void refused(Runnable read, int status) {
        assertThatThrownBy(read::run).isInstanceOfSatisfying(BodyException.class, e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void aJsonBodyIsReadAsTheDocument() {
        assertThat(parser.deserializeBody("application/json", "{\"name\":\"Ola\",\"age\":7,\"list\":[1.5,null,true]}"))
                .hasToString("{\"name\":\"Ola\",\"age\":7,\"list\":[1.5,null,true]}");
        assertThat(parser.deserializeBody("application/json; charset=utf-8", "{\"a\":1}\n")).hasToString("{\"a\":1}");
    }

    @Test
    void aBodyThatIsNotJsonIsRefusedWith400() {
        refused(() -> parser.deserializeBody("application/json", "{\"a\":"), 400);
        refused(() -> parser.deserializeBody("application/json", "{'a':1}"), 400);
        assertThatThrownBy(() -> parser.deserializeBody("application/json", "{\"name\":\"A\""))
                .hasMessage("The body cannot be read at line 1, column 12: Unexpected end-of-input: expected close marker "
                        + "for Object. Send the body as JSON, with Content-Type: application/json");
        assertThatThrownBy(() -> parser.deserializeBody("application/json", "{\"name\":\"A\"} x"))
                .hasMessage("The body cannot be read at line 1, column 14: Unrecognized token 'x': was expecting (JSON "
                        + "String, Number, Array, Object or token 'null', 'true' or 'false'). Send the body as JSON, with "
                        + "Content-Type: application/json");
    }

    @Test
    void anEmptyBodyIsRefusedWith400() {
        assertThatThrownBy(() -> parser.deserializeBody("application/json", "")).isInstanceOf(BodyException.class)
                .hasMessage("Payload was empty!");
        refused(() -> parser.deserializeBody("application/json", " \n"), 400);
        refused(() -> parser.deserializeBody("application/json", null), 400);
    }

    /**
     * Jackson 2 read the first document and ignored what came after it.
     */
    @Test
    void aBodyWithSomethingAfterTheDocumentIsRefused() {
        refused(() -> parser.deserializeBody("application/json", "{\"a\":1} and more"), 400);
        refused(() -> parser.deserializeBody("application/json", "{\"a\":1}{\"b\":2}"), 400);
    }

    @Test
    void anotherContentTypeIsRefusedWith415() {
        refused(() -> parser.deserializeBody("text/plain", "{}"), 415);
        refused(() -> parser.deserializeBody("application/x-www-form-urlencoded", "{}"), 415);
        refused(() -> parser.deserializeBody("not a type", "{}"), 415);
        assertThatThrownBy(() -> parser.deserializeBody("text/plain", "{}"))
                .hasMessage("Unsupported Content-Type: text/plain. Send the body as JSON, with Content-Type: application/json");
    }

    /**
     * As LDS is today: the body of a request reaches the parser as text, and MessagePack is not text, so a
     * MessagePack body has never been read. It is refused as a body that cannot be read.
     */
    @Test
    void aMessagePackBodyIsRefusedBecauseItIsHandedOverAsText() {
        String body = new String(new byte[]{(byte) 0x81, (byte) 0xa1, 'a', 1}, StandardCharsets.ISO_8859_1);

        refused(() -> parser.deserializeBody("application/msgpack", body), 400);
        refused(() -> parser.deserializeBody("application/x-msgpack", body), 400);
    }

    @Test
    void aBodyWithoutAContentTypeIsRefusedWith415() {
        assertThatThrownBy(() -> parser.deserializeBody(null, "{}")).isInstanceOf(BodyException.class)
                .hasMessage("Unsupported Content-Type: null. " + BodyParser.SEND_JSON)
                .satisfies(e -> assertThat(((BodyException) e).status()).isEqualTo(415));
    }

    @Test
    void theContentTypeIsReadAsHttpWritesIt() {
        // a media type is case-insensitive, and its parameters may be written with or without spaces (RFC 9110)
        assertThat(parser.deserializeBody("Application/JSON", "{\"a\":1}")).hasToString("{\"a\":1}");
        assertThat(parser.deserializeBody("application/json;charset=UTF-8", "{\"a\":1}")).hasToString("{\"a\":1}");
        assertThat(parser.deserializeBody(" application/json ; charset=utf-8", "{\"a\":1}")).hasToString("{\"a\":1}");
        refused(() -> parser.deserializeBody("application/jsonx", "{}"), 415);
        refused(() -> parser.deserializeBody("application/", "{}"), 415);
        refused(() -> parser.deserializeBody("", "{}"), 415);
    }

    @Test
    void aBodyThatIsJsonButNotAnObjectIsRead() {
        // whether a document may be such a value is for the validation of the document to say, not the parser
        assertThat(parser.deserializeBody("application/json", "[1,2]")).hasToString("[1,2]");
        assertThat(parser.deserializeBody("application/json", "\"text\"")).hasToString("\"text\"");
    }

    @Test
    void aBodyWithLettersOutsideAsciiIsReadWhole() {
        assertThat(parser.deserializeBody("application/json", "{\"name\":\"øl 日本 😀\"}").get("name").asString())
                .isEqualTo("øl 日本 😀");
    }
}
