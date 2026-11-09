package io.github.carloslimajlle.liquimanager.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/** What each channel actually puts on the wire. No database: the channels know nothing of rules. */
class SendersTest {

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private volatile int status = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook/SECRET";
    }

    @Test
    @DisplayName("Teams gets a message card with the subject as title and the body as text")
    void teams() throws Exception {
        new Senders("", "").send("TEAMS", url(), "Gate bloqueado", "linha 1\nlinha 2");

        assertThat(bodies.get(0)).contains("\"@type\":\"MessageCard\"", "\"title\":\"Gate bloqueado\"", "linha 1");
    }

    @Test
    @DisplayName("Slack gets its text, with the subject in bold")
    void slack() throws Exception {
        new Senders("", "").send("SLACK", url(), "Assunto", "corpo");

        assertThat(bodies.get(0)).isEqualTo("{\"text\":\"*Assunto*\\ncorpo\"}");
    }

    @Test
    @DisplayName("e-mail goes to the internal service with the addresses as a list and the bearer token")
    void email() throws Exception {
        new Senders(url(), "svc-token").send("EMAIL", "a@x.com, b@x.com", "Assunto", "corpo");

        assertThat(bodies.get(0)).contains("\"to\":[\"a@x.com\",\"b@x.com\"]", "\"subject\":\"Assunto\"");
        assertThat(authorizations.get(0)).isEqualTo("Bearer svc-token");
    }

    @Test
    @DisplayName("e-mail without a service configured fails with a reason, instead of pretending")
    void emailNotConfigured() {
        assertThatThrownBy(() -> new Senders("", "").send("EMAIL", "a@x.com", "s", "b"))
                .isInstanceOf(Senders.Failed.class).hasMessageContaining("not configured");
    }

    @Test
    @DisplayName("a refusal carries the status and never the URL, which is a credential")
    void failureDoesNotLeakTheUrl() {
        status = 410;

        assertThatThrownBy(() -> new Senders("", "").send("TEAMS", url(), "s", "b"))
                .isInstanceOf(Senders.Failed.class).hasMessage("the endpoint answered 410");
    }

    @Test
    @DisplayName("an unreachable endpoint is a failure with a generic reason")
    void unreachable() {
        server.stop(0);

        assertThatThrownBy(() -> new Senders("", "").send("SLACK", url(), "s", "b"))
                .isInstanceOf(Senders.Failed.class).hasMessageNotContaining("SECRET");
    }

    @Test
    @DisplayName("an unknown channel is refused")
    void unknownChannel() {
        assertThatThrownBy(() -> new Senders("", "").send("PIGEON", url(), "s", "b")).isInstanceOf(Senders.Failed.class);
    }
}
