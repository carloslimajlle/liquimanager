package io.github.carloslimajlle.liquimanager.matrix;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import io.github.carloslimajlle.liquimanager.common.Labels;
import io.github.carloslimajlle.liquimanager.common.LqmException;

/**
 * Builds the Deployment Matrix of a pipeline: every changeset against every stage
 * (docs/telas/04).
 *
 * <p>Known limits, stated so they are not mistaken for oversights:
 * <ul>
 *   <li>The changelog's own order is not stored anywhere, so rows follow the order in
 *       which changesets were first SEEN ({@code first_seen_at}), then their id. For a
 *       changelog whose changesets all arrive in one operation that is the file order
 *       only when the collector reports them in it.
 *   <li>"Failed, with a fix delivered" pairs a failure with any LATER break-fix
 *       changeset of the same changelog that ran in the same database. The data does
 *       not say which fix is meant for which failure, so this is a heuristic.
 *   <li>The executor behind a cell (tooltip) is not returned; the cell links to the
 *       operation, which has it.
 * </ul>
 */
@Service
public class MatrixService {

    private final JdbcClient jdbc;

    public MatrixService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Filter(String release, String q) {
    }

    private record Stage(UUID id, UUID connectionId, String environment, String environmentName,
                         String connection, int sortOrder) {
    }

    private record ChangesetRow(UUID id, UUID changelogId, String csId, String author, String filePath,
                                String labels, String contexts, boolean breakFix, Instant firstSeen, Instant removedAt,
                                boolean seenOnDefault, String originBranch, String originMrIid, String originMrTitle,
                                String originMrUrl) {

        /** No longer in the changelog on the default branch: shown only while some database still holds it. */
        boolean removed() {
            return removedAt != null;
        }

        /** Never seen on the default branch: it lives in a merge request or a feature branch. */
        boolean onlyInBranch() {
            return !seenOnDefault;
        }

        /** Not part of the changelog on the default branch (gone from it, or not there yet). */
        boolean outsideMain() {
            return removed() || onlyInBranch();
        }
    }

    private record State(String status, UUID operationId, Instant deployedAt, String deploymentId, String executedBy) {
    }

    public Map<String, Object> matrix(UUID workspace, UUID pipelineId, Filter filter, int page, int size) {
        Map<String, Object> pipeline = jdbc.sql("select id, project_id from pipeline where id = :id and workspace_id = :ws")
                .param("id", pipelineId).param("ws", workspace).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> LqmException.notFound("pipeline"));
        UUID projectId = (UUID) pipeline.get("project_id");

        List<Stage> stages = stages(pipelineId);
        List<ChangesetRow> changesets = changesets(projectId, stages, filter);
        Map<UUID, List<State>> states = states(stages, changesets);

        // Derive every row once; the KPIs need all of them, the page only a slice.
        List<List<String>> derived = new ArrayList<>(changesets.size());
        for (ChangesetRow row : changesets) {
            List<String> stored = new ArrayList<>(stages.size());
            for (int i = 0; i < stages.size(); i++) {
                State state = states.getOrDefault(row.id(), List.of()).get(i);
                stored.add(state == null ? null : state.status());
            }
            List<String> cells = CellDerivation.derive(stored);
            if (row.outsideMain()) {
                // Nothing is promoted from a file that is gone, or that no merge has brought yet: no "next stop" for it.
                cells = cells.stream().map(c -> CellDerivation.PENDING.equals(c) ? CellDerivation.NOT_REACHED : c).toList();
            }
            derived.add(cells);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stages", stages.stream().map(s -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stageId", s.id());
            item.put("env", s.environment());
            item.put("envName", s.environmentName());
            item.put("connection", s.connection());
            return item;
        }).toList());
        out.put("kpis", kpis(stages, changesets, derived, states));
        out.put("removedCount", changesets.stream().filter(ChangesetRow::removed).count());

        int from = Math.min(page * size, changesets.size());
        int to = Math.min(from + size, changesets.size());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int r = from; r < to; r++) {
            rows.add(row(changesets.get(r), derived.get(r), stages, states.getOrDefault(changesets.get(r).id(), List.of())));
        }
        out.put("rows", rows);
        out.put("page", page);
        out.put("size", size);
        out.put("totalElements", changesets.size());
        return out;
    }

    // -------------------------------------------------------------------- data

    private List<Stage> stages(UUID pipelineId) {
        return jdbc.sql("""
                select ps.id, ps.db_connection_id, ps.sort_order, e.code, e.name as environment_name, c.slug
                  from pipeline_stage ps
                  join environment e on e.id = ps.environment_id
                  join db_connection c on c.id = ps.db_connection_id
                 where ps.pipeline_id = :p order by ps.sort_order
                """).param("p", pipelineId)
                .query((rs, n) -> new Stage(rs.getObject("id", UUID.class),
                        rs.getObject("db_connection_id", UUID.class), rs.getString("code"),
                        rs.getString("environment_name"), rs.getString("slug"), rs.getInt("sort_order")))
                .list();
    }

    /**
     * The changesets the pipeline is about: those of its project's changelogs. A pipeline
     * with no project falls back to whatever has any recorded state in its databases.
     */
    private List<ChangesetRow> changesets(UUID projectId, List<Stage> stages, Filter filter) {
        if (stages.isEmpty()) {
            return List.of();
        }
        List<String> clauses = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        if (projectId != null) {
            clauses.add("cl.project_id = :project");
            params.put("project", projectId);
        } else {
            clauses.add("cs.id in (select changeset_id from changeset_state where db_connection_id in (:conns))");
            params.put("conns", stages.stream().map(Stage::connectionId).toList());
        }
        if (filter.release() != null && !filter.release().isBlank()) {
            // Exact label match inside a comma list, not a substring: release-2.1 must not hit release-2.10.
            clauses.add("(',' || replace(coalesce(cs.labels, ''), ' ', '') || ',') like :release");
            params.put("release", "%," + filter.release().trim() + ",%");
        }
        if (filter.q() != null && !filter.q().isBlank()) {
            clauses.add("(lower(cs.cs_id) like :q or lower(cs.author) like :q or lower(cs.file_path) like :q)");
            params.put("q", "%" + filter.q().trim().toLowerCase(Locale.ROOT) + "%");
        }
        // A changeset gone from the changelog only matters while some database of the pipeline still holds it.
        clauses.add("((cs.removed_at is null and cs.seen_on_default_at is not null) or exists (select 1 from changeset_state held"
                + " where held.changeset_id = cs.id and held.status = 'EXECUTED' and held.db_connection_id in (:stageConnections)))");
        params.put("stageConnections", stages.stream().map(Stage::connectionId).toList());
        return jdbc.sql("""
                select cs.id, cs.changelog_id, cs.cs_id, cs.author, cs.file_path, cs.labels, cs.contexts,
                       cs.is_break_fix, cs.first_seen_at, cs.removed_at, cs.seen_on_default_at is not null as seen_on_default,
                       cs.origin_branch, cs.origin_mr_iid, cs.origin_mr_title, cs.origin_mr_url
                  from changeset cs join changelog cl on cl.id = cs.changelog_id
                 where %s
                 order by cs.first_seen_at, cs.cs_id
                """.formatted(String.join(" and ", clauses)))
                .params(params)
                .query((rs, n) -> new ChangesetRow(rs.getObject("id", UUID.class),
                        rs.getObject("changelog_id", UUID.class), rs.getString("cs_id"), rs.getString("author"),
                        rs.getString("file_path"), rs.getString("labels"), rs.getString("contexts"),
                        rs.getBoolean("is_break_fix"), rs.getTimestamp("first_seen_at").toInstant(),
                        rs.getTimestamp("removed_at") == null ? null : rs.getTimestamp("removed_at").toInstant(),
                        rs.getBoolean("seen_on_default"), rs.getString("origin_branch"), rs.getString("origin_mr_iid"),
                        rs.getString("origin_mr_title"), rs.getString("origin_mr_url")))
                .list();
    }

    /** changeset id -> one {@link State} per stage (null where nothing is known), in stage order. */
    private Map<UUID, List<State>> states(List<Stage> stages, List<ChangesetRow> changesets) {
        Map<UUID, List<State>> out = new HashMap<>();
        if (stages.isEmpty() || changesets.isEmpty()) {
            return out;
        }
        Map<UUID, Integer> stageByConnection = new HashMap<>();
        for (int i = 0; i < stages.size(); i++) {
            stageByConnection.putIfAbsent(stages.get(i).connectionId(), i);
        }
        for (ChangesetRow row : changesets) {
            List<State> slots = new ArrayList<>(stages.size());
            for (int i = 0; i < stages.size(); i++) {
                slots.add(null);
            }
            out.put(row.id(), slots);
        }

        List<UUID> ids = changesets.stream().map(ChangesetRow::id).toList();
        List<UUID> connections = stages.stream().map(Stage::connectionId).distinct().toList();
        jdbc.sql("""
                select s.changeset_id, s.db_connection_id, s.status, s.last_operation_id, s.deployed_at, s.deployment_id,
                       (select o.executed_by from operation o where o.id = s.last_operation_id limit 1) as executed_by
                  from changeset_state s
                 where s.db_connection_id in (:conns) and s.changeset_id in (:ids)
                """).param("conns", connections).param("ids", ids)
                .query().listOfRows().forEach(r -> {
                    Integer stage = stageByConnection.get((UUID) r.get("db_connection_id"));
                    List<State> slots = out.get((UUID) r.get("changeset_id"));
                    if (stage != null && slots != null) {
                        Timestamp deployed = (Timestamp) r.get("deployed_at");
                        slots.set(stage, new State((String) r.get("status"), (UUID) r.get("last_operation_id"),
                                deployed == null ? null : deployed.toInstant(), (String) r.get("deployment_id"), (String) r.get("executed_by")));
                    }
                });
        return out;
    }

    // --------------------------------------------------------------------- KPIs

    private Map<String, Object> kpis(List<Stage> stages, List<ChangesetRow> changesets,
                                     List<List<String>> derived, Map<UUID, List<State>> states) {
        int inSync = 0;
        int awaiting = 0;
        int furthestPending = -1;
        int failedWithFix = 0;

        int live = 0;
        for (int r = 0; r < changesets.size(); r++) {
            if (changesets.get(r).outsideMain()) {
                continue;
            }
            live++;
            List<String> cells = derived.get(r);
            if (CellDerivation.inSync(cells)) {
                inSync++;
            }
            if (CellDerivation.awaitingPromotion(cells)) {
                awaiting++;
                furthestPending = Math.max(furthestPending, CellDerivation.mostAdvancedPending(cells));
            }
            if (failedWithFix(r, changesets, derived, states)) {
                failedWithFix++;
            }
        }

        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("inSyncAll", inSync);
        kpis.put("total", live);
        kpis.put("environments", stages.size());
        kpis.put("awaitingPromotion", awaiting);
        kpis.put("awaitingPromotionEnvironment", furthestPending < 0 ? null : stages.get(furthestPending).environment());
        kpis.put("failedWithFix", failedWithFix);
        return kpis;
    }

    /**
     * A changeset that FAILED somewhere, with a LATER break-fix changeset of the same
     * changelog that ran successfully in that same database. See the class note: the
     * pairing is a heuristic, because nothing links a fix to the failure it answers.
     */
    private boolean failedWithFix(int index, List<ChangesetRow> changesets, List<List<String>> derived,
                                  Map<UUID, List<State>> states) {
        ChangesetRow failed = changesets.get(index);
        List<String> cells = derived.get(index);
        for (int stage = 0; stage < cells.size(); stage++) {
            if (!CellDerivation.FAILED.equals(cells.get(stage))) {
                continue;
            }
            for (ChangesetRow candidate : changesets) {
                if (!candidate.breakFix() || !candidate.changelogId().equals(failed.changelogId())
                        || !candidate.firstSeen().isAfter(failed.firstSeen())) {
                    continue;
                }
                State fix = states.getOrDefault(candidate.id(), List.of()).get(stage);
                if (fix != null && CellDerivation.EXECUTED.equals(fix.status())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------- rows

    /** Where a changeset not on the default branch came from: the merge request when the pipeline said which, else the branch. */
    static Map<String, Object> origin(String branch, String mrIid, String mrTitle, String mrUrl) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branch", branch);
        out.put("mrIid", mrIid);
        out.put("mrTitle", mrTitle);
        out.put("mrUrl", mrUrl);
        return out;
    }

    private Map<String, Object> row(ChangesetRow row, List<String> derived, List<Stage> stages, List<State> states) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changeset", row.csId());
        out.put("author", row.author());
        out.put("filePath", row.filePath());
        out.put("release", Labels.release(row.labels()));
        out.put("isBreakFix", row.breakFix());
        out.put("contexts", row.contexts());
        out.put("removed", row.removed());
        out.put("removedAt", row.removedAt());
        out.put("onlyInBranch", row.onlyInBranch());
        if (row.onlyInBranch()) {
            out.put("origin", origin(row.originBranch(), row.originMrIid(), row.originMrTitle(), row.originMrUrl()));
        }

        List<Map<String, Object>> cells = new ArrayList<>();
        for (int i = 0; i < stages.size(); i++) {
            State state = states.size() > i ? states.get(i) : null;
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("stageId", stages.get(i).id());
            cell.put("status", derived.get(i));
            if (state != null) {
                cell.put("operationId", state.operationId());
                cell.put("deployedAt", state.deployedAt());
                cell.put("deploymentId", state.deploymentId());
                cell.put("executedBy", state.executedBy());
            }
            cells.add(cell);
        }
        out.put("cells", cells);
        return out;
    }
}
