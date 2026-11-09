package io.github.carloslimajlle.liquimanager.catalog;

import java.util.regex.Pattern;

import io.github.carloslimajlle.liquimanager.common.LqmException;

/** Field rules shared by the catalogue endpoints (docs/02, RF-CAD). */
final class Validation {

    /** RF-CAD-02 CA1: {@code ^[a-z0-9-]{2,64}$}. */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9-]{2,64}$");

    private Validation() {
    }

    static String slug(String value, String field) {
        if (value == null || !SLUG.matcher(value).matches()) {
            throw LqmException.invalid(field + " must match ^[a-z0-9-]{2,64}$");
        }
        return value;
    }

    static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw LqmException.invalid(field + " is required");
        }
        return value.trim();
    }

    static void oneOf(String value, String field, String... allowed) {
        for (String candidate : allowed) {
            if (candidate.equals(value)) {
                return;
            }
        }
        throw LqmException.invalid(field + " must be one of " + String.join(", ", allowed));
    }
}
