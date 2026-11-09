package io.github.carloslimajlle.liquimanager.rollback;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.carloslimajlle.liquimanager.jobs.JobHistory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * A runner that claimed a rollback and then died would leave it RUNNING for ever, and nothing else could take it.
 * After 30 minutes the request is marked failed, with the reason; whoever still wants the rollback asks again.
 */
@Component
class RollbackJobs {

    static final int RUNNER_TIMEOUT_MINUTES = 30;

    private final JdbcClient jdbc;
    private final JobHistory history;

    RollbackJobs(JdbcClient jdbc, JobHistory history) {
        this.jdbc = jdbc;
        this.history = history;
    }

    @Scheduled(cron = "${lqm.jobs.rollback-reaper-cron:0 */5 * * * *}")
    @SchedulerLock(name = "rollback-reaper", lockAtMostFor = "PT4M", lockAtLeastFor = "PT20S")
    void tick() {
        reap();
    }

    /** Fails what has been running too long; returns how many it failed. */
    int reap() {
        int[] reaped = {0};
        history.run("rollback-reaper", () -> {
            reaped[0] = jdbc.sql("""
                    update rollback_request set status = 'FAILED', finished_at = now(),
                           error = 'the runner did not finish within %d minutes'
                     where status = 'RUNNING' and claimed_at < now() - make_interval(mins => %d)
                    """.formatted(RUNNER_TIMEOUT_MINUTES, RUNNER_TIMEOUT_MINUTES)).update();
            return new JobHistory.Outcome(true, reaped[0] + " request(s) timed out");
        });
        return reaped[0];
    }
}
