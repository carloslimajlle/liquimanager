package io.github.carloslimajlle.liquimanager.policies;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import io.github.carloslimajlle.liquimanager.common.LqmException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Resolves the policy package that applies to a project in an environment, and what
 * that package means right now (docs/06, {@code GET /policies/effective}).
 *
 * <p>Precedence, narrowest first: the pipeline STAGE for that project and environment,
 * then the CHANGELOG, then the workspace's {@code default} package. There is no
 * project-level rung, because nothing in the model gives a project a package of its
 * own — docs/06 lists one, docs/05 never stored it, so the code follows the schema.
 *
 * <p>This is what a collector fetches before every run, so it has to be cheap to
 * revalidate: the answer carries an ETag derived from its own content, and a collector
 * that already holds it gets a 304.
 */
@Service
public class PolicyResolver {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    public PolicyResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One check of the effective package. {@code implementation} is the builtin a copy runs;
     * {@code message} is the template of a custom check; {@code members} are the checks a
     * chain evaluates. All three are null when they do not apply, and the API omits nulls.
     */
    public record CheckEntry(String code, String kind, int severity, Map<String, Object> parameters,
                             String implementation, String message, List<CheckEntry> members) {
    }

    public record ExceptionEntry(String check, String changeset, Instant expiresAt) {
    }

    public record Effective(String packageName, String etag, int blockThreshold,
                            List<CheckEntry> checks, List<ExceptionEntry> exceptions) {
    }

    private record Environment(UUID id, boolean production) {
    }

    private record PackageRow(UUID id, String name, int thresholdProd, int thresholdNonProd) {
    }

    public Effective resolve(UUID workspace, String projectSlug, String envCode, String changelogPath, Instant now) {
        Environment env = jdbc.sql("select id, is_production from environment where workspace_id = :ws and code = :code")
                .param("ws", workspace).param("code", envCode == null ? "" : envCode.toUpperCase())
                .query((rs, n) -> new Environment(rs.getObject("id", UUID.class), rs.getBoolean("is_production")))
                .optional()
                .orElseThrow(() -> LqmException.invalid("unknown environment: " + envCode));

        UUID project = projectSlug == null || projectSlug.isBlank() ? null
                : jdbc.sql("select id from project where workspace_id = :ws and slug = :slug")
                        .param("ws", workspace).param("slug", projectSlug).query(UUID.class).optional().orElse(null);

        PackageRow pkg = packageFor(workspace, project, env, changelogPath);
        if (pkg == null) {
            // A workspace with no default package has no rules yet. That is a state, not an
            // error: the collector gets an empty package and nothing is blocked.
            return seal(new Effective("none", null, env.production() ? 3 : 4, List.of(), List.of()));
        }

        int threshold = env.production() ? pkg.thresholdProd() : pkg.thresholdNonProd();
        return seal(new Effective(pkg.name(), null, threshold, checks(pkg.id()),
                exceptions(workspace, env, project, now)));
    }

    // ---------------------------------------------------------------- package

    private PackageRow packageFor(UUID workspace, UUID project, Environment env, String changelogPath) {
        UUID id = null;
        if (project != null) {
            id = jdbc.sql("""
                    select ps.policy_package_id from pipeline_stage ps join pipeline p on p.id = ps.pipeline_id
                     where p.workspace_id = :ws and p.project_id = :project and ps.environment_id = :env
                       and ps.policy_package_id is not null
                     order by p.name limit 1
                    """)
                    .param("ws", workspace).param("project", project).param("env", env.id())
                    .query(UUID.class).optional().orElse(null);
            if (id == null && changelogPath != null && !changelogPath.isBlank()) {
                id = jdbc.sql("select policy_package_id from changelog where project_id = :project and path = :path")
                        .param("project", project).param("path", changelogPath)
                        .query(UUID.class).optional().orElse(null);
            }
        }
        if (id == null) {
            id = jdbc.sql("select id from policy_package where workspace_id = :ws and name = 'default'")
                    .param("ws", workspace).query(UUID.class).optional().orElse(null);
        }
        if (id == null) {
            return null;
        }
        return jdbc.sql("select id, name, block_threshold_prod, block_threshold_nonprod from policy_package where id = :id")
                .param("id", id)
                .query((rs, n) -> new PackageRow(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getInt("block_threshold_prod"), rs.getInt("block_threshold_nonprod")))
                .single();
    }

    // ------------------------------------------------------------------ checks

    /**
     * The package's ENABLED checks, each with the severity and parameters that apply: the
     * package's override when it has one, the check's own value otherwise. A disabled check
     * is simply not in the answer, so a collector never even hears of it.
     */
    private List<CheckEntry> checks(UUID packageId) {
        List<CheckEntry> out = new ArrayList<>();
        jdbc.sql("""
                select pc.id, pc.code, pc.kind, coalesce(ppc.severity_override, pc.default_severity) as severity,
                       cast(pc.parameters as text) as parameters,
                       cast(ppc.parameters_override as text) as override,
                       pc.message_template, parent.code as parent_code
                  from policy_package_check ppc
                  join policy_check pc on pc.id = ppc.check_id
                  left join policy_check parent on parent.id = pc.parent_id
                 where ppc.package_id = :pkg and pc.enabled
                 order by pc.code
                """).param("pkg", packageId).query().listOfRows().forEach(row -> {
            Map<String, Object> parameters = new LinkedHashMap<>(parse((String) row.get("parameters")));
            parameters.putAll(parse((String) row.get("override")));
            String kind = (String) row.get("kind");
            // A customised copy of a builtin runs the parent's logic under its own name.
            String implementation = "BUILTIN".equals(kind) ? (String) row.get("parent_code") : null;
            String message = "BUILTIN".equals(kind) ? null : (String) row.get("message_template");
            List<CheckEntry> members = "CHAINED".equals(kind)
                    ? members((UUID) row.get("id")) : null;
            out.add(new CheckEntry((String) row.get("code"), kind, ((Number) row.get("severity")).intValue(),
                    parameters, implementation, message, members));
        });
        return out;
    }

    /** The members of a chain, each at its own default severity: the chain reports the worst of them. */
    private List<CheckEntry> members(UUID chainId) {
        List<CheckEntry> out = new ArrayList<>();
        jdbc.sql("""
                select pc.code, pc.kind, pc.default_severity, cast(pc.parameters as text) as parameters,
                       pc.message_template, parent.code as parent_code
                  from policy_chain_member m
                  join policy_check pc on pc.id = m.member_id
                  left join policy_check parent on parent.id = pc.parent_id
                 where m.chain_id = :chain and pc.enabled
                 order by pc.code
                """).param("chain", chainId).query().listOfRows().forEach(row -> {
            String kind = (String) row.get("kind");
            out.add(new CheckEntry((String) row.get("code"), kind, ((Number) row.get("default_severity")).intValue(),
                    parse((String) row.get("parameters")),
                    "BUILTIN".equals(kind) ? (String) row.get("parent_code") : null,
                    "BUILTIN".equals(kind) ? null : (String) row.get("message_template"), null));
        });
        return out;
    }

    // -------------------------------------------------------------- exceptions

    /**
     * Exceptions in force: APPROVED and not expired, for this environment (or any) and this
     * project (or any). Expiry is checked here AND by the engine: a collector that holds
     * the answer for a while must not keep honouring an exception that has lapsed.
     */
    private List<ExceptionEntry> exceptions(UUID workspace, Environment env, UUID project, Instant now) {
        return jdbc.sql("""
                select pc.code, cs.cs_id, e.expires_at
                  from policy_exception e
                  join policy_check pc on pc.id = e.check_id
                  left join changeset cs on cs.id = e.changeset_id
                 where e.workspace_id = :ws and e.status = 'APPROVED' and e.expires_at > :now
                   and (e.environment_id is null or e.environment_id = :env)
                   and (e.project_id is null or e.project_id = :project)
                 order by e.expires_at, pc.code
                """)
                .param("ws", workspace).param("now", java.time.OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
                .param("env", env.id()).param("project", project)
                .query().listOfRows().stream()
                .map(r -> new ExceptionEntry((String) r.get("code"), (String) r.get("cs_id"),
                        ((Timestamp) r.get("expires_at")).toInstant()))
                .toList();
    }

    // -------------------------------------------------------------------- etag

    /** The ETag is a hash of the answer's own content, so it changes exactly when the answer does. */
    private Effective seal(Effective unsealed) {
        try {
            String content = JSON.writeValueAsString(new Effective(unsealed.packageName(), null,
                    unsealed.blockThreshold(), unsealed.checks(), unsealed.exceptions()));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            String etag = "W/\"" + HexFormat.of().formatHex(digest, 0, 8) + "\"";
            return new Effective(unsealed.packageName(), etag, unsealed.blockThreshold(),
                    unsealed.checks(), unsealed.exceptions());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return JSON.readValue(json, Map.class);
    }
}
