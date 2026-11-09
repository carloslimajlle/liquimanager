package io.github.carloslimajlle.liquimanager.collector;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JdbcUrlsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "jdbc:postgresql://db:5432/app?user=lqm&password=s3cret|jdbc:postgresql://db:5432/app",
            "jdbc:postgresql://db:5432/app?ssl=true&user=lqm&password=s3cret|jdbc:postgresql://db:5432/app?ssl=true",
            "jdbc:postgresql://db:5432/app?user=lqm&ssl=true|jdbc:postgresql://db:5432/app?ssl=true",
            "jdbc:sqlserver://db:1433;databaseName=app;user=sa;password=x;encrypt=true|jdbc:sqlserver://db:1433;databaseName=app;encrypt=true",
            "jdbc:postgresql://lqm:s3cret@db:5432/app|jdbc:postgresql://db:5432/app",
            "jdbc:postgresql://db:5432/app|jdbc:postgresql://db:5432/app",
    })
    void removesCredentialsAndKeepsTheRest(String url, String expected) {
        assertThat(JdbcUrls.sanitize(url)).isEqualTo(expected);
    }

    @Test
    void nullStaysNull() {
        assertThat(JdbcUrls.sanitize(null)).isNull();
    }
}
