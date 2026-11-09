package io.github.carloslimajlle.liquimanager.drift;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * What a monitored SQL Server account must be able to see for a snapshot to be true.
 *
 * <p>SQL Server does not refuse a metadata query the account has no right to: it answers as if the object, or its
 * definition, were not there. Without {@code VIEW DEFINITION} the default value of a column and the text of a view come
 * back empty, and the comparison with a snapshot taken with the right to see them reports changes nobody made. A false
 * drift is an alarm, so the run says what is wrong instead of reporting it.
 */
public final class SqlServerAccess {

    /** The sentence shown to whoever has to fix the account; the URL, the user and the password are not in it. */
    public static final String VIEW_DEFINITION_MISSING =
            "the account cannot see object definitions in this database (VIEW DEFINITION): a snapshot of it would be incomplete "
                    + "and drift would report differences nobody made. Grant VIEW DEFINITION, plus db_datareader to read DATABASECHANGELOG.";

    private SqlServerAccess() {
    }

    public static boolean isSqlServer(String jdbcUrl) {
        return jdbcUrl != null && jdbcUrl.regionMatches(true, 0, "jdbc:sqlserver:", 0, "jdbc:sqlserver:".length());
    }

    /** True when the account holds VIEW DEFINITION on the current database (db_owner and sysadmin do, implicitly). */
    public static boolean canViewDefinitions(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'VIEW DEFINITION')")) {
            return rs.next() && rs.getInt(1) == 1;
        }
    }
}
