package io.github.carloslimajlle.liquimanager.policy;

/**
 * A single check result against a changeset.
 *
 * @param checkCode    the check code, e.g. RollbackRequired
 * @param severity     0..4
 * @param changesetKey Liquibase identity "id::author::filePath", or null for DATABASE scope
 * @param objectName   the object involved (table, column, index), when known
 * @param line         line in the file, when known
 * @param message      display-ready message; written in pt-BR because it reaches the user
 */
public record Finding(
        String checkCode,
        int severity,
        String changesetKey,
        String objectName,
        Integer line,
        String message) {

    public Finding {
        if (checkCode == null || checkCode.isBlank()) {
            throw new IllegalArgumentException("checkCode is required");
        }
        Severity.validate(severity);
    }

    /** Shorthand for the common case: a changeset finding with no object or line. */
    public static Finding of(String checkCode, int severity, String changesetKey, String message) {
        return new Finding(checkCode, severity, changesetKey, null, null, message);
    }
}
