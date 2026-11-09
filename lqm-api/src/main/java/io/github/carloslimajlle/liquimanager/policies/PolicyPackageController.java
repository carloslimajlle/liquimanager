package io.github.carloslimajlle.liquimanager.policies;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.common.UuidV7;
import io.github.carloslimajlle.liquimanager.policy.Severity;
import tools.jackson.databind.json.JsonMapper;

/**
 * Policy packages: a named set of checks, each with an optional severity and parameter
 * override, and the two thresholds that decide when a finding blocks (docs/08 § 1).
 *
 * <p>A package is ASSIGNED to a changelog or to a pipeline stage; the workspace's package named
 * {@code default} is what applies when nothing more specific does, so it can be edited but
 * not deleted. A package that is assigned somewhere cannot be deleted either: its checks are
 * what a gate enforces right now.
 */
@RestController
@RequestMapping(Api.V1 + "/policy-packages")
class PolicyPackageController {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DEFAULT_NAME = "default";

    record PackageCheck(String code, Integer severityOverride, Map<String, Object> parametersOverride) {
    }

    record PackageBody(String name, String description, Integer blockThresholdProd, Integer blockThresholdNonprod,
                       List<PackageCheck> checks) {
    }

    record AssignBody(UUID changelogId, UUID stageId) {
    }

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final AuditService audit;

    PolicyPackageController(JdbcClient jdbc, WorkspaceContext workspaces, AuditService audit) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.audit = audit;
    }

    @GetMapping
    List<Map<String, Object>> list(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws) {
        UUID workspace = workspaces.resolve(ws);
        return jdbc.sql("""
                select pp.id, pp.name, pp.description, pp.block_threshold_prod, pp.block_threshold_nonprod,
                       (select count(*) from policy_package_check c where c.package_id = pp.id) as checks,
                       (select count(*) from changelog cl where cl.policy_package_id = pp.id) as changelogs,
                       (select count(*) from pipeline_stage ps where ps.policy_package_id = pp.id) as stages
                  from policy_package pp where pp.workspace_id = :ws order by pp.name
                """).param("ws", workspace).query().listOfRows().stream().map(r -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", r.get("id"));
            out.put("name", r.get("name"));
            out.put("description", r.get("description"));
            out.put("blockThresholdProd", r.get("block_threshold_prod"));
            out.put("blockThresholdNonprod", r.get("block_threshold_nonprod"));
            out.put("checks", r.get("checks"));
            out.put("changelogs", r.get("changelogs"));
            out.put("stages", r.get("stages"));
            out.put("isDefault", DEFAULT_NAME.equals(r.get("name")));
            return out;
        }).toList();
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                            @PathVariable UUID id) {
        return present(workspaces.resolve(ws), id);
    }

    @PostMapping
    @Transactional
    ResponseEntity<Map<String, Object>> create(
            @RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
            Authentication authentication, @RequestBody PackageBody body) {
        UUID workspace = workspaces.resolve(ws);
        if (body.name() == null || body.name().isBlank()) {
            throw LqmException.invalid("name is required");
        }
        int prod = threshold(body.blockThresholdProd(), Severity.BLOCK_THRESHOLD_PROD);
        int nonProd = threshold(body.blockThresholdNonprod(), Severity.BLOCK_THRESHOLD_NONPROD);
        UUID id = UuidV7.next();
        jdbc.sql("""
                insert into policy_package (id, workspace_id, name, description, block_threshold_prod, block_threshold_nonprod)
                values (:id, :ws, :name, :description, :prod, :nonprod)
                """).param("id", id).param("ws", workspace).param("name", body.name().trim())
                .param("description", body.description()).param("prod", prod).param("nonprod", nonProd).update();
        replaceChecks(workspace, id, body.checks());

        Map<String, Object> created = present(workspace, id);
        audit.record(workspace, AuditService.actorOf(authentication), "policy-package.create", "policy-package",
                id.toString(), null, created);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    @Transactional
    Map<String, Object> update(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                               Authentication authentication, @PathVariable UUID id, @RequestBody PackageBody body) {
        UUID workspace = workspaces.resolve(ws);
        Map<String, Object> before = present(workspace, id);
        if (body.name() != null && !body.name().isBlank() && !body.name().trim().equals(before.get("name"))
                && DEFAULT_NAME.equals(before.get("name"))) {
            throw new LqmException(HttpStatus.CONFLICT, "LQM-409", "the default package cannot be renamed");
        }
        jdbc.sql("""
                update policy_package set name = :name, description = :description,
                       block_threshold_prod = :prod, block_threshold_nonprod = :nonprod
                 where id = :id and workspace_id = :ws
                """).param("id", id).param("ws", workspace)
                .param("name", body.name() == null || body.name().isBlank() ? before.get("name") : body.name().trim())
                .param("description", body.description() != null ? body.description() : before.get("description"))
                .param("prod", threshold(body.blockThresholdProd(), (Integer) before.get("blockThresholdProd")))
                .param("nonprod", threshold(body.blockThresholdNonprod(), (Integer) before.get("blockThresholdNonprod")))
                .update();
        if (body.checks() != null) {
            replaceChecks(workspace, id, body.checks());
        }
        Map<String, Object> after = present(workspace, id);
        audit.record(workspace, AuditService.actorOf(authentication), "policy-package.update", "policy-package",
                id.toString(), before, after);
        return after;
    }

    @DeleteMapping("/{id}")
    @Transactional
    ResponseEntity<Void> delete(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                Authentication authentication, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        Map<String, Object> before = present(workspace, id);
        if (DEFAULT_NAME.equals(before.get("name"))) {
            throw new LqmException(HttpStatus.CONFLICT, "LQM-409", "the default package cannot be deleted");
        }
        long assigned = ((Number) before.get("changelogs")).longValue() + ((Number) before.get("stages")).longValue();
        if (assigned > 0) {
            throw new LqmException(HttpStatus.CONFLICT, "LQM-409",
                    "the package is assigned to " + assigned + " changelog(s) or stage(s); unassign it first");
        }
        jdbc.sql("delete from policy_package_check where package_id = :id").param("id", id).update();
        jdbc.sql("delete from policy_package where id = :id").param("id", id).update();
        audit.record(workspace, AuditService.actorOf(authentication), "policy-package.delete", "policy-package",
                id.toString(), before, null);
        return ResponseEntity.noContent().build();
    }

    /** Points a changelog or a pipeline stage at this package. Exactly one of the two. */
    @PostMapping("/{id}/assign")
    @Transactional
    Map<String, Object> assign(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                               Authentication authentication, @PathVariable UUID id, @RequestBody AssignBody body) {
        return reassign(ws, authentication, id, body, id);
    }

    @PostMapping("/{id}/unassign")
    @Transactional
    Map<String, Object> unassign(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                 Authentication authentication, @PathVariable UUID id, @RequestBody AssignBody body) {
        return reassign(ws, authentication, id, body, null);
    }

    private Map<String, Object> reassign(String ws, Authentication authentication, UUID id, AssignBody body,
                                         UUID target) {
        UUID workspace = workspaces.resolve(ws);
        present(workspace, id);
        if ((body.changelogId() == null) == (body.stageId() == null)) {
            throw LqmException.invalid("send exactly one of changelogId or stageId");
        }
        int updated;
        String targetType;
        UUID targetId;
        if (body.changelogId() != null) {
            targetType = "changelog";
            targetId = body.changelogId();
            updated = jdbc.sql("""
                    update changelog set policy_package_id = :pkg
                     where id = :id and project_id in (select id from project where workspace_id = :ws)
                    """).param("pkg", target).param("id", targetId).param("ws", workspace).update();
        } else {
            targetType = "pipeline-stage";
            targetId = body.stageId();
            updated = jdbc.sql("""
                    update pipeline_stage set policy_package_id = :pkg
                     where id = :id and pipeline_id in (select id from pipeline where workspace_id = :ws)
                    """).param("pkg", target).param("id", targetId).param("ws", workspace).update();
        }
        if (updated == 0) {
            throw LqmException.notFound(targetType);
        }
        audit.record(workspace, AuditService.actorOf(authentication),
                target == null ? "policy-package.unassign" : "policy-package.assign", targetType,
                targetId.toString(), null, Map.of("packageId", id.toString()));
        return Map.of("packageId", id, targetType.equals("changelog") ? "changelogId" : "stageId", targetId,
                "assigned", target != null);
    }

    // ----------------------------------------------------------------- helpers

    private Map<String, Object> present(UUID workspace, UUID id) {
        var rows = jdbc.sql("""
                select pp.id, pp.name, pp.description, pp.block_threshold_prod, pp.block_threshold_nonprod,
                       (select count(*) from changelog cl where cl.policy_package_id = pp.id) as changelogs,
                       (select count(*) from pipeline_stage ps where ps.policy_package_id = pp.id) as stages
                  from policy_package pp where pp.id = :id and pp.workspace_id = :ws
                """).param("id", id).param("ws", workspace).query().listOfRows();
        if (rows.isEmpty()) {
            throw LqmException.notFound("policy package");
        }
        Map<String, Object> r = rows.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.get("id"));
        out.put("name", r.get("name"));
        out.put("description", r.get("description"));
        out.put("blockThresholdProd", r.get("block_threshold_prod"));
        out.put("blockThresholdNonprod", r.get("block_threshold_nonprod"));
        out.put("changelogs", r.get("changelogs"));
        out.put("stages", r.get("stages"));
        out.put("checks", jdbc.sql("""
                select pc.code, pc.kind, pc.category, pc.default_severity, pc.enabled,
                       ppc.severity_override, cast(ppc.parameters_override as text) as parameters_override
                  from policy_package_check ppc join policy_check pc on pc.id = ppc.check_id
                 where ppc.package_id = :id order by pc.code
                """).param("id", id).query().listOfRows().stream().map(c -> {
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("code", c.get("code"));
            check.put("kind", c.get("kind"));
            check.put("category", c.get("category"));
            check.put("defaultSeverity", c.get("default_severity"));
            check.put("enabled", c.get("enabled"));
            check.put("severityOverride", c.get("severity_override"));
            check.put("parametersOverride", c.get("parameters_override") == null ? null
                    : JSON.readValue((String) c.get("parameters_override"), Map.class));
            return check;
        }).toList());
        return out;
    }

    /** Replaces the package's checks with the given list; unknown codes are rejected, not skipped. */
    private void replaceChecks(UUID workspace, UUID packageId, List<PackageCheck> checks) {
        jdbc.sql("delete from policy_package_check where package_id = :id").param("id", packageId).update();
        if (checks == null) {
            return;
        }
        for (PackageCheck check : checks) {
            UUID checkId = jdbc.sql("""
                    select id from policy_check where code = :code and (workspace_id is null or workspace_id = :ws)
                    """).param("code", check.code()).param("ws", workspace).query(UUID.class).optional()
                    .orElseThrow(() -> LqmException.invalid("unknown check: " + check.code()));
            if (check.severityOverride() != null) {
                Severity.validate(check.severityOverride());
            }
            jdbc.sql("""
                    insert into policy_package_check (package_id, check_id, severity_override, parameters_override)
                    values (:pkg, :check, :severity, cast(:params as jsonb))
                    """).param("pkg", packageId).param("check", checkId).param("severity", check.severityOverride())
                    .param("params", check.parametersOverride() == null ? null
                            : JSON.writeValueAsString(check.parametersOverride()))
                    .update();
        }
    }

    private static int threshold(Integer requested, int fallback) {
        int value = requested == null ? fallback : requested;
        if (value < Severity.MINOR || value > Severity.BLOCKER) {
            throw LqmException.invalid("a block threshold must be between 1 and 4");
        }
        return value;
    }
}
