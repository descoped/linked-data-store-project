package io.descoped.lds.core.persistence.postgres;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code postgres.driver.port} is read as every other number of the configuration is: spaces around it are left out,
 * and a value that is not a number is refused with the key, as a fault of the configuration.
 */
class PostgresPortTest {

    @Test
    void aPortIsReadWithTheSpacesAroundItLeftOut() {
        assertThat(PostgresDbInitializer.port("5432")).isEqualTo(5432);
        assertThat(PostgresDbInitializer.port(" 5433 ")).isEqualTo(5433);
    }

    @Test
    void aPortThatIsNotANumberIsRefusedWithTheKey() {
        assertThatThrownBy(() -> PostgresDbInitializer.port("abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("postgres.driver.port is not a number: abc");
    }
}
