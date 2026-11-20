package io.descoped.lds.core.txlog;

import io.descoped.lds.api.persistence.json.JsonTools;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An entry of the transaction log is MessagePack. The file {@code message.msgpack.hex} holds the bytes that the
 * MessagePack library LDS used with Jackson 2 (org.msgpack 0.9.12) wrote for {@code message.json}, a message
 * with every kind of value: the format of the log stays what it was.
 */
class TxLogToolsTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = TxLogToolsTest.class.getResourceAsStream("/txlog/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] messagePack() throws IOException {
        return HexFormat.of().parseHex(resource("message.msgpack.hex").replace("\n", ""));
    }

    @Test
    void aMessageIsWrittenAsTheSameBytesAsBefore() throws IOException {
        JsonNode message = JsonTools.toJsonNode(resource("message.json"));

        assertThat(TxLogTools.toBytes(message)).isEqualTo(messagePack());
    }

    @Test
    void theBytesWrittenBeforeAreReadAsTheMessage() throws IOException {
        JsonNode message = JsonTools.toJsonNode(resource("message.json"));

        assertThat(JsonTools.toJson(TxLogTools.toJson(messagePack()))).isEqualTo(JsonTools.toJson(message));
    }

    @Test
    void aMessageIsTheSameAfterItIsWrittenAndRead() throws IOException {
        JsonNode message = JsonTools.toJsonNode(resource("message.json"));

        assertThat(JsonTools.toJson(TxLogTools.toJson(TxLogTools.toBytes(message)))).isEqualTo(JsonTools.toJson(message));
    }
}
