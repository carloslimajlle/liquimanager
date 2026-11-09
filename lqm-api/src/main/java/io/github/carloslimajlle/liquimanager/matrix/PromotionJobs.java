package io.github.carloslimajlle.liquimanager.matrix;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.jobs.JobHistory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * A promotion request nobody decided does not wait forever: after the workspace's {@code promotion_ttl_hours} it becomes
 * {@code EXPIRED} (0 turns this off). Whoever still wants the release asks again, so the approval always reflects what is true
 * today, not what was true a week ago. Each expiry is an audit event.
 */
@Component
class PromotionJobs {

    static final String NAME = "promotion-expiry";

    private final JdbcClient jdbc;
    private final JobHistory history;
    private final AuditService audit;

    PromotionJobs(JdbcClient jdbc, JobHistory history, AuditService audit) {
        this.jdbc = jdbc;
        this.history = history;
        this.audit = audit;
    }

    @Scheduled(cron = "${lqm.jobs.promotion-expiry-cron:0 */15 * * * *}")
    @SchedulerLock(name = NAME, lockAtMostFor = "PT10M", lockAtLeastFor = "PT20S")
    void tick() {
        expire();
    }

    /** Expires what waited too long; returns how many. */
    int expire() {
        int[] expired = {0};
        history.run(NAME, () -> {
            List<Map<String, Object>> rows = jdbc.sql("""
                    update promotion p set status = 'EXPIRED', decided_by = 'system', decided_at = now()
                      from workspace w
                     where w.id = p.workspace_id and p.status = 'REQUESTED' and w.promotion_ttl_hours > 0
                       and p.created_at < now() - make_interval(hours => w.promotion_ttl_hours)
                    returning p.id, p.workspace_id, p.pipeline_id, p.environment, p.requested_by, w.promotion_ttl_hours
                    """).query().listOfRows();
            for (Map<String, Object> row : rows) {
                audit.record((UUID) row.get("workspace_id"), "system", "promotion.expire", "promotion", row.get("id").toString(),
                        Map.of("status", "REQUESTED"),
                        Map.of("status", "EXPIRED", "environment", row.get("environment"), "requestedBy", row.get("requested_by"),
                                "ttlHours", row.get("promotion_ttl_hours")));
            }
            expired[0] = rows.size();
            return new JobHistory.Outcome(true, rows.size() + " request(s) expired");
        });
        return expired[0];
    }
}
