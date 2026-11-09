package io.github.carloslimajlle.liquimanager.drift;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.scheduling.annotation.Scheduled;

import io.github.carloslimajlle.liquimanager.ingest.OperationIngested;
import io.github.carloslimajlle.liquimanager.jobs.JobHistory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * The two ways drift gets going without anyone asking:
 * <ul>
 *   <li>a TICK every minute, which runs the connections whose own cron says they are due (default every 6 hours);
 *   <li>a REFERENCE refresh after every successful update, because "clean" means "right after the changelog did
 *       its work" (D-06).
 * </ul>
 */
@Component
class DriftJobs {

    private static final Logger LOG = LoggerFactory.getLogger(DriftJobs.class);
    static final String NAME = "drift-tick";

    /** Commands that legitimately change the schema. A scanner observation is the opposite: it IS drift. */
    private static final Set<String> SCHEMA_COMMANDS = Set.of("update", "rollback", "rollback-one-changeset", "rollback-to-changeset", "flow");

    private final JdbcClient jdbc;
    private final DriftService drift;
    private final JobHistory history;

    DriftJobs(JdbcClient jdbc, DriftService drift, JobHistory history) {
        this.jdbc = jdbc;
        this.drift = drift;
        this.history = history;
    }

    // ---------------------------------------------------------------- the tick

    @Scheduled(cron = "${lqm.jobs.drift-tick-cron:0 * * * * *}")
    @SchedulerLock(name = NAME, lockAtMostFor = "PT10M", lockAtLeastFor = "PT20S")
    void tick() {
        runDue(Instant.now());
    }

    /** Runs every connection that is due at {@code now}. Returns how many it ran. */
    int runDue(Instant now) {
        List<DueConnection> due = jdbc.sql("""
                select c.id, c.workspace_id, c.slug, c.drift_cron,
                       (select max(r.started_at) from drift_run r where r.db_connection_id = c.id) as last_run
                  from db_connection c where c.drift_enabled and c.verified and c.archived_at is null
                """).query((rs, n) -> new DueConnection(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getString("slug"), rs.getString("drift_cron"), rs.getTimestamp("last_run")))
                .list().stream().filter(c -> c.isDue(now)).toList();
        int ran = 0;
        for (DueConnection connection : due) {
            JobHistory.Outcome outcome = history.run("drift:" + connection.slug(), () -> {
                DriftService.RunResult result = drift.run(connection.workspace(), connection.id(), "scheduler", "scheduler");
                return new JobHistory.Outcome(result.status().equals("SUCCESS"),
                        result.status().equals("SUCCESS")
                                ? (result.hasDrift() ? "drift: +" + result.added() + " -" + result.removed() + " ~" + result.modified() : "clean")
                                : result.error());
            });
            ran++;
            if (!outcome.ok()) {
                LOG.warn("scheduled drift run on {} did not succeed: {}", connection.slug(), outcome.detail());
            }
        }
        return ran;
    }

    record DueConnection(UUID id, UUID workspace, String slug, String cron, Timestamp lastRun) {

        boolean isDue(Instant now) {
            if (lastRun == null) {
                return true;
            }
            try {
                LocalDateTime last = LocalDateTime.ofInstant(lastRun.toInstant(), ZoneOffset.UTC);
                LocalDateTime next = CronExpression.parse(cron == null || cron.isBlank() ? "0 0 */6 * * *" : cron).next(last);
                return next != null && !next.isAfter(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
            } catch (IllegalArgumentException e) {
                // A cron nobody can read is a configuration error, not a reason to run the connection every minute.
                LOG.warn("connection {} has an invalid drift cron '{}'", slug, cron);
                return false;
            }
        }
    }

    // ------------------------------------------------------------- the reference

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void afterUpdate(OperationIngested event) {
        if (!"database-change".equals(event.type()) || !"SUCCESS".equals(event.status())
                || !SCHEMA_COMMANDS.contains(event.command()) || "scanner".equals(event.source())
                || event.connectionId() == null) {
            return;
        }
        Boolean monitored = jdbc.sql("select drift_enabled from db_connection where id = :id")
                .param("id", event.connectionId()).query(Boolean.class).optional().orElse(false);
        if (!Boolean.TRUE.equals(monitored)) {
            return;
        }
        try {
            drift.takeReference(event.workspaceId(), event.connectionId());
        } catch (RuntimeException e) {
            // The next scheduled run would find no reference or a stale one; saying so is all that can be done here.
            LOG.warn("could not refresh the drift reference of {} after {}: {}", event.connection(), event.command(), e.getMessage());
        }
    }
}
