package io.github.carloslimajlle.liquimanager.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JdbcFingerprintTest {

    @Test
    @DisplayName("SQL Server: host, port and databaseName, with options discarded")
    void sqlServer() {
        assertThat(JdbcFingerprint.of("jdbc:sqlserver://sql-prd:1433;databaseName=Core;encrypt=true"))
                .isEqualTo("sql-prd:1433/core");
    }

    @Test
    @DisplayName("SQL Server: default port is 1433 and `database` is an alias of databaseName")
    void sqlServerDefaults() {
        assertThat(JdbcFingerprint.of("jdbc:sqlserver://SQL-PRD;database=Core"))
                .isEqualTo("sql-prd:1433/core");
    }

    @Test
    @DisplayName("PostgreSQL: query string is dropped and default port applied")
    void postgres() {
        assertThat(JdbcFingerprint.of("jdbc:postgresql://db.local/lqm?ssl=true"))
                .isEqualTo("db.local:5432/lqm");
    }

    @Test
    @DisplayName("two URLs for the same database yield the same fingerprint")
    void sameDatabaseSameFingerprint() {
        assertThat(JdbcFingerprint.of("jdbc:sqlserver://sql-prd:1433;databaseName=Core;encrypt=true"))
                .isEqualTo(JdbcFingerprint.of("jdbc:sqlserver://SQL-PRD:1433;databaseName=core"));
    }

    @Test
    @DisplayName("MySQL and Oracle are understood")
    void otherEngines() {
        assertThat(JdbcFingerprint.of("jdbc:mysql://m1:3307/shop")).isEqualTo("m1:3307/shop");
        assertThat(JdbcFingerprint.of("jdbc:oracle:thin:@ora1:1521/ORCL")).isEqualTo("ora1:1521/orcl");
    }

    @Test
    @DisplayName("an unknown or blank URL yields null instead of throwing")
    void unknown() {
        assertThat(JdbcFingerprint.of("not a url")).isNull();
        assertThat(JdbcFingerprint.of("")).isNull();
        assertThat(JdbcFingerprint.of(null)).isNull();
    }
}
