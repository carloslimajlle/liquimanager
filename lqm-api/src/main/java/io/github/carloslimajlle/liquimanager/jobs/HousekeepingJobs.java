package io.github.carloslimajlle.liquimanager.jobs;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Clears what the product itself produces and nobody reads after a while: the history of its own jobs and the notification
 * deliveries already sent. How long each is kept is a workspace setting (docs/09, {@code workspace:admin}).
 *
 * <p>Operations and the audit trail are NOT touched here: they are kept for the legal period (D-08) and, for the operations,
 * dropped by partition, not by row.
 */
@Component
class HousekeepingJobs {

    static final String NAME = "retention";

    private final JdbcClient jdbc;
    private final JobHistory history;

    HousekeepingJobs(JdbcClient jdbc, JobHistory history) {
        this.jdbc = jdbc;
        this.history = history;
    }

    @Scheduled(cron = "${lqm.jobs.retention-cron:0 30 3 * * *}")
    @SchedulerLock(name = NAME, lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    void tick() {
        runNow();
    }

    /** The body, callable without waiting for the clock. */
    JobHistory.Outcome runNow() {
        return history.run(NAME, () -> {
            // job_run belongs to the installation, not to a workspace: the longest period any workspace asked for wins, so
            // nobody loses history another one still wants.
            int jobRuns = jdbc.sql("""
                    delete from job_run
                     where started_at < now() - make_interval(days => (select coalesce(max(job_run_retention_days), 90) from workspace))
                    """).update();
            int deliveries = jdbc.sql("""
                    delete from notification_delivery d
                     using notification_rule r, workspace w
                     where d.rule_id = r.id and w.id = r.workspace_id
                       and d.status <> 'PENDING'
                       and d.created_at < now() - make_interval(days => w.notification_retention_days)
                    """).update();
            return new JobHistory.Outcome(true, jobRuns + " job run(s) and " + deliveries + " notification(s) removed");
        });
    }
}
