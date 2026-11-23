package io.descoped.lds.core.utils;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RequestTimeTest {

    private static final ZonedDateTime NOON = ZonedDateTime.parse("2026-11-22T12:00:00Z").withZoneSameInstant(RequestTime.UTC);

    @Test
    void aTimeWithAnOffsetOrAZoneIsGivenInUtc() {
        assertThat(RequestTime.parse("2026-11-22T12:00:00Z")).isEqualTo(NOON);
        assertThat(RequestTime.parse("2026-11-22T13:00:00+01:00")).isEqualTo(NOON);
        assertThat(RequestTime.parse("2026-11-22T13:00:00+01:00[Europe/Oslo]")).isEqualTo(NOON);
    }

    @Test
    void anEncodedTimeIsRead() {
        assertThat(RequestTime.parse("2026-11-22T13%3A00%3A00%2B01%3A00")).isEqualTo(NOON);
    }

    @Test
    void aTextThatIsNotATimeGivesNull() {
        assertThat(RequestTime.parse("yesterday")).isNull();
        assertThat(RequestTime.parse("2026-11-22T13:00:00 01:00")).isNull();
        assertThat(RequestTime.parse("2026-11-22")).isNull();
        assertThat(RequestTime.parse("100%")).isNull();
        assertThat(RequestTime.parse(null)).isNull();
    }
}
