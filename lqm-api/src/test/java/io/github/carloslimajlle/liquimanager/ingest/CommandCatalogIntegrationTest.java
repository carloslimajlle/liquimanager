package io.github.carloslimajlle.liquimanager.ingest;

import static io.github.carloslimajlle.liquimanager.TestPayloads.payloadAt;
import static io.github.carloslimajlle.liquimanager.TestPayloads.uniqueUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;

/** Every command the CLI can report is known to the ingestion, with the type that decides whether it moves changeset state. */
class CommandCatalogIntegrationTest extends AbstractDatabaseTest {

    private static final Map<String, String> TYPES = Map.of(
            "update-count", "database-change",
            "update-to-tag", "database-change",
            "rollback-count", "database-change",
            "rollback-to-date", "database-change",
            "history", "read-only",
            "snapshot", "read-only",
            "diff", "read-only",
            "diff-changelog", "read-only");

    @Test
    @DisplayName("the new commands are accepted and typed: the ones that change the database move state, snapshot and diff do not")
    void knowsTheCommands() {
        String token = serviceToken("ingest");
        String project = unique("cmd");
        String connection = unique("c");
        String url = uniqueUrl();
        TYPES.forEach((command, type) -> {
            UUID id = UUID.randomUUID();
            HttpResponse<String> response = call("POST", "/api/v1/ingest/operations", token,
                    payloadAt("DEV", id, project, connection, url, command, "SUCCESS", Instant.now().minusSeconds(30), ""));
            assertThat(response.statusCode()).as(command + ": " + response.body()).isIn(200, 202);
            assertThat(jdbc.sql("select type from operation where id = :id").param("id", id).query(String.class).single())
                    .as(command).isEqualTo(type);
        });

        HttpResponse<String> unknown = call("POST", "/api/v1/ingest/operations", token,
                payloadAt("DEV", UUID.randomUUID(), project, connection, url, "update-everything", "SUCCESS", Instant.now(), ""));
        assertThat(unknown.statusCode()).isEqualTo(422);
    }
}
