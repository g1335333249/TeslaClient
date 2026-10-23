package cn.voltmate.core;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlTimeTest {
    @Test void convertsMySqlDatetimeMappedAsLocalDateTimeInUtc() {
        assertThat(SqlTime.instant(LocalDateTime.of(2026, 10, 23, 4, 5, 6)))
            .isEqualTo(Instant.parse("2026-10-23T04:05:06Z"));
    }

    @Test void acceptsTimestampFromOtherJdbcDrivers() {
        Instant at = Instant.parse("2026-10-23T04:05:06Z");
        assertThat(SqlTime.instant(Timestamp.from(at))).isEqualTo(at);
    }
}
