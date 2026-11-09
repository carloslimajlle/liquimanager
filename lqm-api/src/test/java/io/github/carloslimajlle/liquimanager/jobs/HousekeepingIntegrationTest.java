package io.github.carloslimajlle.liquimanager.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;

/** What the product clears by itself: its own job history and the notifications already sent. */
class HousekeepingIntegrationTest extends AbstractDatabaseTest {

    @Autowired
    private HousekeepingJobs housekeeping;

    private long runs(String job) {
        return jdbc.sql("select count(*) from job_run where job = :j").param("j", job).query(Long.class).single();
    }

    private long deliveries(UUID rule, String key) {
        return jdbc.sql("select count(*) from notification_delivery where rule_id = :r and dedupe_key = :k")
                .param("r", rule).param("k", key).query(Long.class).single();
    }

    @Test
    @DisplayName("old job runs go, recent ones stay")
    void jobRuns() {
        String old = unique("hk-old");
        String recent = unique("hk-new");
        jdbc.sql("insert into job_run (id, job, started_at, ok) values (gen_random_uuid(), :j, now() - interval '9999 days', true)").param("j", old).update();
        jdbc.sql("insert into job_run (id, job, started_at, ok) values (gen_random_uuid(), :j, now(), true)").param("j", recent).update();

        assertThat(housekeeping.runNow().ok()).isTrue();

        assertThat(runs(old)).isZero();
        assertThat(runs(recent)).isEqualTo(1L);
    }

    @Test
    @DisplayName("sent notifications older than the workspace's period go; pending ones and recent ones stay")
    void notifications() {
        UUID workspace = UUID.randomUUID();
        jdbc.sql("insert into workspace (id, slug, name, notification_retention_days) values (:id, :slug, 'hk', 7)")
                .param("id", workspace).param("slug", unique("hk")).update();
        UUID rule = UUID.randomUUID();
        jdbc.sql("""
                insert into notification_rule (id, workspace_id, name, event, channel, target, created_by)
                values (:id, :ws, 'r', 'SYSTEM_ALERT', 'EMAIL', 'a@b.c', 'test')
                """).param("id", rule).param("ws", workspace).update();
        delivery(rule, "old-sent", "SENT", 30);
        delivery(rule, "old-pending", "PENDING", 30);
        delivery(rule, "new-sent", "SENT", 1);

        housekeeping.runNow();

        assertThat(deliveries(rule, "old-sent")).isZero();
        assertThat(deliveries(rule, "old-pending")).isEqualTo(1L);
        assertThat(deliveries(rule, "new-sent")).isEqualTo(1L);
    }

    private void delivery(UUID rule, String key, String status, int daysAgo) {
        jdbc.sql("""
                insert into notification_delivery (id, rule_id, event, subject, body, dedupe_key, status, created_at)
                values (gen_random_uuid(), :r, 'SYSTEM_ALERT', 's', 'b', :k, :st, now() - make_interval(days => :d))
                """).param("r", rule).param("k", key).param("st", status).param("d", daysAgo).update();
    }
}
