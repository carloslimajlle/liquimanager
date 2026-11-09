package io.github.carloslimajlle.liquimanager.operations;

import java.time.Instant;

/**
 * Query-string filters shared by the operation list and its KPIs, so the numbers at
 * the top of screen 05 always describe exactly the rows in the table (the spec asks
 * for it: "KPIs respect the filters").
 *
 * <p>Bound straight from the query string. Every field is optional.
 */
public record OperationFilter(
        String status,
        String type,
        String command,
        String env,
        String connection,
        String project,
        String changelog,
        String executedBy,
        String source,
        Instant from,
        Instant to,
        String q) {
}
