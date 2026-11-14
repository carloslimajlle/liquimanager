package io.github.carloslimajlle.liquimanager.policy.cdc;

import java.util.List;
import java.util.Locale;

/**
 * A table that SQL Server Change Data Capture tracks, with its capture instances (at most two: that limit is what makes
 * the two-instance recapture possible and also what stops a third).
 *
 * @param schema    the schema of the tracked table
 * @param table     the tracked table
 * @param instances the capture instances, oldest first
 */
public record CdcTable(String schema, String table, List<Instance> instances) {

    /**
     * One capture instance.
     *
     * @param name               {@code capture_instance}, for example {@code dbo_cliente}
     * @param role               the gating role, or null when access is not gated
     * @param supportsNetChanges whether net changes are on, which needs a primary key or a unique index
     */
    public record Instance(String name, String role, boolean supportsNetChanges) {
    }

    public CdcTable {
        instances = instances == null ? List.of() : List.copyOf(instances);
    }

    /** {@code schema.table}, lower-cased: how impacts and tracked tables are matched, since SQL Server names are not case-sensitive here. */
    public String key() {
        return key(schema, table);
    }

    public static String key(String schema, String table) {
        return (schema == null || schema.isBlank() ? "dbo" : schema).toLowerCase(Locale.ROOT) + "." + table.toLowerCase(Locale.ROOT);
    }

    /** {@code [schema].[table]}, for SQL. */
    public String quoted() {
        return "[" + schema + "].[" + table + "]";
    }
}
