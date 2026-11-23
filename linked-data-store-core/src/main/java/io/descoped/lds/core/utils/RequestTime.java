package io.descoped.lds.core.utils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The time a request names, in a REST {@code timestamp} or a GraphQL {@code snapshot}: one way to read it for both.
 */
public final class RequestTime {

    /**
     * The zone every time LDS reads from a request is given in.
     */
    public static final ZoneId UTC = ZoneId.of("Etc/UTC");

    /**
     * A time LDS reads, to show in an answer that refuses another.
     */
    public static final String EXAMPLE = "2018-12-06T11:05:31.000+01:00";

    private RequestTime() {
    }

    /**
     * The time in UTC that a text gives as ISO-8601 with an offset or a zone, as it is or url-decoded, or null when it
     * gives none. A {@code +} that is not encoded in the query of an address arrives as a space, and is not a time.
     *
     * @param text the text, or null
     * @return the time in {@link #UTC}, or null
     */
    public static ZonedDateTime parse(String text) {
        if (text == null) {
            return null;
        }
        for (String candidate : List.of(text, urlDecoded(text))) {
            try {
                return ZonedDateTime.parse(candidate).withZoneSameInstant(UTC);
            } catch (DateTimeParseException _) {
                // the next way of reading it
            }
        }
        return null;
    }

    private static String urlDecoded(String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException _) {
            return text;
        }
    }
}
