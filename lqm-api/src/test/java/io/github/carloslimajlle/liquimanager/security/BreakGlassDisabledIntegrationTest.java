package io.github.carloslimajlle.liquimanager.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;

/** Off by default: the sign-in answers as if it did not exist. */
class BreakGlassDisabledIntegrationTest extends AbstractDatabaseTest {

    @Test
    @DisplayName("without the feature enabled the sign-in is a 404")
    void disabled() {
        assertThat(post("/api/v1/auth/break-glass", "{\"username\":\"a\",\"password\":\"b\",\"code\":\"123456\"}").statusCode()).isEqualTo(404);
    }
}
