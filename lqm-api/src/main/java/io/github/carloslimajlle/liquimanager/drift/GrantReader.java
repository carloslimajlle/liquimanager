package io.github.carloslimajlle.liquimanager.drift;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The privileges granted on the tables and views of the monitored schemas, which Liquibase's own snapshot does not carry. Read from the
 * database's catalog, one string per grant ({@code grantee|schema.object|PRIVILEGE}), sorted, so two reads of the same database are equal.
 *
 * <p>Only PostgreSQL is read today (from {@code pg_class.relacl}, which every login can see, unlike the information schema's views that show
 * only what the monitoring account itself touches). Anywhere else it answers "not supported", and drift says nothing about grants rather
 * than guessing: a comparison needs both sides to have been read the same way. The owner's own implicit privileges are left out; they
 * are not something anybody granted.
 */
final class GrantReader {

    private static final String POSTGRES = """
            select coalesce(r.rolname, 'PUBLIC') as grantee, n.nspname as schema_name, c.relname as object_name, a.privilege_type
              from pg_class c
              join pg_namespace n on n.oid = c.relnamespace
              cross join lateral aclexplode(c.relacl) a
              left join pg_roles r on r.oid = a.grantee
             where n.nspname = any(?) and c.relkind in ('r', 'v', 'm', 'p') and a.grantee <> c.relowner
             order by 1, 2, 3, 4
            """;

    private GrantReader() {
    }

    /** The grants, or empty when this kind of database is not read. */
    static Optional<List<String>> read(TargetConnections.Target target, List<String> schemas) throws SQLException {
        if (!"postgresql".equals(target.database().getShortName()) || schemas.isEmpty()) {
            return Optional.empty();
        }
        List<String> out = new ArrayList<>();
        Array names = target.jdbc().createArrayOf("text", schemas.toArray());
        try (PreparedStatement statement = target.jdbc().prepareStatement(POSTGRES)) {
            statement.setArray(1, names);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString("grantee") + "|" + rs.getString("schema_name") + "." + rs.getString("object_name") + "|"
                            + rs.getString("privilege_type"));
                }
            }
        } finally {
            names.free();
        }
        return Optional.of(out);
    }
}
