package io.github.carloslimajlle.liquimanager.ingest;

import java.time.Instant;
import java.util.UUID;

/**
 * Published once an operation has been ingested and committed. It feeds the SSE
 * stream behind the dashboard's live table (RNF-19) and, later, notifications and
 * incremental metrics. Shaped like {@code OperationSummary} in the contract.
 */
public record OperationIngested(
        UUID id,
        String command,
        String status,
        String type,
        String source,
        String executedBy,
        String project,
        String connection,
        String environment,
        String release,
        Instant startedAt,
        Long durationMs,
        boolean isBreakFix,
        UUID workspaceId,
        UUID connectionId) {
}
