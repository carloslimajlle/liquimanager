package io.github.carloslimajlle.liquimanager.notifications;

import java.net.URI;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.common.UuidV7;

/**
 * Notification rules (screen /admin/notifications) and what was sent.
 *
 * <p>A webhook URL lets anyone who has it post in the channel, so it is a credential: the API takes it on create and
 * update and never returns it again, only its host. A rule updated without a {@code target} keeps the one it has.
 */
@RestController
@RequestMapping(Api.V1)
class NotificationController {

    private static final Set<String> CHANNELS = Set.of("EMAIL", "TEAMS", "SLACK");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s,]+@[^@\\s,]+\\.[^@\\s,]+$");

    record RuleBody(String name, String event, String channel, String target, String environment, String project, Boolean enabled,
                    Integer minSeverity, String quietStart, String quietEnd, String quietZone, Integer digestMinutes) {
    }

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final AuditService audit;
    private final Senders senders;

    NotificationController(JdbcClient jdbc, WorkspaceContext workspaces, AuditService audit, Senders senders) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.audit = audit;
        this.senders = senders;
    }

    // -------------------------------------------------------------------- rules

    @GetMapping("/notification-rules")
    List<Map<String, Object>> list(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws) {
        return jdbc.sql(SELECT + " where r.workspace_id = :ws order by r.name").param("ws", workspaces.resolve(ws))
                .query().listOfRows().stream().map(NotificationController::present).toList();
    }

    @PostMapping("/notification-rules")
    @Transactional
    ResponseEntity<Map<String, Object>> create(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                               Authentication authentication, @RequestBody RuleBody body) {
        UUID workspace = workspaces.resolve(ws);
        String event = body.event() == null ? "" : body.event().toUpperCase(Locale.ROOT);
        String channel = body.channel() == null ? "" : body.channel().toUpperCase(Locale.ROOT);
        validate(body.name(), event, channel, body.target());

        UUID id = UuidV7.next();
        jdbc.sql("""
                insert into notification_rule (id, workspace_id, name, event, channel, target, environment_id, project_id, enabled, created_by,
                    min_severity, quiet_start, quiet_end, quiet_zone, digest_minutes)
                values (:id, :ws, :name, :event, :channel, :target, :env, :project, :enabled, :by,
                    :severity, cast(:qs as time), cast(:qe as time), :qz, :digest)
                """).params(options(body)).param("id", id).param("ws", workspace).param("name", body.name().trim()).param("event", event)
                .param("channel", channel).param("target", body.target().trim())
                .param("env", environmentId(workspace, body.environment())).param("project", projectId(workspace, body.project()))
                .param("enabled", body.enabled() == null || body.enabled()).param("by", AuditService.actorOf(authentication)).update();

        Map<String, Object> created = load(workspace, id);
        audit.record(workspace, AuditService.actorOf(authentication), "notification.create", "notification-rule", id.toString(), null, created);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/notification-rules/{id}")
    @Transactional
    Map<String, Object> update(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                               Authentication authentication, @PathVariable UUID id, @RequestBody RuleBody body) {
        UUID workspace = workspaces.resolve(ws);
        Map<String, Object> before = load(workspace, id);
        var current = jdbc.sql("select name, event, channel, target, environment_id, project_id, enabled from notification_rule where id = :id")
                .param("id", id).query().singleRow();

        String name = body.name() != null ? body.name() : (String) current.get("name");
        String event = (body.event() != null ? body.event() : (String) current.get("event")).toUpperCase(Locale.ROOT);
        String channel = (body.channel() != null ? body.channel() : (String) current.get("channel")).toUpperCase(Locale.ROOT);
        // Omitted keeps the stored target: the API never shows it, so the screen has nothing to send back.
        String target = body.target() != null && !body.target().isBlank() ? body.target() : (String) current.get("target");
        validate(name, event, channel, target);

        jdbc.sql("""
                update notification_rule set name = :name, event = :event, channel = :channel, target = :target,
                       environment_id = :env, project_id = :project, enabled = :enabled,
                       min_severity = :severity, quiet_start = cast(:qs as time), quiet_end = cast(:qe as time), quiet_zone = :qz,
                       digest_minutes = :digest where id = :id
                """).params(options(body)).param("id", id).param("name", name.trim()).param("event", event).param("channel", channel)
                .param("target", target.trim())
                .param("env", body.environment() != null ? environmentId(workspace, body.environment()) : current.get("environment_id"))
                .param("project", body.project() != null ? projectId(workspace, body.project()) : current.get("project_id"))
                .param("enabled", body.enabled() != null ? body.enabled() : current.get("enabled")).update();

        Map<String, Object> after = load(workspace, id);
        audit.record(workspace, AuditService.actorOf(authentication), "notification.update", "notification-rule", id.toString(), before, after);
        return after;
    }

    @DeleteMapping("/notification-rules/{id}")
    @Transactional
    ResponseEntity<Void> delete(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                Authentication authentication, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        Map<String, Object> before = load(workspace, id);
        jdbc.sql("delete from notification_rule where id = :id").param("id", id).update();
        audit.record(workspace, AuditService.actorOf(authentication), "notification.delete", "notification-rule", id.toString(), before, null);
        return ResponseEntity.noContent().build();
    }

    /** Sends a message to the rule's target right now and says whether it arrived, so a wrong URL shows up when it is typed. */
    @PostMapping("/notification-rules/{id}/test")
    Map<String, Object> test(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        load(workspace, id);
        var rule = jdbc.sql("select channel, target from notification_rule where id = :id").param("id", id).query().singleRow();
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            senders.send((String) rule.get("channel"), (String) rule.get("target"), "LiquiManager: mensagem de teste",
                    "Se voce esta lendo isto, a regra de notificacao esta funcionando.");
            out.put("ok", true);
        } catch (Senders.Failed e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        return out;
    }

    // --------------------------------------------------------------- deliveries

    @GetMapping("/notification-deliveries")
    List<Map<String, Object>> deliveries(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                         @RequestParam(required = false) String status) {
        UUID workspace = workspaces.resolve(ws);
        return jdbc.sql("""
                select d.id, d.event, d.subject, d.status, d.attempts, d.last_error, d.created_at, d.sent_at, r.name as rule, r.channel
                  from notification_delivery d join notification_rule r on r.id = d.rule_id
                 where r.workspace_id = :ws and (cast(:status as text) is null or d.status = :status)
                 order by d.created_at desc limit 100
                """).param("ws", workspace).param("status", status == null || status.isBlank() ? null : status.toUpperCase(Locale.ROOT))
                .query().listOfRows().stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.get("id"));
                    m.put("event", r.get("event"));
                    m.put("subject", r.get("subject"));
                    m.put("status", r.get("status"));
                    m.put("attempts", r.get("attempts"));
                    m.put("error", r.get("last_error"));
                    m.put("rule", r.get("rule"));
                    m.put("channel", r.get("channel"));
                    m.put("createdAt", ((Timestamp) r.get("created_at")).toInstant());
                    m.put("sentAt", r.get("sent_at") == null ? null : ((Timestamp) r.get("sent_at")).toInstant());
                    return m;
                }).toList();
    }

    // ------------------------------------------------------------------ helpers

    private static final String SELECT = """
            select r.id, r.name, r.event, r.channel, r.target, r.enabled, r.created_by, r.created_at,
                   r.min_severity, r.quiet_start, r.quiet_end, r.quiet_zone, r.digest_minutes,
                   e.code as environment, p.slug as project
              from notification_rule r
              left join environment e on e.id = r.environment_id
              left join project p on p.id = r.project_id
            """;

    /**
     * The options that say when and how much a rule speaks, validated: a severity from 0 to 4, quiet hours as two times in a zone (both or
     * neither), a digest of 5 minutes to a day. They are the rule's whole state, so an update sends them all and a missing one clears it.
     */
    private static Map<String, Object> options(RuleBody body) {
        Integer severity = body.minSeverity();
        if (severity != null && (severity < 0 || severity > 4)) {
            throw LqmException.invalid("minSeverity must be from 0 (INFO) to 4 (BLOCKER)");
        }
        boolean hasStart = body.quietStart() != null && !body.quietStart().isBlank();
        boolean hasEnd = body.quietEnd() != null && !body.quietEnd().isBlank();
        if (hasStart != hasEnd) {
            throw LqmException.invalid("quiet hours need both a start and an end");
        }
        String zone = body.quietZone() == null || body.quietZone().isBlank() ? null : body.quietZone().trim();
        try {
            if (hasStart) {
                java.time.LocalTime.parse(body.quietStart().trim());
                java.time.LocalTime.parse(body.quietEnd().trim());
            }
            if (zone != null) {
                java.time.ZoneId.of(zone);
            }
        } catch (java.time.DateTimeException e) {
            throw LqmException.invalid("quiet hours are HH:mm times and the zone a Java zone id (America/Sao_Paulo)");
        }
        Integer digest = body.digestMinutes();
        if (digest != null && (digest < 5 || digest > 1440)) {
            throw LqmException.invalid("digestMinutes must be from 5 to 1440");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("severity", severity);
        out.put("qs", hasStart ? body.quietStart().trim() : null);
        out.put("qe", hasEnd ? body.quietEnd().trim() : null);
        out.put("qz", hasStart ? (zone == null ? "UTC" : zone) : null);
        out.put("digest", digest);
        return out;
    }

    private Map<String, Object> load(UUID workspace, UUID id) {
        return jdbc.sql(SELECT + " where r.id = :id and r.workspace_id = :ws").param("id", id).param("ws", workspace)
                .query().listOfRows().stream().findFirst().map(NotificationController::present)
                .orElseThrow(() -> LqmException.notFound("notification rule"));
    }

    private static Map<String, Object> present(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("name", r.get("name"));
        m.put("event", r.get("event"));
        m.put("channel", r.get("channel"));
        m.put("target", masked((String) r.get("channel"), (String) r.get("target")));
        m.put("environment", r.get("environment"));
        m.put("project", r.get("project"));
        m.put("enabled", r.get("enabled"));
        m.put("minSeverity", r.get("min_severity"));
        m.put("quietStart", r.get("quiet_start") == null ? null : r.get("quiet_start").toString().substring(0, 5));
        m.put("quietEnd", r.get("quiet_end") == null ? null : r.get("quiet_end").toString().substring(0, 5));
        m.put("quietZone", r.get("quiet_zone"));
        m.put("digestMinutes", r.get("digest_minutes"));
        m.put("createdBy", r.get("created_by"));
        m.put("createdAt", ((Timestamp) r.get("created_at")).toInstant());
        return m;
    }

    /** Addresses are not secret; a webhook URL is, so only its host is shown. */
    static String masked(String channel, String target) {
        if ("EMAIL".equals(channel)) {
            return target;
        }
        try {
            String host = URI.create(target).getHost();
            return (host == null ? "webhook" : host) + "/…";
        } catch (IllegalArgumentException e) {
            return "webhook";
        }
    }

    private static void validate(String name, String event, String channel, String target) {
        if (name == null || name.isBlank()) {
            throw LqmException.invalid("name is required");
        }
        if (!NotificationService.EVENTS.contains(event)) {
            throw LqmException.invalid("event must be one of " + String.join(", ", NotificationService.EVENTS.stream().sorted().toList()));
        }
        if (!CHANNELS.contains(channel)) {
            throw LqmException.invalid("channel must be one of " + String.join(", ", CHANNELS));
        }
        if (target == null || target.isBlank()) {
            throw LqmException.invalid("target is required");
        }
        if (channel.equals("EMAIL")) {
            for (String address : target.split(",")) {
                if (!EMAIL.matcher(address.trim()).matches()) {
                    throw LqmException.invalid("not an e-mail address: " + address.trim());
                }
            }
        } else {
            try {
                URI uri = URI.create(target.trim());
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                    throw LqmException.invalid("a webhook must be an https URL");
                }
            } catch (IllegalArgumentException e) {
                throw LqmException.invalid("a webhook must be an https URL");
            }
        }
    }

    private UUID environmentId(UUID workspace, String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return jdbc.sql("select id from environment where workspace_id = :ws and code = :code")
                .param("ws", workspace).param("code", code.toUpperCase(Locale.ROOT)).query(UUID.class).optional()
                .orElseThrow(() -> LqmException.invalid("unknown environment: " + code));
    }

    private UUID projectId(UUID workspace, String slug) {
        if (slug == null || slug.isBlank()) {
            return null;
        }
        return jdbc.sql("select id from project where workspace_id = :ws and slug = :slug")
                .param("ws", workspace).param("slug", slug).query(UUID.class).optional()
                .orElseThrow(() -> LqmException.invalid("unknown project: " + slug));
    }
}
