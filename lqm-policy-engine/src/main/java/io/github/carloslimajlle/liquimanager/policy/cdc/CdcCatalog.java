package io.github.carloslimajlle.liquimanager.policy.cdc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads which tables SQL Server Change Data Capture tracks, from the catalogue of a database. Only queries: it needs to see
 * {@code cdc.change_tables}, which SQL Server shows to {@code db_owner} and to whoever has been granted it.
 *
 * <p>Lives next to {@link CdcImpact} because the collectors and the server must read the catalogue the same way. It takes a
 * {@link Connection} and does no connecting itself, so the engine stays free of drivers and of credentials.
 */
public final class CdcCatalog {

    /** @param enabled whether CDC is on for the database at all; {@code tables} is empty when it is not */
    public record State(boolean enabled, List<CdcTable> tables) {
    }

    private static final String ENABLED = "SELECT is_cdc_enabled FROM sys.databases WHERE name = DB_NAME()";

    private static final String TRACKED = """
            SELECT s.name AS schema_name, t.name AS table_name, ct.capture_instance, ct.role_name, ct.supports_net_changes
              FROM sys.tables t
              JOIN sys.schemas s ON s.schema_id = t.schema_id
              JOIN cdc.change_tables ct ON ct.source_object_id = t.object_id
             WHERE t.is_tracked_by_cdc = 1
             ORDER BY s.name, t.name, ct.create_date, ct.capture_instance
            """;

    private CdcCatalog() {
    }

    /** @throws SQLException when the database cannot be read, for example the account cannot see {@code cdc.change_tables} */
    public static State read(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(ENABLED)) {
            if (!rs.next() || !rs.getBoolean(1)) {
                return new State(false, List.of());
            }
        }
        Map<String, List<CdcTable.Instance>> instances = new LinkedHashMap<>();
        Map<String, String[]> names = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(TRACKED)) {
            while (rs.next()) {
                String schema = rs.getString("schema_name");
                String table = rs.getString("table_name");
                String key = CdcTable.key(schema, table);
                names.putIfAbsent(key, new String[] {schema, table});
                instances.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new CdcTable.Instance(rs.getString("capture_instance"), rs.getString("role_name"), rs.getBoolean("supports_net_changes")));
            }
        }
        List<CdcTable> tables = new ArrayList<>();
        instances.forEach((key, list) -> tables.add(new CdcTable(names.get(key)[0], names.get(key)[1], list)));
        return new State(true, tables);
    }
}
