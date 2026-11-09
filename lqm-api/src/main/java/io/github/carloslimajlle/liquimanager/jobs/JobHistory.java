package io.github.carloslimajlle.liquimanager.jobs;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.github.carloslimajlle.liquimanager.common.UuidV7;

/**
 * Runs a job body and writes what happened to {@code job_run}. A job that throws is recorded as failed
 * and logged, never rethrown: the scheduler has to keep firing it on the next tick.
 */
@Component
public class JobHistory {

    private static final Logger LOG = LoggerFactory.getLogger(JobHistory.class);

    /** What a job reports back: did it do what it was meant to, and a line to read later. */
    public record Outcome(boolean ok, String detail) {
    }

    private final JdbcClient jdbc;

    JobHistory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Outcome run(String job, Supplier<Outcome> body) {
        UUID id = UuidV7.next();
        Instant started = Instant.now();
        jdbc.sql("insert into job_run (id, job, started_at) values (:id, :job, :at)")
                .param("id", id).param("job", job).param("at", Timestamp.from(started)).update();
        Outcome outcome;
        try {
            outcome = body.get();
        } catch (RuntimeException e) {
            LOG.error("job {} failed", job, e);
            outcome = new Outcome(false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (!outcome.ok()) {
            LOG.error("job {} finished with a problem: {}", job, outcome.detail());
        }
        jdbc.sql("update job_run set ended_at = :at, ok = :ok, detail = :detail where id = :id")
                .param("at", Timestamp.from(Instant.now())).param("ok", outcome.ok())
                .param("detail", outcome.detail()).param("id", id).update();
        return outcome;
    }
}
