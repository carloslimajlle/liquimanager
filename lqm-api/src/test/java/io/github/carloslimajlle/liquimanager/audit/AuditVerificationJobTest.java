package io.github.carloslimajlle.liquimanager.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import io.github.carloslimajlle.liquimanager.jobs.JobHistory;

class AuditVerificationJobTest extends AbstractDatabaseTest {

    @Autowired
    AuditService audit;

    @Autowired
    JobHistory history;

    private JobHistory.Outcome verify() {
        return history.run("audit-verify", () -> {
            AuditService.Verification v = audit.verify();
            return new JobHistory.Outcome(v.intact(), v.intact() ? v.checked() + " events verified" : "broken at " + v.brokenAt());
        });
    }

    @Test
    @DisplayName("a run is recorded with its result")
    void recorded() {
        audit.record(WORKSPACE, "ana", "policy.job", "x", UUID.randomUUID().toString(), null, Map.of("a", 1));

        JobHistory.Outcome outcome = verify();

        assertThat(outcome.ok()).isTrue();
        var row = jdbc.sql("select ok, detail, ended_at is not null as ended from job_run where job = 'audit-verify' order by started_at desc limit 1")
                .query().singleRow();
        assertThat(row.get("ok")).isEqualTo(true);
        assertThat(row.get("ended")).isEqualTo(true);
        assertThat((String) row.get("detail")).contains("events verified");
    }

    @Test
    @DisplayName("a job that throws is recorded as failed and does not propagate")
    void failureIsRecorded() {
        JobHistory.Outcome outcome = history.run("boom-job", () -> {
            throw new IllegalStateException("kaput");
        });

        assertThat(outcome.ok()).isFalse();
        assertThat(jdbc.sql("select detail from job_run where job = 'boom-job' order by started_at desc limit 1")
                .query(String.class).single()).contains("kaput");
    }

    @Test
    @DisplayName("a tampered chain makes the run fail with where it broke")
    void tamperingFailsTheRun() {
        audit.record(WORKSPACE, "ana", "policy.job-tamper", "x", UUID.randomUUID().toString(), null, Map.of("a", 1));
        long seq = jdbc.sql("select max(seq) from audit_event where action = 'policy.job-tamper'").query(Long.class).single();
        jdbc.sql("update audit_event set actor = 'mallory' where seq = :s").param("s", seq).update();
        try {
            JobHistory.Outcome outcome = verify();
            assertThat(outcome.ok()).isFalse();
            assertThat(outcome.detail()).contains("broken at " + seq);
        } finally {
            jdbc.sql("update audit_event set actor = 'ana' where seq = :s").param("s", seq).update();
        }
    }
}
