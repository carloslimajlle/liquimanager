package io.github.carloslimajlle.liquimanager.policies;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.policy.PolicyEngine;

/**
 * The policy library, read only (screen 07).
 *
 * <p>Writing the library lives in {@link PolicyCheckAdminController}. Each check says whether the engine actually implements it:
 * the catalogue lists 51, phase 1 implements 33, and a screen that showed all 51 as live
 * would be promising protection that does not exist.
 */
@RestController
@RequestMapping(Api.V1 + "/policy-checks")
class PolicyLibraryController {

    private static final String COLUMNS = """
            pc.id, pc.code, pc.short_name, pc.description, pc.category, pc.scope, pc.kind,
            pc.default_severity, pc.enabled, pc.parent_id, pc.workspace_id is null as prebuilt,
            (select count(*) from policy_package_check ppc where ppc.check_id = pc.id) as packages,
            (select p.code from policy_check p where p.id = pc.parent_id) as parent_code
            """;

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final Set<String> implemented = PolicyEngine.withBuiltins().implementedCodes();

    PolicyLibraryController(JdbcClient jdbc, WorkspaceContext workspaces) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
    }

    @GetMapping
    List<Map<String, Object>> list(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                   @RequestParam(required = false) String category,
                                   @RequestParam(required = false) Boolean enabled,
                                   @RequestParam(required = false) String kind,
                                   @RequestParam(required = false) String q) {
        UUID workspace = workspaces.resolve(ws);
        List<String> clauses = new ArrayList<>(List.of("(pc.workspace_id is null or pc.workspace_id = :ws)"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ws", workspace);
        if (category != null && !category.isBlank()) {
            clauses.add("pc.category = :category");
            params.put("category", category);
        }
        if (enabled != null) {
            clauses.add("pc.enabled = :enabled");
            params.put("enabled", enabled);
        }
        if (kind != null && !kind.isBlank()) {
            clauses.add("pc.kind = :kind");
            params.put("kind", kind.toUpperCase(Locale.ROOT));
        }
        if (q != null && !q.isBlank()) {
            clauses.add("(lower(pc.code) like :q or lower(pc.description) like :q)");
            params.put("q", "%" + q.trim().toLowerCase(Locale.ROOT) + "%");
        }
        return jdbc.sql("select " + COLUMNS + " from policy_check pc where " + String.join(" and ", clauses)
                        + " order by pc.category, pc.code")
                .params(params).query().listOfRows().stream().map(this::present).toList();
    }

    @GetMapping("/summary")
    Map<String, Object> summary(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws) {
        UUID workspace = workspaces.resolve(ws);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prebuilt", count("select count(*) from policy_check where workspace_id is null and category <> 'Processo'", workspace));
        out.put("prebuiltCategories", count(
                "select count(distinct category) from policy_check where workspace_id is null and category <> 'Processo'", workspace));
        out.put("custom", count("select count(*) from policy_check where workspace_id = :ws", workspace));
        out.put("packages", count("select count(*) from policy_package where workspace_id = :ws", workspace));
        out.put("pipelines", count("select count(*) from pipeline where workspace_id = :ws", workspace));
        out.put("connections", count("select count(*) from db_connection where workspace_id = :ws and archived_at is null", workspace));
        out.put("implemented", implemented.size());
        return out;
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                            @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        List<Map<String, Object>> rows = jdbc.sql("select " + COLUMNS + ", pc.changelog_types, pc.message_template, "
                        + "cast(pc.parameters_schema as text) as parameters_schema, cast(pc.parameters as text) as parameters "
                        + "from policy_check pc where pc.id = :id and (pc.workspace_id is null or pc.workspace_id = :ws)")
                .param("id", id).param("ws", workspace).query().listOfRows();
        if (rows.isEmpty()) {
            throw LqmException.notFound("policy check");
        }
        Map<String, Object> out = present(rows.get(0));
        out.put("changelogTypes", rows.get(0).get("changelog_types"));
        out.put("messageTemplate", rows.get(0).get("message_template"));
        out.put("parametersSchema", rows.get(0).get("parameters_schema"));
        out.put("parameters", rows.get(0).get("parameters"));
        if ("CHAINED".equals(rows.get(0).get("kind"))) {
            out.put("members", jdbc.sql("""
                    select pc.code from policy_chain_member m join policy_check pc on pc.id = m.member_id
                     where m.chain_id = :id order by pc.code
                    """).param("id", id).query(String.class).list());
        }
        return out;
    }

    private Map<String, Object> present(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("code", row.get("code"));
        out.put("shortName", row.get("short_name"));
        out.put("description", row.get("description"));
        out.put("category", row.get("category"));
        out.put("scope", row.get("scope"));
        out.put("kind", row.get("kind"));
        out.put("defaultSeverity", row.get("default_severity"));
        out.put("enabled", row.get("enabled"));
        out.put("prebuilt", row.get("prebuilt"));
        out.put("parentId", row.get("parent_id"));
        out.put("packages", row.get("packages"));
        // A BUILTIN has code behind it (a copy runs its parent's). REGEX, SQL_PATTERN and CHAINED are
        // interpreted by the engine itself.
        String kind = (String) row.get("kind");
        String implementation = row.get("parent_code") != null ? (String) row.get("parent_code") : (String) row.get("code");
        out.put("implemented", switch (kind) {
            case "BUILTIN" -> implemented.contains(implementation);
            case "REGEX", "SQL_PATTERN", "SPEL", "CHAINED" -> true;
            default -> false;
        });
        return out;
    }

    private long count(String sql, UUID workspace) {
        return jdbc.sql(sql).param("ws", workspace).query(Long.class).single();
    }
}
