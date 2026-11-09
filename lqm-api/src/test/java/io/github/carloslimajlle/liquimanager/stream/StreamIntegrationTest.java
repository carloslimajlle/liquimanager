package io.github.carloslimajlle.liquimanager.stream;

import static io.github.carloslimajlle.liquimanager.TestPayloads.changeset;
import static io.github.carloslimajlle.liquimanager.TestPayloads.payload;
import static io.github.carloslimajlle.liquimanager.TestPayloads.uniqueUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;

/**
 * The live feed end to end: a real HTTP client subscribes to the SSE stream, an
 * operation is ingested through the real endpoint, and the event has to arrive.
 *
 * <p>This is the test the dashboard's "live" promise rests on (RNF-19). It also pins the
 * two defects found when the screen showed "no live connection": the stream sent no
 * bytes until the first event, so a browser could not tell it was connected.
 */
class StreamIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    private HttpResponse<Stream<String>> subscribe() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + API + "/stream/operations"))
                .header("Accept", "text/event-stream").GET().build();
        return HttpClient.newHttpClient()
                .sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .get(10, TimeUnit.SECONDS);
    }

    /** Reads lines until one satisfies the condition, or the timeout hits. Returns everything read. */
    private List<String> readUntil(Iterator<String> lines, java.util.function.Predicate<String> stop, Duration timeout) {
        CompletableFuture<List<String>> reading = CompletableFuture.supplyAsync(() -> {
            List<String> seen = new ArrayList<>();
            while (lines.hasNext()) {
                String line = lines.next();
                seen.add(line);
                if (stop.test(line)) {
                    break;
                }
            }
            return seen;
        });
        try {
            return reading.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new AssertionError("timed out waiting on the stream", e);
        }
    }

    @Test
    @DisplayName("the stream answers at once with a comment, so the browser knows it is connected")
    void answersImmediately() throws Exception {
        HttpResponse<Stream<String>> response = subscribe();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");

        List<String> first = readUntil(response.body().iterator(), line -> line.startsWith(":"), Duration.ofSeconds(5));
        assertThat(first).anyMatch(line -> line.contains("connected"));
        response.body().close();
    }

    @Test
    @DisplayName("an ingested operation reaches a subscriber as an operation.ingested event")
    void deliversIngestedOperations() throws Exception {
        HttpResponse<Stream<String>> response = subscribe();
        Iterator<String> lines = response.body().iterator();
        readUntil(lines, line -> line.startsWith(":"), Duration.ofSeconds(5));

        UUID operationId = UUID.randomUUID();
        String project = unique("live");
        HttpResponse<String> ingested = call("POST", API + "/ingest/operations", serviceToken("ingest"),
                payload(operationId, project, unique("c"), uniqueUrl(), "SUCCESS", changeset("SUP-1-001", "EXECUTED")));
        assertThat(ingested.statusCode()).isEqualTo(202);

        List<String> received = readUntil(lines, line -> line.startsWith("data:"), Duration.ofSeconds(5));

        assertThat(received).contains("event:operation.ingested");
        String data = received.get(received.size() - 1);
        assertThat(data).contains(operationId.toString()).contains(project).contains("\"status\":\"SUCCESS\"");
        response.body().close();
    }

    @Test
    @DisplayName("an operation that is refused is never announced")
    void rejectedOperationsAreNotAnnounced() throws Exception {
        HttpResponse<Stream<String>> response = subscribe();
        Iterator<String> lines = response.body().iterator();
        readUntil(lines, line -> line.startsWith(":"), Duration.ofSeconds(5));

        HttpResponse<String> refused = call("POST", API + "/ingest/operations", serviceToken("ingest"),
                "{\"command\":\"update\"}");
        assertThat(refused.statusCode()).isEqualTo(422);

        // Prove a real event still flows, and that the refused one left nothing before it.
        UUID real = UUID.randomUUID();
        call("POST", API + "/ingest/operations", serviceToken("ingest"),
                payload(real, unique("live"), unique("c"), uniqueUrl(), "SUCCESS", ""));
        List<String> received = readUntil(lines, line -> line.startsWith("data:"), Duration.ofSeconds(5));

        assertThat(received.get(received.size() - 1)).contains(real.toString());
        response.body().close();
    }
}
