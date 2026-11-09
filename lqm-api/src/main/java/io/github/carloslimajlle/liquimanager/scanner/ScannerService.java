package io.github.carloslimajlle.liquimanager.scanner;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import io.github.carloslimajlle.liquimanager.drift.TargetConnections;
import io.github.carloslimajlle.liquimanager.ingest.IngestOperation;
import io.github.carloslimajlle.liquimanager.ingest.IngestService;
import io.github.carloslimajlle.liquimanager.security.ServiceToken;
import liquibase.database.Database;

/**
 * The agentless scanner (docs/07 § 4): reads each monitored database's own {@code DATABASECHANGELOG} and
 * reports what was applied WITHOUT going through LiquiManager.
 *
 * <p>A deployment (a group of rows with the same {@code DEPLOYMENT_ID}) that no known operation accounts for
 * becomes an {@code observed} operation from the source {@code scanner}, with an {@code OutOfProcessChange}
 * finding: somebody ran Liquibase by hand, or a collector never reported. It goes through the ordinary
 * ingestion, so the matrix, the dashboard and the stream treat it like any other operation.
 *
 * <p>The first pass over a database has no "last seen" to start from. It imports the history so the matrix
 * knows what ran, but WITHOUT the finding: calling every deployment since the dawn of time "out of process"
 * would bury the ones that matter. After that, only what is new is judged.
 *
 * <p>It also looks at {@code DATABASECHANGELOGLOCK}: a lock held for more than 15 minutes is a deploy that died
 * with the lock in hand and blocks every later one.
 */
@Service
public class ScannerService {

    private static final Logger LOG = LoggerFactory.getLogger(ScannerService.class);
    static final Duration STUCK_LOCK = Duration.ofMinutes(15);

    /** One row of the monitored database's DATABASECHANGELOG. */
    record Row(String id, String author, String filename, Instant executedAt, int order, String execType, String checksum,
               String tag, String contexts, String labels, String deploymentId) {
    }

    /** What a pass found, for the job history and the status endpoint. */
    public record Result(boolean ok, int deployments, int outOfProcess, boolean firstPass, Instant stuckLockSince, String error) {
    }

    private final JdbcClient jdbc;
    private final TargetConnections targets;
    private final IngestService ingest;

    ScannerService(JdbcClient jdbc, TargetConnections targets, IngestService ingest) {
        this.jdbc = jdbc;
        this.targets = targets;
        this.ingest = ingest;
    }

    public Result scan(UUID workspace, UUID connectionId) {
        Instant now = Instant.now();
        var connection = jdbc.sql("select slug, jdbc_url from db_connection where id = :id and workspace_id = :ws")
                .param("id", connectionId).param("ws", workspace).query().listOfRows().stream().findFirst().orElseThrow();
        Optional<Timestamp> lastSeen = jdbc.sql("select last_seen from scanner_state where db_connection_id = :id")
                .param("id", connectionId).query((rs, n) -> rs.getTimestamp(1)).list().stream().filter(java.util.Objects::nonNull).findFirst();

        List<Row> rows;
        Instant stuckSince = null;
        try (TargetConnections.Target target = targets.open(workspace, connectionId)) {
            rows = read(target, lastSeen.map(Timestamp::toInstant).orElse(null));
            stuckSince = stuckLock(target, now);
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            LOG.warn("scanner could not read {}: {}", connection.get("slug"), error);
            saveState(connectionId, lastSeen.map(Timestamp::toInstant).orElse(null), now, false, error, null);
            return new Result(false, 0, 0, lastSeen.isEmpty(), null, error);
        }

        boolean firstPass = lastSeen.isEmpty();
        Map<String, List<Row>> deployments = new LinkedHashMap<>();
        for (Row row : rows) {
            deployments.computeIfAbsent(row.deploymentId() == null ? "legacy-" + row.executedAt().atZone(ZoneOffset.UTC).toLocalDate()
                    : row.deploymentId(), k -> new ArrayList<>()).add(row);
        }

        int outOfProcess = 0;
        Instant newest = lastSeen.map(Timestamp::toInstant).orElse(null);
        for (Map.Entry<String, List<Row>> deployment : deployments.entrySet()) {
            List<Row> group = deployment.getValue();
            for (Row row : group) {
                if (newest == null || row.executedAt().isAfter(newest)) {
                    newest = row.executedAt();
                }
            }
            if (known(connectionId, deployment.getKey())) {
                continue;
            }
            report(workspace, connectionId, (String) connection.get("slug"), (String) connection.get("jdbc_url"),
                    deployment.getKey(), group, !firstPass);
            if (!firstPass) {
                outOfProcess++;
            }
        }
        saveState(connectionId, newest == null ? now : newest, now, true, null, stuckSince);
        return new Result(true, deployments.size(), outOfProcess, firstPass, stuckSince, null);
    }

    // -------------------------------------------------------------------- reading

    private List<Row> read(TargetConnections.Target target, Instant after) throws SQLException {
        Database database = target.database();
        String table = database.escapeTableName(null, target.primarySchema(), database.getDatabaseChangeLogTableName());
        String sql = "select ID, AUTHOR, FILENAME, DATEEXECUTED, ORDEREXECUTED, EXECTYPE, MD5SUM, TAG, CONTEXTS, LABELS, DEPLOYMENT_ID "
                + "from " + table + (after == null ? "" : " where DATEEXECUTED > ?") + " order by DATEEXECUTED, ORDEREXECUTED";
        List<Row> rows = new ArrayList<>();
        Connection connection = target.jdbc();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (after != null) {
                statement.setTimestamp(1, Timestamp.from(after));
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getInt(5),
                            rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11)));
                }
            }
        }
        return rows;
    }

    /** Since when the lock has been held, if it has been held for longer than the limit. */
    private static Instant stuckLock(TargetConnections.Target target, Instant now) {
        Database database = target.database();
        String table = database.escapeTableName(null, target.primarySchema(), database.getDatabaseChangeLogLockTableName());
        try (PreparedStatement statement = target.jdbc().prepareStatement("select LOCKED, LOCKGRANTED from " + table + " where ID = 1");
             ResultSet rs = statement.executeQuery()) {
            if (rs.next() && rs.getBoolean(1) && rs.getTimestamp(2) != null) {
                Instant granted = rs.getTimestamp(2).toInstant();
                return Duration.between(granted, now).compareTo(STUCK_LOCK) > 0 ? granted : null;
            }
        } catch (SQLException e) {
            // No lock table (an old or odd database) is not a stuck lock.
            return null;
        }
        return null;
    }

    // ------------------------------------------------------------------- reporting

    /** Is there already an operation for this deployment on this database? */
    private boolean known(UUID connectionId, String deploymentId) {
        return jdbc.sql("select count(*) from operation where db_connection_id = :c and deployment_id = :d")
                .param("c", connectionId).param("d", deploymentId).query(Long.class).single() > 0;
    }

    private void report(UUID workspace, UUID connectionId, String slug, String jdbcUrl, String deploymentId, List<Row> group,
                        boolean judge) {
        String project = projectOf(connectionId);
        String changelogPath = changelogOf(connectionId, group);
        int severity = jdbc.sql("select default_severity from policy_check where code = 'OutOfProcessChange' and workspace_id is null")
                .query(Integer.class).optional().orElse(3);

        List<IngestOperation.Changeset> changesets = new ArrayList<>();
        int order = 0;
        for (Row row : group) {
            changesets.add(new IngestOperation.Changeset(row.id(), row.author(), row.filename(), row.checksum(), row.labels(),
                    row.contexts(), null, "EXECUTED", null, ++order, row.executedAt(), null, null));
        }
        Instant started = group.get(0).executedAt();
        Instant ended = group.get(group.size() - 1).executedAt();
        IngestOperation.Policy policy = judge
                ? new IngestOperation.Policy("WARNED", group.stream().map(row -> new IngestOperation.Finding(
                        "OutOfProcessChange", severity, "WARNED", row.id(),
                        "O changeset " + row.id() + " foi aplicado fora do processo: nenhuma operacao conhecida o executou."))
                        .toList())
                : null;

        IngestOperation operation = new IngestOperation(
                UUID.nameUUIDFromBytes((connectionId + ":" + deploymentId).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "observed", "SUCCESS", "scanner", "scanner", started, ended, project, null, deploymentId, null, null, null,
                new IngestOperation.Connection(slug, jdbcUrl, null, null),
                new IngestOperation.Changelog(changelogPath, "XML", null), null, null,
                new IngestOperation.Runtime(null, "lqm-scanner", null, null, null),
                new IngestOperation.Summary(changesets.size(), 0, 0, 0), changesets, policy, null, null, null, null);
        ingest.ingest(new ServiceToken(null, workspace, "scanner", List.of("ingest"), List.of()), operation);
    }

    /** The project that owns this database, from its pipeline; failing that, a holding project for what nobody claimed. */
    private String projectOf(UUID connectionId) {
        return jdbc.sql("""
                select p.slug from pipeline_stage ps join pipeline pl on pl.id = ps.pipeline_id join project p on p.id = pl.project_id
                 where ps.db_connection_id = :c order by pl.name limit 1
                """).param("c", connectionId).query(String.class).optional().orElse("unassigned");
    }

    /** The changelog that already knows these changesets, or a placeholder for the ones nobody has seen. */
    private String changelogOf(UUID connectionId, List<Row> group) {
        for (Row row : group) {
            Optional<String> path = jdbc.sql("""
                    select cl.path from changeset cs join changelog cl on cl.id = cs.changelog_id
                      join pipeline pl on pl.project_id = cl.project_id join pipeline_stage ps on ps.pipeline_id = pl.id
                     where ps.db_connection_id = :c and cs.cs_id = :id and cs.author = :author and cs.file_path = :fp limit 1
                    """).param("c", connectionId).param("id", row.id()).param("author", row.author()).param("fp", row.filename())
                    .query(String.class).optional();
            if (path.isPresent()) {
                return path.get();
            }
        }
        return "observed";
    }

    private void saveState(UUID connectionId, Instant lastSeen, Instant ranAt, boolean ok, String error, Instant stuckSince) {
        jdbc.sql("""
                insert into scanner_state (db_connection_id, last_seen, last_run_at, last_ok, last_error, stuck_lock_since)
                values (:c, :seen, :at, :ok, :error, :stuck)
                on conflict (db_connection_id) do update set last_seen = excluded.last_seen, last_run_at = excluded.last_run_at,
                    last_ok = excluded.last_ok, last_error = excluded.last_error, stuck_lock_since = excluded.stuck_lock_since
                """).param("c", connectionId).param("seen", lastSeen == null ? null : Timestamp.from(lastSeen))
                .param("at", Timestamp.from(ranAt)).param("ok", ok).param("error", error)
                .param("stuck", stuckSince == null ? null : Timestamp.from(stuckSince)).update();
    }
}
