package io.github.carloslimajlle.liquimanager.ingest;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalises a JDBC URL into {@code host:port/database}, so an operation whose
 * collector sent no connection key can still be matched to a registered connection
 * (docs/06, ingestion rule 1).
 *
 * <p>Credentials and driver options are discarded: they must never take part in the
 * match (RNF-06), and two URLs differing only in {@code encrypt=true} point at the
 * same database. Returns null when the URL is not understood.
 */
public final class JdbcFingerprint {

    private static final Pattern SQLSERVER = Pattern.compile(
            "^jdbc:sqlserver://([^:;/]+)(?::(\\d+))?(?:;(.*))?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL_STYLE = Pattern.compile(
            "^jdbc:(postgresql|mysql|mariadb)://([^:/?]+)(?::(\\d+))?(?:/([^?;]*))?(?:\\?.*)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ORACLE = Pattern.compile(
            "^jdbc:oracle:thin:@(?://)?([^:/]+)(?::(\\d+))?[:/]([^?]+)$", Pattern.CASE_INSENSITIVE);

    private JdbcFingerprint() {
    }

    /**
     * True when the URL carries a username or password (RNF-06). Such a URL is refused
     * on ingestion and in the catalogue alike: credentials live in the secrets
     * provider, never in a column.
     */
    public static boolean hasCredentials(String jdbcUrl) {
        if (jdbcUrl == null) {
            return false;
        }
        String url = jdbcUrl.toLowerCase(Locale.ROOT);
        return url.contains("password=") || url.contains("user=") || url.matches(".*//[^/@]+:[^/@]+@.*");
    }

    public static String of(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return null;
        }
        String url = jdbcUrl.trim();

        Matcher sqlServer = SQLSERVER.matcher(url);
        if (sqlServer.matches()) {
            String database = option(sqlServer.group(3), "databaseName", "database");
            return build(sqlServer.group(1), sqlServer.group(2), 1433, database);
        }

        Matcher urlStyle = URL_STYLE.matcher(url);
        if (urlStyle.matches()) {
            int defaultPort = switch (urlStyle.group(1).toLowerCase(Locale.ROOT)) {
                case "postgresql" -> 5432;
                default -> 3306;
            };
            return build(urlStyle.group(2), urlStyle.group(3), defaultPort, urlStyle.group(4));
        }

        Matcher oracle = ORACLE.matcher(url);
        if (oracle.matches()) {
            return build(oracle.group(1), oracle.group(2), 1521, oracle.group(3));
        }
        return null;
    }

    private static String build(String host, String port, int defaultPort, String database) {
        String effectivePort = port == null || port.isBlank() ? String.valueOf(defaultPort) : port;
        String db = database == null ? "" : database.trim().toLowerCase(Locale.ROOT);
        return host.toLowerCase(Locale.ROOT) + ":" + effectivePort + "/" + db;
    }

    /** Reads {@code name=value} from a semicolon-separated option list, trying each alias. */
    private static String option(String options, String... aliases) {
        if (options == null) {
            return null;
        }
        for (String pair : options.split(";")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = pair.substring(0, eq).trim();
            for (String alias : aliases) {
                if (key.equalsIgnoreCase(alias)) {
                    return pair.substring(eq + 1).trim();
                }
            }
        }
        return null;
    }
}
