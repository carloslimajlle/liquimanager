package io.github.carloslimajlle.liquimanager.policy;

/**
 * Severity 0..4, as defined in docs/08-motor-de-politicas.md § 1.
 *
 * <p>The number is what travels over the API and lives in the database; these
 * constants exist so the code is not littered with bare literals.
 */
public final class Severity {

    public static final int INFO = 0;
    public static final int MINOR = 1;
    public static final int MAJOR = 2;
    public static final int CRITICAL = 3;
    public static final int BLOCKER = 4;

    /** Default blocking thresholds: CRITICAL in production, BLOCKER elsewhere. */
    public static final int BLOCK_THRESHOLD_PROD = CRITICAL;
    public static final int BLOCK_THRESHOLD_NONPROD = BLOCKER;

    private Severity() {
    }

    public static String label(int severity) {
        return switch (severity) {
            case INFO -> "INFO";
            case MINOR -> "MINOR";
            case MAJOR -> "MAJOR";
            case CRITICAL -> "CRITICAL";
            case BLOCKER -> "BLOCKER";
            default -> throw new IllegalArgumentException("severity out of range 0..4: " + severity);
        };
    }

    public static void validate(int severity) {
        if (severity < INFO || severity > BLOCKER) {
            throw new IllegalArgumentException("severity out of range 0..4: " + severity);
        }
    }
}
