package io.github.carloslimajlle.liquimanager.policy;

import java.util.List;
import java.util.Map;

/**
 * A single policy check.
 *
 * <p>Implementations must be STATELESS: the same instance is shared across threads
 * in the API, and the copies running in the CLI and in the starter have to produce
 * the same answer (docs/04 § 3). No mutable fields, no clock, no network access
 * inside a check.
 */
public interface PolicyCheck {

    /** Stable code, matching the catalogue in docs/08. */
    String code();

    CheckScope scope();

    /** Severity used when the package does not override it. */
    int defaultSeverity();

    /**
     * Evaluates and returns the findings. An empty list means the check passed.
     *
     * @param params effective parameters for this check, package overrides already applied
     */
    List<Finding> evaluate(CheckContext ctx, Map<String, Object> params);

    /** Reads a text parameter, falling back to the default when absent or blank. */
    default String param(Map<String, Object> params, String name, String fallback) {
        Object value = params == null ? null : params.get(name);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    /** Reads an integer parameter, falling back to the default when absent or unreadable. */
    default int intParam(Map<String, Object> params, String name, int fallback) {
        Object value = params == null ? null : params.get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
