package cn.voltmate.core;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** MySQL DATETIME columns contain UTC values; JDBC map queries may expose either type. */
public final class SqlTime {
    private SqlTime() {}

    public static Instant instant(Object value) {
        if (value instanceof LocalDateTime dateTime) return dateTime.toInstant(ZoneOffset.UTC);
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof Instant instant) return instant;
        throw new IllegalArgumentException("Unsupported SQL time value: " + (value == null ? "null" : value.getClass().getName()));
    }
}
