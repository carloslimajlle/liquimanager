package io.github.carloslimajlle.liquimanager.scanner;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.carloslimajlle.liquimanager.jobs.JobHistory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Ticks every minute and scans the connections whose own interval has elapsed (default 15 minutes).
 *
 * <p>Only VERIFIED connections: one discovered through ingestion has no credentials and no one has vouched for
 * it, and scanning it would only fail every pass.
 */
@Component
class ScannerJob {

    static final String NAME = "scanner-tick";

    private final JdbcClient jdbc;
    private final ScannerService scanner;
    private final JobHistory history;

    private final io.github.carloslimajlle.liquimanager.notifications.NotificationService notifications;

    ScannerJob(JdbcClient jdbc, ScannerService scanner, JobHistory history,
               io.github.carloslimajlle.liquimanager.notifications.NotificationService notifications) {
        this.jdbc = jdbc;
        this.scanner = scanner;
        this.history = history;
        this.notifications = notifications;
    }

    @Scheduled(cron = "${lqm.jobs.scanner-tick-cron:30 * * * * *}")
    @SchedulerLock(name = NAME, lockAtMostFor = "PT10M", lockAtLeastFor = "PT20S")
    void tick() {
        runDue(Instant.now());
    }

    /** Scans every connection that is due at {@code now}; returns how many were scanned. */
    int runDue(Instant now) {
        List<Due> due = jdbc.sql("""
                select c.id, c.workspace_id, c.slug, coalesce(c.scanner_interval_min, 15) as interval_min, s.last_run_at
                  from db_connection c left join scanner_state s on s.db_connection_id = c.id
                 where c.scanner_enabled and c.verified and c.archived_at is null
                """).query((rs, n) -> new Due(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getString("slug"), rs.getInt("interval_min"), rs.getTimestamp("last_run_at")))
                .list().stream().filter(d -> d.isDue(now)).toList();
        int scanned = 0;
        for (Due connection : due) {
            history.run("scanner:" + connection.slug(), () -> {
                ScannerService.Result result = scanner.scan(connection.workspace(), connection.id());
                if (!result.ok()) {
                    return new JobHistory.Outcome(false, result.error());
                }
                String detail = result.deployments() + " deployments seen, " + result.outOfProcess() + " out of process"
                        + (result.firstPass() ? " (first pass: history imported)" : "")
                        + (result.stuckLockSince() == null ? "" : "; LOCK HELD SINCE " + result.stuckLockSince());
                if (result.stuckLockSince() != null) {
                    notifications.notify(connection.workspace(), "SYSTEM_ALERT", null, null,
                            "Lock preso em " + connection.slug(),
                            "O DATABASECHANGELOGLOCK de " + connection.slug() + " esta preso desde " + result.stuckLockSince()
                                    + ". Um deploy morreu com o lock e bloqueia os proximos.",
                            "lock:" + connection.id() + ":" + result.stuckLockSince());
                }
                // A lock held for more than 15 minutes is a problem worth the job being marked as one.
                return new JobHistory.Outcome(result.stuckLockSince() == null, detail);
            });
            scanned++;
        }
        return scanned;
    }

    record Due(UUID id, UUID workspace, String slug, int intervalMinutes, Timestamp lastRun) {

        boolean isDue(Instant now) {
            return lastRun == null || Duration.between(lastRun.toInstant(), now).toMinutes() >= intervalMinutes;
        }
    }
}
