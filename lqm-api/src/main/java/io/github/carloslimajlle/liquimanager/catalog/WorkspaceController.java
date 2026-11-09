package io.github.carloslimajlle.liquimanager.catalog;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.common.UuidV7;

/**
 * Workspaces (RF-CAD-01), the top-level grouping.
 *
 * <p>Read and create only. There is no PUT/DELETE: renaming a slug would break every
 * collector's {@code X-LQM-Workspace} header, and a workspace owns the whole catalogue.
 * {@code aiEnabled} is not writable here — it is a future feature that starts off (D-13).
 */
@RestController
@RequestMapping(Api.V1 + "/workspaces")
class WorkspaceController {

    private static final String COLUMNS = "id, slug, name, ai_enabled, created_at, job_run_retention_days, "
            + "notification_retention_days, promotion_ttl_hours";

    private final JdbcClient jdbc;
    private final AuditService audit;

    WorkspaceController(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** What the administrator can tune (docs/09: {@code workspace:admin}); each one is validated against the table's own limits. */
    record SettingsRequest(Integer jobRunRetentionDays, Integer notificationRetentionDays, Integer promotionTtlHours) {
    }

    @PutMapping("/{id}/settings")
    Map<String, Object> settings(@PathVariable UUID id, Authentication authentication, @RequestBody SettingsRequest body) {
        Map<String, Object> before = find(id);
        int jobRuns = within(body.jobRunRetentionDays(), "jobRunRetentionDays", 7, 3650);
        int notifications = within(body.notificationRetentionDays(), "notificationRetentionDays", 7, 3650);
        int ttl = within(body.promotionTtlHours(), "promotionTtlHours", 0, 8760);
        jdbc.sql("update workspace set job_run_retention_days = :a, notification_retention_days = :b, promotion_ttl_hours = :c where id = :id")
                .param("a", jobRuns).param("b", notifications).param("c", ttl).param("id", id).update();
        Map<String, Object> after = find(id);
        audit.record(id, AuditService.actorOf(authentication), "workspace.settings", "workspace", id.toString(), before, after);
        return after;
    }

    private static int within(Integer value, String name, int min, int max) {
        if (value == null || value < min || value > max) {
            throw LqmException.invalid(name + " must be between " + min + " and " + max);
        }
        return value;
    }

    record WorkspaceRequest(String slug, String name) {
    }

    @GetMapping
    List<Map<String, Object>> list() {
        return Rows.camel(jdbc.sql("select " + COLUMNS + " from workspace order by slug").query().listOfRows());
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@PathVariable UUID id) {
        return find(id);
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(@RequestBody WorkspaceRequest body) {
        Validation.slug(body.slug(), "slug");
        Validation.required(body.name(), "name");
        UUID id = UuidV7.next();
        jdbc.sql("insert into workspace (id, slug, name) values (:id, :slug, :name)")
                .param("id", id).param("slug", body.slug()).param("name", body.name().trim()).update();
        return ResponseEntity.status(HttpStatus.CREATED).body(find(id));
    }

    private Map<String, Object> find(UUID id) {
        List<Map<String, Object>> rows = jdbc.sql("select " + COLUMNS + " from workspace where id = :id")
                .param("id", id).query().listOfRows();
        if (rows.isEmpty()) {
            throw LqmException.notFound("workspace");
        }
        return Rows.camel(rows.get(0));
    }
}
