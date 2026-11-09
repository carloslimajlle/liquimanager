package io.github.carloslimajlle.liquimanager.dashboard;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.carloslimajlle.liquimanager.jobs.JobHistory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Keeps {@code metric_daily} current: the last two days every ten minutes (cheap, so the bands are never more than
 * ten minutes behind), and the whole 90-day window every night, which also repairs anything a late-arriving
 * operation or an edit upset.
 */
@Component
class MetricsJobs {

    static final int WINDOW_DAYS = 90;

    private final JdbcClient jdbc;
    private final MetricsService metrics;
    private final JobHistory history;

    MetricsJobs(JdbcClient jdbc, MetricsService metrics, JobHistory history) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.history = history;
    }

    @Scheduled(cron = "${lqm.jobs.metrics-recent-cron:0 */10 * * * *}")
    @SchedulerLock(name = "metrics-recent", lockAtMostFor = "PT9M", lockAtLeastFor = "PT30S")
    void recent() {
        run("metrics-recent", 2);
    }

    @Scheduled(cron = "${lqm.jobs.metrics-full-cron:0 30 2 * * *}")
    @SchedulerLock(name = "metrics-full", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    void full() {
        run("metrics-full", WINDOW_DAYS);
    }

    /** Rebuilds the last {@code days} days of every workspace; returns how many workspaces it covered. */
    int run(String job, int days) {
        List<UUID> workspaces = jdbc.sql("select id from workspace").query(UUID.class).list();
        history.run(job, () -> {
            for (UUID workspace : workspaces) {
                metrics.rebuildRecent(workspace, days);
            }
            return new JobHistory.Outcome(true, workspaces.size() + " workspace(s), " + days + " day(s)");
        });
        return workspaces.size();
    }
}
