package com.huoling.wms.site.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 投影行到领域值的取值工具。
 *
 * <p>列一律显式起别名，Map 的键就是别名；JDBC 驱动把 {@code timestamptz} 交回
 * {@link Timestamp} 或 {@link OffsetDateTime}，这里统一收敛成 {@link Instant}（UTC）。
 */
final class SqlValues {

    private SqlValues() {
    }

    static String text(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    static Long id(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value == null ? null : ((Number) value).longValue();
    }

    static long longValue(Map<String, Object> row, String key, long fallback) {
        Long value = id(row, key);
        return value == null ? fallback : value;
    }

    static int intValue(Map<String, Object> row, String key, int fallback) {
        Long value = id(row, key);
        return value == null ? fallback : value.intValue();
    }

    static Instant instant(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return null;
    }

    static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }
}
