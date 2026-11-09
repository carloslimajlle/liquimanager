package io.github.carloslimajlle.liquimanager.catalog;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns raw result rows into API JSON: snake_case columns become camelCase keys and
 * JDBC timestamps become {@link java.time.Instant}.
 *
 * <p>Catalogue tables are returned as-is, so adding a column is enough to expose it.
 * That is only safe because the SELECTs name their columns: nothing secret may ever
 * ride along through a {@code select *} (RNF-06).
 */
final class Rows {

    private Rows() {
    }

    static List<Map<String, Object>> camel(List<Map<String, Object>> rows) {
        return rows.stream().map(Rows::camel).toList();
    }

    static Map<String, Object> camel(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((column, value) -> out.put(camelCase(column), normalise(value)));
        return out;
    }

    static String camelCase(String column) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : column.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    private static Object normalise(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return value;
    }
}
