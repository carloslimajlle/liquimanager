package io.github.carloslimajlle.liquimanager.notifications;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.carloslimajlle.liquimanager.jobs.JobHistory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Sends the queue, and notices what only time makes true.
 *
 * <p>A delivery that fails is tried again later, a little further apart each time, and given up on after five
 * attempts, which is when it shows as FAILED to whoever looks at the screen. The reason is stored, and it is never the
 * webhook's own answer or URL.
 */
@Component
public class NotificationJobs {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationJobs.class);
    static final int MAX_ATTEMPTS = 5;
    private static final int BATCH = 50;
    private static final int EXPIRY_WINDOW_DAYS = 3;

    private final JdbcClient jdbc;
    private final Senders senders;
    private final NotificationService notifications;
    private final JobHistory history;

    NotificationJobs(JdbcClient jdbc, Senders senders, NotificationService notifications, JobHistory history) {
        this.jdbc = jdbc;
        this.senders = senders;
        this.notifications = notifications;
        this.history = history;
    }

    @Scheduled(cron = "${lqm.jobs.notifications-cron:*/30 * * * * *}")
    @SchedulerLock(name = "notifications-dispatch", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    void tick() {
        dispatch();
    }

    @Scheduled(cron = "${lqm.jobs.exception-expiry-cron:0 5 * * * *}")
    @SchedulerLock(name = "notifications-expiring", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    void expiring() {
        history.run("notify-expiring-exceptions", () -> new JobHistory.Outcome(true, announceExpiring() + " exception(s) announced"));
    }

    /** Sends what is due; returns how many it delivered. */
    @Transactional
    public int dispatch() {
        var due = jdbc.sql("""
                select d.id, d.rule_id, d.subject, d.body, d.attempts, r.channel, r.target, r.digest_minutes
                  from notification_delivery d join notification_rule r on r.id = d.rule_id
                 where d.status = 'PENDING' and d.next_at <= now()
                 order by d.created_at limit :batch for update of d skip locked
                """).param("batch", BATCH).query().listOfRows();
        int sent = 0;
        // A rule with a digest speaks once for what piled up in its window; everything else goes one by one.
        Map<Object, List<Map<String, Object>>> digests = new LinkedHashMap<>();
        List<Map<String, Object>> single = new ArrayList<>();
        for (var row : due) {
            if (row.get("digest_minutes") == null) {
                single.add(row);
            } else {
                digests.computeIfAbsent(row.get("rule_id"), k -> new ArrayList<>()).add(row);
            }
        }
        for (List<Map<String, Object>> group : digests.values()) {
            if (group.size() == 1) {
                single.add(group.get(0));
            } else {
                sent += sendDigest(group);
            }
        }
        for (var row : single) {
            UUID id = (UUID) row.get("id");
            int attempts = ((Number) row.get("attempts")).intValue() + 1;
            try {
                senders.send((String) row.get("channel"), (String) row.get("target"), (String) row.get("subject"),
                        (String) row.get("body"));
                jdbc.sql("update notification_delivery set status = 'SENT', attempts = :a, sent_at = now(), last_error = null where id = :id")
                        .param("a", attempts).param("id", id).update();
                sent++;
            } catch (Senders.Failed e) {
                boolean giveUp = attempts >= MAX_ATTEMPTS;
                // 2, 4, 8, 16 minutes between tries.
                jdbc.sql("""
                        update notification_delivery set status = :status, attempts = :a, last_error = :error,
                               next_at = :next where id = :id
                        """).param("status", giveUp ? "FAILED" : "PENDING").param("a", attempts).param("error", e.getMessage())
                        .param("next", Timestamp.from(Instant.now().plusSeconds(60L * (1L << attempts)))).param("id", id).update();
                LOG.warn("notification {} failed (attempt {}): {}", id, attempts, e.getMessage());
            }
        }
        return sent;
    }

    /** One message for the deliveries a digest rule piled up: the count, then each subject and body. All of them are sent, or none is. */
    private int sendDigest(List<Map<String, Object>> group) {
        String channel = (String) group.get(0).get("channel");
        String target = (String) group.get(0).get("target");
        StringBuilder body = new StringBuilder();
        for (var row : group) {
            body.append("- ").append(row.get("subject")).append('\n').append(row.get("body")).append("\n\n");
        }
        String subject = "LiquiManager: " + group.size() + " notificacoes agrupadas";
        try {
            senders.send(channel, target, subject, body.toString().strip());
            for (var row : group) {
                jdbc.sql("update notification_delivery set status = 'SENT', attempts = attempts + 1, sent_at = now(), last_error = null where id = :id")
                        .param("id", row.get("id")).update();
            }
            return group.size();
        } catch (Senders.Failed e) {
            for (var row : group) {
                int attempts = ((Number) row.get("attempts")).intValue() + 1;
                boolean giveUp = attempts >= MAX_ATTEMPTS;
                jdbc.sql("""
                        update notification_delivery set status = :status, attempts = :a, last_error = :error, next_at = :next where id = :id
                        """).param("status", giveUp ? "FAILED" : "PENDING").param("a", attempts).param("error", e.getMessage())
                        .param("next", Timestamp.from(Instant.now().plusSeconds(60L * (1L << attempts)))).param("id", row.get("id")).update();
            }
            LOG.warn("digest of {} notifications failed: {}", group.size(), e.getMessage());
            return 0;
        }
    }

    /** Approved exceptions that run out within three days, once each. */
    int announceExpiring() {
        List<java.util.Map<String, Object>> rows = jdbc.sql("""
                select e.id, e.workspace_id, e.expires_at, pc.code as check_code, p.slug as project, env.code as environment,
                       cs.cs_id, e.requested_by
                  from policy_exception e
                  join policy_check pc on pc.id = e.check_id
                  left join project p on p.id = e.project_id
                  left join environment env on env.id = e.environment_id
                  left join changeset cs on cs.id = e.changeset_id
                 where e.status = 'APPROVED' and e.expires_at > now()
                   and e.expires_at <= now() + make_interval(days => :days)
                """).param("days", EXPIRY_WINDOW_DAYS).query().listOfRows();
        int announced = 0;
        for (var row : rows) {
            Instant expires = ((Timestamp) row.get("expires_at")).toInstant();
            String scope = row.get("cs_id") != null ? "o changeset " + row.get("cs_id") : "todos os changesets";
            announced += notifications.notify((UUID) row.get("workspace_id"), "EXCEPTION_EXPIRING",
                    (String) row.get("project"), (String) row.get("environment"),
                    "Excecao a vencer: " + row.get("check_code"),
                    "A excecao do check " + row.get("check_code") + " para " + scope + ", pedida por " + row.get("requested_by")
                            + ", vence em " + expires + ". Depois disso o check volta a bloquear.",
                    "exception:" + row.get("id")) > 0 ? 1 : 0;
        }
        return announced;
    }
}
