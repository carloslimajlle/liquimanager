package io.github.carloslimajlle.liquimanager.client;

import java.util.List;
import java.util.UUID;

/**
 * A rollback a runner has been handed (the answer of {@code POST /runner/claim}). It says where the changelog is and
 * which database it is about; it never carries a credential, which the runner resolves where it runs.
 */
public record RunnerJob(
        UUID id,
        String kind,
        String tag,
        UUID parentOperationId,
        Changeset changeset,
        Connection connection,
        String environment,
        String project,
        String changelogPath,
        String gitCommit,
        String contexts,
        String labels,
        /** What going back to the changeset must undo, newest first, as id::author::filePath; empty for a tag. */
        List<String> plan) {

    public record Changeset(String id, String author, String filePath) {
    }

    public record Connection(String key, String jdbcUrl, String defaultSchema) {
    }

    public boolean isTag() {
        return "TAG".equals(kind);
    }
}
