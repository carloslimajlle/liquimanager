package io.github.carloslimajlle.liquimanager.notifications;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import io.github.carloslimajlle.liquimanager.common.UuidV7;

/**
 * Turns an event into messages: finds the rules that want it and queues one delivery per rule.
 *
 * <p>Queued, not sent, on purpose. Whoever raises an event is in the middle of something that matters more (an
 * ingestion, a drift run) and must neither wait for a webhook nor fail because one is down; {@link
 * NotificationJobs} sends the queue and retries.
 *
 * <p>{@code dedupeKey} names WHAT happened, so that the same thing seen twice (an operation ingested again, an
 * exception checked every hour) is told once per rule.
 */
@Service
public class NotificationService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);

    /** What can be subscribed to. */
    public static final Set<String> EVENTS = Set.of(
            "PRD_FAILURE", "GATE_BLOCKED", "DRIFT_DETECTED", "EXCEPTION_EXPIRING", "ROLLBACK_PENDING", "SYSTEM_ALERT");

    private final JdbcClient jdbc;

    NotificationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param project     slug of the project the event is about, or null
     * @param environment code of the environment, or null
     * @return how many deliveries were queued
     */
    public int notify(UUID workspace, String event, String project, String environment, String subject, String body,
                      String dedupeKey) {
        return notify(workspace, event, null, project, environment, subject, body, dedupeKey);
    }

    /**
     * @param severity how serious the event is (the scale of the policy checks, 0 to 4), or null when it has none; a rule with a minimum
     *                 severity skips an event below it, and never skips one that carries no severity (what cannot be judged is not hidden)
     */
    public int notify(UUID workspace, String event, Integer severity, String project, String environment, String subject, String body,
                      String dedupeKey) {
        if (!EVENTS.contains(event)) {
            throw new IllegalArgumentException("unknown notification event " + event);
        }
        try {
            var rules = jdbc.sql("""
                    select r.id, r.quiet_start, r.quiet_end, r.quiet_zone, r.digest_minutes from notification_rule r
                      left join environment e on e.id = r.environment_id
                      left join project p on p.id = r.project_id
                     where r.workspace_id = :ws and r.enabled and r.event = :event
                       and (r.environment_id is null or e.code = :env)
                       and (r.project_id is null or p.slug = :project)
                       and (r.min_severity is null or cast(:severity as int) is null or cast(:severity as int) >= r.min_severity)
                    """).param("ws", workspace).param("event", event).param("env", environment).param("project", project)
                    .param("severity", severity).query().listOfRows();
            Instant now = Instant.now();
            int queued = 0;
            for (var rule : rules) {
                // Quiet hours hold the message until the window ends; a digest holds it until the end of its window.
                Instant when = QuietHours.releaseAt(now, time(rule.get("quiet_start")), time(rule.get("quiet_end")), zone((String) rule.get("quiet_zone")));
                if (rule.get("digest_minutes") != null) {
                    Instant windowEnd = QuietHours.digestEnd(now, ((Number) rule.get("digest_minutes")).intValue());
                    when = when.isAfter(windowEnd) ? when : windowEnd;
                }
                queued += jdbc.sql("""
                        insert into notification_delivery (id, rule_id, event, subject, body, dedupe_key, status, next_at)
                        values (:id, :rule, :event, :subject, :body, :key, 'PENDING', :next)
                        on conflict (rule_id, dedupe_key) do nothing
                        """).param("id", UuidV7.next()).param("rule", rule.get("id")).param("event", event)
                        .param("subject", cut(subject, 300)).param("body", body).param("key", cut(dedupeKey, 200))
                        .param("next", Timestamp.from(when)).update();
            }
            return queued;
        } catch (RuntimeException e) {
            // Failing to queue a message must never break what raised it.
            LOG.warn("could not queue the notification {} ({}): {}", event, dedupeKey, e.getMessage());
            return 0;
        }
    }

    /** For what concerns the installation and not one workspace (the audit chain is one for all): tell every workspace. */
    public int notifyEveryWorkspace(String event, String subject, String body, String dedupeKey) {
        int queued = 0;
        for (UUID workspace : jdbc.sql("select id from workspace").query(UUID.class).list()) {
            queued += notify(workspace, event, null, null, subject, body, dedupeKey);
        }
        return queued;
    }

    private static LocalTime time(Object value) {
        return value == null ? null : ((Time) value).toLocalTime();
    }

    private static ZoneId zone(String name) {
        try {
            return name == null || name.isBlank() ? ZoneId.of("UTC") : ZoneId.of(name);
        } catch (DateTimeException unknown) {
            return ZoneId.of("UTC");
        }
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
