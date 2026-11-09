package io.github.carloslimajlle.liquimanager.ingest;

import java.util.Map;
import java.util.Set;

/** The vocabulary of an operation: commands, statuses, sources and changeset outcomes. */
final class CommandCatalog {

    /** Command -> operation type (docs/05 § 2.3). Only database-change commands move changeset state. */
    private static final Map<String, String> TYPE_BY_COMMAND = Map.ofEntries(
            Map.entry("update", "database-change"),
            Map.entry("update-count", "database-change"),
            Map.entry("update-to-tag", "database-change"),
            Map.entry("rollback", "database-change"),
            Map.entry("rollback-count", "database-change"),
            Map.entry("rollback-to-date", "database-change"),
            Map.entry("rollback-one-changeset", "database-change"),
            Map.entry("rollback-to-changeset", "database-change"),
            Map.entry("tag", "database-change"),
            Map.entry("flow", "database-change"),
            Map.entry("observed", "database-change"),
            Map.entry("update-sql", "read-only"),
            Map.entry("status", "read-only"),
            Map.entry("history", "read-only"),
            Map.entry("snapshot", "read-only"),
            Map.entry("diff", "read-only"),
            Map.entry("diff-changelog", "read-only"),
            Map.entry("validate", "read-only"),
            Map.entry("checks run", "policy-checks"));

    static final Set<String> STATUSES = Set.of("SUCCESS", "FAILURE", "BLOCKED", "RUNNING", "CANCELED");
    static final Set<String> SOURCES = Set.of("pipeline", "startup", "cli-local", "scheduler", "ui", "scanner");
    static final Set<String> OUTCOMES = Set.of(
            "EXECUTED", "PREVIOUSLY_RUN", "FILTERED", "FAILED", "NOT_EXECUTED", "ROLLED_BACK");

    private CommandCatalog() {
    }

    static boolean knows(String command) {
        return TYPE_BY_COMMAND.containsKey(command);
    }

    static String typeOf(String command) {
        return TYPE_BY_COMMAND.get(command);
    }
}
