package io.github.carloslimajlle.liquimanager.policies;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import io.github.carloslimajlle.liquimanager.policy.EffectiveCheck;
import io.github.carloslimajlle.liquimanager.policy.builtin.CustomChecks;
import io.github.carloslimajlle.liquimanager.policy.Severity;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writing the policy library (E9): create, edit and remove a workspace's own checks, copy a
 * builtin to customise it, and dry-run a check over a pasted changelog.
 *
 * <p>The rules that keep this safe:
 * <ul>
 *   <li>A builtin is never edited or deleted. It belongs to every workspace; to change its
 *       behaviour you COPY it and edit the copy.
 *   <li>A check in use (in a package, or as a chain member) cannot be deleted: removing it would
 *       silently change what a gate enforces.
 *   <li>The definition is validated when it is saved, not when a deploy first meets it: an
 *       invalid regex saved today would otherwise be a silent no-op in production.
 *   <li>Every change is audited with before and after.
 * </ul>
 */
@RestController
@RequestMapping(Api.V1 + "/policy-checks")
class PolicyCheckAdminController {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9]{2,79}$");
    private static final Set<String> KINDS = Set.of("REGEX", "SQL_PATTERN", "SPEL", "CHAINED");
    private static final Set<String> SCOPES = Set.of("CHANGELOG", "DATABASE", "BOTH");
    private static final Set<String> OPERATORS = Set.of("STARTS_WITH", "CONTAINS", "ENDS_WITH", "REGEXP", "ALL");
    private static final Set<String> STATEMENTS = Set.of("ALL", "CREATE_TABLE", "ALTER_TABLE", "DROP", "GRANT", "INSERT",
            "UPDATE", "DELETE", "CREATE_INDEX", "CREATE_VIEW", "TRUNCATE");
    private static final Set<String> APPLIES_TO = Set.of("SQL", "COMMENT", "ID");
    private static final Set<String> CONSTRAINTS = Set.of("NOT_NULL", "UNIQUE", "PRIMARYKEY", "FOREIGNKEY");

    private static final Map<String, String> SCHEMAS = Map.of(
            "REGEX", "{\"type\":\"object\",\"required\":[\"pattern\"],\"properties\":{"
                    + "\"pattern\":{\"type\":\"string\"},\"appliesTo\":{\"enum\":[\"SQL\",\"COMMENT\",\"ID\"]},"
                    + "\"mustMatch\":{\"type\":\"boolean\"}}}",
            "SQL_PATTERN", "{\"type\":\"object\",\"properties\":{\"statementType\":{\"type\":\"string\"},"
                    + "\"operator\":{\"enum\":[\"STARTS_WITH\",\"CONTAINS\",\"ENDS_WITH\",\"REGEXP\",\"ALL\"]},"
                    + "\"tables\":{\"type\":\"string\"},\"columns\":{\"type\":\"string\"},"
                    + "\"requiredConstraints\":{\"type\":\"array\",\"items\":{\"enum\":[\"NOT_NULL\",\"UNIQUE\",\"PRIMARYKEY\",\"FOREIGNKEY\"]}}}}",
            "SPEL", "{\"type\":\"object\",\"required\":[\"expression\"],\"properties\":{\"expression\":{\"type\":\"string\"}}}",
            "CHAINED", "{\"type\":\"object\",\"properties\":{}}");

    /** What the screen sends. Every field is optional on update; on create the ones that matter are checked. */
    record CheckBody(String code, String shortName, String description, String category, String scope, String kind,
                     Integer defaultSeverity, Map<String, Object> parameters, String messageTemplate,
                     String changelogTypes, List<String> members, Boolean enabled) {
    }

    record CopyBody(String code) {
    }

    record TestBody(String changelogContent, String format, String dialect, String environment, Boolean production,
                    Integer severity, Map<String, Object> parameters) {
    }

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final AuditService audit;
    private final ChangelogDryRun dryRun;

    PolicyCheckAdminController(JdbcClient jdbc, WorkspaceContext workspaces, AuditService audit,
                               ChangelogDryRun dryRun) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.audit = audit;
        this.dryRun = dryRun;
    }

    // ------------------------------------------------------------------ create

    @PostMapping
    @Transactional
    ResponseEntity<Map<String, Object>> create(
            @RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
            Authentication authentication, @RequestBody CheckBody body) {
        UUID workspace = workspaces.resolve(ws);
        String code = requireCode(body.code());
        assertCodeFree(workspace, code);
        String kind = body.kind() == null ? "" : body.kind().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(kind)) {
            throw LqmException.invalid("kind must be one of " + String.join(", ", KINDS));
        }
        Map<String, Object> parameters = body.parameters() == null ? Map.of() : body.parameters();
        validate(kind, parameters, body.members(), workspace);
        int severity = severity(body.defaultSeverity(), Severity.MAJOR);
        String scope = body.scope() == null ? "CHANGELOG" : body.scope().toUpperCase(Locale.ROOT);
        if (!SCOPES.contains(scope)) {
            throw LqmException.invalid("scope must be one of " + String.join(", ", SCOPES));
        }
        String shortName = body.shortName() == null || body.shortName().isBlank() ? code : body.shortName().trim();
        if (body.category() == null || body.category().isBlank()) {
            throw LqmException.invalid("category is required");
        }

        UUID id = UuidV7.next();
        jdbc.sql("""
                insert into policy_check (id, workspace_id, code, short_name, description, category, scope,
                    changelog_types, kind, default_severity, parameters_schema, parameters, message_template,
                    enabled, updated_by, updated_at)
                values (:id, :ws, :code, :name, :description, :category, :scope, :types, :kind, :severity,
                    cast(:schema as jsonb), cast(:parameters as jsonb), :message, :enabled, :by, now())
                """)
                .param("id", id).param("ws", workspace).param("code", code).param("name", shortName)
                .param("description", body.description()).param("category", body.category().trim())
                .param("scope", scope)
                .param("types", body.changelogTypes() == null ? "XML,SQL,YAML,JSON" : body.changelogTypes())
                .param("kind", kind).param("severity", severity).param("schema", SCHEMAS.get(kind))
                .param("parameters", JSON.writeValueAsString(parameters))
                .param("message", body.messageTemplate() == null ? "" : body.messageTemplate())
                .param("enabled", body.enabled() == null || body.enabled())
                .param("by", AuditService.actorOf(authentication)).update();
        setMembers(id, kind, body.members(), workspace);

        Map<String, Object> created = snapshot(id);
        audit.record(workspace, AuditService.actorOf(authentication), "policy.create", "policy-check",
                id.toString(), null, created);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ------------------------------------------------------------------ update

    @PutMapping("/{id}")
    @Transactional
    Map<String, Object> update(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                               Authentication authentication, @PathVariable UUID id, @RequestBody CheckBody body) {
        UUID workspace = workspaces.resolve(ws);
        Map<String, Object> current = owned(workspace, id);
        String kind = (String) current.get("kind");
        if (!KINDS.contains(kind) && !"BUILTIN".equals(kind)) {
            throw LqmException.invalid("unknown check kind: " + kind);
        }
        Map<String, Object> before = snapshot(id);

        Map<String, Object> parameters = body.parameters() != null ? body.parameters() : parse((String) current.get("parameters"));
        List<String> members = body.members() != null ? body.members() : memberCodes(id);
        // A builtin copy keeps its parameters free-form: the parent's logic reads whatever it needs.
        if (!"BUILTIN".equals(kind)) {
            validate(kind, parameters, members, workspace);
        }
        int severity = severity(body.defaultSeverity(), ((Number) current.get("default_severity")).intValue());
        String scope = body.scope() == null ? (String) current.get("scope") : body.scope().toUpperCase(Locale.ROOT);
        if (!SCOPES.contains(scope)) {
            throw LqmException.invalid("scope must be one of " + String.join(", ", SCOPES));
        }

        jdbc.sql("""
                update policy_check set short_name = :name, description = :description, category = :category,
                    scope = :scope, changelog_types = :types, default_severity = :severity,
                    parameters = cast(:parameters as jsonb), message_template = :message, enabled = :enabled,
                    updated_by = :by, updated_at = now()
                 where id = :id
                """)
                .param("id", id)
                .param("name", orElse(body.shortName(), (String) current.get("short_name")))
                .param("description", body.description() != null ? body.description() : current.get("description"))
                .param("category", orElse(body.category(), (String) current.get("category")))
                .param("scope", scope)
                .param("types", orElse(body.changelogTypes(), (String) current.get("changelog_types")))
                .param("severity", severity)
                .param("parameters", JSON.writeValueAsString(parameters))
                .param("message", body.messageTemplate() != null ? body.messageTemplate() : current.get("message_template"))
                .param("enabled", body.enabled() != null ? body.enabled() : current.get("enabled"))
                .param("by", AuditService.actorOf(authentication)).update();
        if (body.members() != null) {
            setMembers(id, kind, members, workspace);
        }

        Map<String, Object> after = snapshot(id);
        audit.record(workspace, AuditService.actorOf(authentication), "policy.update", "policy-check",
                id.toString(), before, after);
        return after;
    }

    // ------------------------------------------------------------------ delete

    @DeleteMapping("/{id}")
    @Transactional
    ResponseEntity<Void> delete(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                Authentication authentication, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        owned(workspace, id);
        List<String> packages = jdbc.sql("""
                select pp.name from policy_package_check ppc join policy_package pp on pp.id = ppc.package_id
                 where ppc.check_id = :id order by pp.name
                """).param("id", id).query(String.class).list();
        if (!packages.isEmpty()) {
            throw new LqmException(HttpStatus.CONFLICT, "LQM-409",
                    "the check is used by package(s) " + String.join(", ", packages) + "; remove it from them first");
        }
        long chains = jdbc.sql("select count(*) from policy_chain_member where member_id = :id")
                .param("id", id).query(Long.class).single();
        if (chains > 0) {
            throw new LqmException(HttpStatus.CONFLICT, "LQM-409", "the check is a member of a chained check");
        }
        Map<String, Object> before = snapshot(id);
        jdbc.sql("delete from policy_chain_member where chain_id = :id").param("id", id).update();
        jdbc.sql("delete from policy_check where id = :id").param("id", id).update();
        audit.record(workspace, AuditService.actorOf(authentication), "policy.delete", "policy-check",
                id.toString(), before, null);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------- copy

    @PostMapping("/{id}/copy")
    @Transactional
    ResponseEntity<Map<String, Object>> copy(
            @RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
            Authentication authentication, @PathVariable UUID id, @RequestBody(required = false) CopyBody body) {
        UUID workspace = workspaces.resolve(ws);
        var rows = jdbc.sql("""
                select * from policy_check where id = :id and (workspace_id is null or workspace_id = :ws)
                """).param("id", id).param("ws", workspace).query().listOfRows();
        if (rows.isEmpty()) {
            throw LqmException.notFound("policy check");
        }
        Map<String, Object> source = rows.get(0);

        String code = body == null || body.code() == null || body.code().isBlank()
                ? freeCode(workspace, source.get("code") + "Custom") : requireCode(body.code());
        assertCodeFree(workspace, code);

        // A copy of a copy still runs the ROOT builtin's logic: the engine finds the implementation by that code.
        Object parent = source.get("parent_id") != null ? source.get("parent_id") : source.get("id");
        UUID copyId = UuidV7.next();
        jdbc.sql("""
                insert into policy_check (id, workspace_id, code, short_name, description, category, scope,
                    changelog_types, kind, default_severity, parameters_schema, parameters, message_template,
                    enabled, parent_id, updated_by, updated_at)
                select :copy, :ws, :code, short_name, description, category, scope, changelog_types, kind,
                       default_severity, parameters_schema, parameters, message_template, true, :parent, :by, now()
                  from policy_check where id = :source
                """)
                .param("copy", copyId).param("ws", workspace).param("code", code).param("parent", parent)
                .param("by", AuditService.actorOf(authentication)).param("source", id).update();
        jdbc.sql("""
                insert into policy_chain_member (chain_id, member_id)
                select :copy, member_id from policy_chain_member where chain_id = :source
                """).param("copy", copyId).param("source", id).update();

        Map<String, Object> created = snapshot(copyId);
        audit.record(workspace, AuditService.actorOf(authentication), "policy.copy", "policy-check",
                copyId.toString(), Map.of("copiedFrom", String.valueOf(source.get("code"))), created);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // -------------------------------------------------------------------- test

    @PostMapping("/{id}/test")
    Map<String, Object> test(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                             @PathVariable UUID id, @RequestBody TestBody body) {
        UUID workspace = workspaces.resolve(ws);
        var rows = jdbc.sql("""
                select pc.code, pc.kind, pc.default_severity, cast(pc.parameters as text) as parameters,
                       pc.message_template, parent.code as parent_code
                  from policy_check pc left join policy_check parent on parent.id = pc.parent_id
                 where pc.id = :id and (pc.workspace_id is null or pc.workspace_id = :ws)
                """).param("id", id).param("ws", workspace).query().listOfRows();
        if (rows.isEmpty()) {
            throw LqmException.notFound("policy check");
        }
        Map<String, Object> row = rows.get(0);
        String kind = (String) row.get("kind");
        Map<String, Object> parameters = body.parameters() != null ? body.parameters() : parse((String) row.get("parameters"));
        int severity = severity(body.severity(), ((Number) row.get("default_severity")).intValue());

        List<EffectiveCheck> members = new ArrayList<>();
        if ("CHAINED".equals(kind)) {
            jdbc.sql("""
                    select pc.code, pc.kind, pc.default_severity, cast(pc.parameters as text) as parameters,
                           pc.message_template, parent.code as parent_code
                      from policy_chain_member m join policy_check pc on pc.id = m.member_id
                      left join policy_check parent on parent.id = pc.parent_id
                     where m.chain_id = :id
                    """).param("id", id).query().listOfRows().forEach(m -> members.add(new EffectiveCheck(
                    (String) m.get("code"), ((Number) m.get("default_severity")).intValue(),
                    parse((String) m.get("parameters")), (String) m.get("kind"),
                    "BUILTIN".equals(m.get("kind")) ? (String) m.get("parent_code") : null,
                    (String) m.get("message_template"), List.of())));
        }
        EffectiveCheck check = new EffectiveCheck((String) row.get("code"), severity, parameters, kind,
                "BUILTIN".equals(kind) ? (String) row.get("parent_code") : null,
                (String) row.get("message_template"), members);

        ChangelogDryRun.Result result = dryRun.run(body.changelogContent(), body.format(), body.dialect(),
                body.environment(), Boolean.TRUE.equals(body.production()), check);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gate", result.gate());
        out.put("changesets", result.changesets());
        out.put("findings", result.evaluation().findings().stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("check", f.checkCode());
            m.put("severity", f.severity());
            m.put("changeset", f.changesetKey());
            m.put("message", f.message());
            return m;
        }).toList());
        return out;
    }

    // -------------------------------------------------------------- validation

    private void validate(String kind, Map<String, Object> parameters, List<String> members, UUID workspace) {
        switch (kind) {
            case "REGEX" -> {
                Object pattern = parameters.get("pattern");
                if (pattern == null || pattern.toString().isBlank()) {
                    throw LqmException.invalid("parameter pattern is required");
                }
                try {
                    Pattern.compile(pattern.toString());
                } catch (PatternSyntaxException e) {
                    throw LqmException.invalid("pattern is not a valid regular expression: " + e.getDescription());
                }
                oneOf(parameters, "appliesTo", APPLIES_TO);
            }
            case "SQL_PATTERN" -> {
                oneOf(parameters, "statementType", STATEMENTS);
                oneOf(parameters, "operator", OPERATORS);
                if ("REGEXP".equalsIgnoreCase(String.valueOf(parameters.get("operator"))) && parameters.get("tables") != null) {
                    for (String table : parameters.get("tables").toString().split(",")) {
                        try {
                            Pattern.compile(table.trim());
                        } catch (PatternSyntaxException e) {
                            throw LqmException.invalid("tables has an invalid regular expression: " + e.getDescription());
                        }
                    }
                }
                Object required = parameters.get("requiredConstraints");
                if (required instanceof Iterable<?> items) {
                    for (Object item : items) {
                        if (!CONSTRAINTS.contains(String.valueOf(item).toUpperCase(Locale.ROOT))) {
                            throw LqmException.invalid("requiredConstraints accepts " + String.join(", ", CONSTRAINTS));
                        }
                    }
                }
            }
            case "SPEL" -> {
                if (parameters.get("expression") == null || parameters.get("expression").toString().isBlank()) {
                    throw LqmException.invalid("parameter expression is required");
                }
                String problem = CustomChecks.spelProblem(parameters.get("expression").toString());
                if (problem != null) {
                    throw LqmException.invalid("parameter expression: " + problem);
                }
            }
            case "CHAINED" -> {
                if (members == null || members.isEmpty()) {
                    throw LqmException.invalid("a chained check needs at least one member");
                }
                for (String member : members) {
                    long found = jdbc.sql("""
                            select count(*) from policy_check
                             where code = :code and (workspace_id is null or workspace_id = :ws) and kind <> 'CHAINED'
                            """).param("code", member).param("ws", workspace).query(Long.class).single();
                    if (found == 0) {
                        throw LqmException.invalid("chain member not found (or itself chained): " + member);
                    }
                }
            }
            default -> throw LqmException.invalid("unsupported kind " + kind);
        }
    }

    private static void oneOf(Map<String, Object> parameters, String name, Set<String> allowed) {
        Object value = parameters.get(name);
        if (value != null && !allowed.contains(value.toString().toUpperCase(Locale.ROOT))) {
            throw LqmException.invalid("parameter " + name + " must be one of " + String.join(", ", allowed));
        }
    }

    private static int severity(Integer requested, int fallback) {
        int value = requested == null ? fallback : requested;
        if (value < Severity.INFO || value > Severity.BLOCKER) {
            throw LqmException.invalid("severity must be between 0 and 4");
        }
        return value;
    }

    private static String requireCode(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw LqmException.invalid("code must start with a letter and use only letters and digits (3-80)");
        }
        return code;
    }

    private void assertCodeFree(UUID workspace, String code) {
        long clash = jdbc.sql("select count(*) from policy_check where code = :code and (workspace_id is null or workspace_id = :ws)")
                .param("code", code).param("ws", workspace).query(Long.class).single();
        if (clash > 0) {
            throw new LqmException(HttpStatus.CONFLICT, "LQM-409", "a check with code " + code + " already exists");
        }
    }

    private String freeCode(UUID workspace, String base) {
        String code = base;
        for (int n = 2; n < 100; n++) {
            long clash = jdbc.sql("select count(*) from policy_check where code = :code and (workspace_id is null or workspace_id = :ws)")
                    .param("code", code).param("ws", workspace).query(Long.class).single();
            if (clash == 0) {
                return code;
            }
            code = base + n;
        }
        throw LqmException.invalid("could not find a free code based on " + base);
    }

    // ----------------------------------------------------------------- helpers

    /** The check, only when it belongs to the workspace: a builtin is never edited in place. */
    private Map<String, Object> owned(UUID workspace, UUID id) {
        var rows = jdbc.sql("""
                select *, cast(parameters as text) as parameters_text from policy_check
                 where id = :id and (workspace_id is null or workspace_id = :ws)
                """).param("id", id).param("ws", workspace).query().listOfRows();
        if (rows.isEmpty()) {
            throw LqmException.notFound("policy check");
        }
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        if (row.get("workspace_id") == null) {
            throw new LqmException(HttpStatus.CONFLICT, "LQM-409",
                    "a builtin check cannot be changed or deleted; copy it and edit the copy");
        }
        row.put("parameters", row.get("parameters_text"));
        return row;
    }

    private void setMembers(UUID chainId, String kind, List<String> members, UUID workspace) {
        jdbc.sql("delete from policy_chain_member where chain_id = :id").param("id", chainId).update();
        if (!"CHAINED".equals(kind) || members == null) {
            return;
        }
        for (String code : members) {
            jdbc.sql("""
                    insert into policy_chain_member (chain_id, member_id)
                    select :chain, id from policy_check
                     where code = :code and (workspace_id is null or workspace_id = :ws)
                    """).param("chain", chainId).param("code", code).param("ws", workspace).update();
        }
    }

    private List<String> memberCodes(UUID chainId) {
        return jdbc.sql("""
                select pc.code from policy_chain_member m join policy_check pc on pc.id = m.member_id
                 where m.chain_id = :id order by pc.code
                """).param("id", chainId).query(String.class).list();
    }

    private Map<String, Object> snapshot(UUID id) {
        var row = jdbc.sql("""
                select id, code, short_name, description, category, scope, changelog_types, kind, default_severity,
                       cast(parameters as text) as parameters, message_template, enabled, parent_id
                  from policy_check where id = :id
                """).param("id", id).query().singleRow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("code", row.get("code"));
        out.put("shortName", row.get("short_name"));
        out.put("description", row.get("description"));
        out.put("category", row.get("category"));
        out.put("scope", row.get("scope"));
        out.put("changelogTypes", row.get("changelog_types"));
        out.put("kind", row.get("kind"));
        out.put("defaultSeverity", row.get("default_severity"));
        out.put("parameters", parse((String) row.get("parameters")));
        out.put("messageTemplate", row.get("message_template"));
        out.put("enabled", row.get("enabled"));
        out.put("parentId", row.get("parent_id"));
        if ("CHAINED".equals(row.get("kind"))) {
            out.put("members", memberCodes(id));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        return json == null || json.isBlank() ? Map.of() : JSON.readValue(json, Map.class);
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
