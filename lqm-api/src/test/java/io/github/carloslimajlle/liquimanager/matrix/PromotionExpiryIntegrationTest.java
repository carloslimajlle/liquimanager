package io.github.carloslimajlle.liquimanager.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;

/** A promotion request that nobody decided in time expires, once, with an audit event; a TTL of 0 never expires. */
class PromotionExpiryIntegrationTest extends AbstractDatabaseTest {

    @Autowired
    private PromotionJobs jobs;

    private UUID workspace(int ttlHours) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into workspace (id, slug, name, promotion_ttl_hours) values (:id, :slug, 'pe', :ttl)")
                .param("id", id).param("slug", unique("pe")).param("ttl", ttlHours).update();
        return id;
    }

    private UUID request(UUID workspace, String status, int hoursAgo) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into promotion (id, workspace_id, pipeline_id, stage_id, environment, status, requested_by, created_at)
                values (:id, :ws, gen_random_uuid(), gen_random_uuid(), 'PRD', :st, 'ana', now() - make_interval(hours => :h))
                """).param("id", id).param("ws", workspace).param("st", status).param("h", hoursAgo).update();
        return id;
    }

    private String status(UUID id) {
        return jdbc.sql("select status from promotion where id = :id").param("id", id).query(String.class).single();
    }

    @Test
    @DisplayName("a request older than the workspace's TTL expires, a younger one and an already decided one do not")
    void expires() {
        UUID workspace = workspace(24);
        UUID stale = request(workspace, "REQUESTED", 30);
        UUID fresh = request(workspace, "REQUESTED", 2);
        UUID decided = request(workspace, "TRIGGERED", 100);

        assertThat(jobs.expire()).isGreaterThanOrEqualTo(1);

        assertThat(status(stale)).isEqualTo("EXPIRED");
        assertThat(status(fresh)).isEqualTo("REQUESTED");
        assertThat(status(decided)).isEqualTo("TRIGGERED");
        assertThat(jdbc.sql("select count(*) from audit_event where target_id = :id and action = 'promotion.expire' and actor = 'system'")
                .param("id", stale.toString()).query(Long.class).single()).isEqualTo(1L);

        jobs.expire();
        assertThat(jdbc.sql("select count(*) from audit_event where target_id = :id and action = 'promotion.expire'")
                .param("id", stale.toString()).query(Long.class).single()).as("expired once").isEqualTo(1L);
    }

    @Test
    @DisplayName("a TTL of 0 turns the expiry off")
    void zeroNeverExpires() {
        UUID workspace = workspace(0);
        UUID old = request(workspace, "REQUESTED", 5000);

        jobs.expire();

        assertThat(status(old)).isEqualTo("REQUESTED");
    }
}
