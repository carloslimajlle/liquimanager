package io.github.carloslimajlle.liquimanager.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import io.github.carloslimajlle.liquimanager.client.IngestPayload;

/** The merge request comment: what it says, and that it edits its own earlier note instead of adding another. */
class MergeRequestCommentTest {

    private HttpServer gitlab;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> forms = new CopyOnWriteArrayList<>();
    private final List<String> tokens = new CopyOnWriteArrayList<>();
    private volatile String existingNotes = "[]";
    private volatile int status = 200;

    @BeforeEach
    void start() throws IOException {
        gitlab = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gitlab.createContext("/", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            forms.add(URLDecoder.decode(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8));
            tokens.add(String.valueOf(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN")));
            boolean read = "GET".equals(exchange.getRequestMethod());
            byte[] body = (read ? existingNotes : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(read ? 200 : status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        gitlab.start();
    }

    @AfterEach
    void stop() {
        gitlab.stop(0);
    }

    private MergeRequestComment.Target target() {
        return new MergeRequestComment.Target("http://127.0.0.1:" + gitlab.getAddress().getPort() + "/api/v4", "77", "12",
                "glpat-SECRET");
    }

    private static IngestPayload.Policy policy(String gate, IngestPayload.Finding... findings) {
        return new IngestPayload.Policy(gate, List.of(findings));
    }

    private static IngestPayload.Finding finding(String check, int severity, String result, String changeset, String message) {
        return new IngestPayload.Finding(check, severity, result, changeset, message);
    }

    // --------------------------------------------------------------------- text

    @Test
    @DisplayName("a blocked verdict is spelled out, worst findings first, with the changeset id and no file path")
    void blockedBody() {
        String body = MergeRequestComment.body("PRD", policy("BLOCKED",
                finding("ChangesetCommentRequired", 0, "PASSED", "SUP-1-001::ana::db/a.xml", "sem comentario"),
                finding("DropTableWarn", 3, "BLOCKED", "SUP-1-002::ana::db/b.xml", "remove a tabela veiculo")));

        assertThat(body).startsWith("<!-- lqm-checks -->").contains("✕ Bloqueado", "`PRD`", "2 achados");
        assertThat(body.indexOf("DropTableWarn")).as("the worst finding comes first")
                .isLessThan(body.indexOf("ChangesetCommentRequired"));
        assertThat(body).contains("`SUP-1-002`", "crítica", "✕ bloqueia").doesNotContain("db/b.xml", "::ana");
    }

    @Test
    @DisplayName("no findings is a short, positive comment")
    void cleanBody() {
        String body = MergeRequestComment.body("HML", policy("PASSED"));

        assertThat(body).contains("✓ Aprovado", "0 achados", "Nenhum achado").doesNotContain("| Resultado");
    }

    @Test
    @DisplayName("a message cannot break the table: pipes and backticks are neutralised, and a line break stays inside the cell as <br>")
    void messageIsEscaped() {
        String body = MergeRequestComment.body("PRD", policy("WARNED",
                finding("X", 2, "WARNED", "a::b::c", "linha 1\nlinha 2 | com pipe `e crase`")));

        assertThat(body).contains("linha 1<br>linha 2 \\| com pipe 'e crase'");
    }

    @Test
    @DisplayName("a long list is cut and says how many are missing")
    void longListIsCut() {
        List<IngestPayload.Finding> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(finding("Check" + i, 1, "PASSED", "CS-" + i + "::a::f", "m"));
        }

        String body = MergeRequestComment.body("PRD", new IngestPayload.Policy("WARNED", many));

        assertThat(body).contains("e mais 10 achados");
    }

    // -------------------------------------------------------------------- target

    @Test
    @DisplayName("it only acts in a merge request pipeline with a token")
    void targetNeedsEverything() {
        Map<String, String> full = Map.of("CI_API_V4_URL", "http://gl/api/v4", "CI_PROJECT_ID", "77",
                "CI_MERGE_REQUEST_IID", "12", "LQM_GITLAB_TOKEN", "t");

        assertThat(MergeRequestComment.Target.from(full)).isPresent();
        for (String missing : full.keySet()) {
            Map<String, String> partial = new HashMap<>(full);
            partial.remove(missing);
            assertThat(MergeRequestComment.Target.from(partial)).as("without %s", missing).isEmpty();
        }
    }

    // ------------------------------------------------------------------- posting

    @Test
    @DisplayName("the first run posts a note, with the token in the header and the text in the form")
    void posts() {
        String problem = MergeRequestComment.publish(target(), "PRD", policy("BLOCKED",
                finding("DropTableWarn", 3, "BLOCKED", "SUP-1-002::ana::b.xml", "m")));

        assertThat(problem).isNull();
        assertThat(calls).containsExactly("GET /api/v4/projects/77/merge_requests/12/notes",
                "POST /api/v4/projects/77/merge_requests/12/notes");
        assertThat(forms.get(1)).startsWith("body=<!-- lqm-checks -->").contains("DropTableWarn");
        assertThat(tokens).containsOnly("glpat-SECRET");
    }

    @Test
    @DisplayName("a later run edits its own earlier note instead of adding another")
    void updatesItsOwnNote() {
        existingNotes = "[{\"id\":5,\"body\":\"someone else\"},{\"id\":9,\"body\":\"<!-- lqm-checks -->\\nold\"}]";

        String problem = MergeRequestComment.publish(target(), "PRD", policy("PASSED"));

        assertThat(problem).isNull();
        assertThat(calls).last().isEqualTo("PUT /api/v4/projects/77/merge_requests/12/notes/9");
    }

    @Test
    @DisplayName("a refusal is a reason with the status only, never the token, and does not throw")
    void refusal() {
        status = 403;

        String problem = MergeRequestComment.publish(target(), "PRD", policy("PASSED"));

        assertThat(problem).isEqualTo("GitLab answered 403").doesNotContain("SECRET");
    }

    @Test
    @DisplayName("an unreachable GitLab is a reason, not an exception")
    void unreachable() {
        MergeRequestComment.Target dead = target();
        gitlab.stop(0);

        assertThat(MergeRequestComment.publish(dead, "PRD", policy("PASSED"))).isEqualTo("could not reach GitLab");
    }
}
