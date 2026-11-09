package io.github.carloslimajlle.liquimanager.collector;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.carloslimajlle.liquimanager.client.IngestPayload;

/**
 * A summary of the policy findings as a comment on the GitLab merge request (docs/07 § 3, E24).
 *
 * <p>It runs inside the CI job, which already holds the verdict, so nothing is sent to the server and back. The token is
 * a CI variable ({@code LQM_GITLAB_TOKEN}, a project access token with {@code api}); {@code CI_JOB_TOKEN} cannot write
 * merge request notes. Like everything a collector does beyond the gate itself, it is fail-open (ADR-004): any failure is
 * reported as a reason and swallowed, and the pipeline goes on.
 *
 * <p>One comment per merge request: the first run posts it, later runs edit it, found by a hidden marker. A new comment
 * on every push would bury the review under its own bot.
 */
public final class MergeRequestComment {

    /** Hidden in the body so a later run can find its own comment. */
    static final String MARKER = "<!-- lqm-checks -->";
    private static final int MAX_ROWS = 30;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Where the comment goes. All four come from the GitLab CI environment and the token variable. */
    public record Target(String apiUrl, String projectId, String mergeRequestIid, String token) {

        /** The target of this job, or empty when it is not a merge request pipeline or no token was given. */
        public static Optional<Target> from(Map<String, String> env) {
            String api = env.get("CI_API_V4_URL");
            String project = env.get("CI_PROJECT_ID");
            String iid = env.get("CI_MERGE_REQUEST_IID");
            String token = env.get("LQM_GITLAB_TOKEN");
            if (blank(api) || blank(project) || blank(iid) || blank(token)) {
                return Optional.empty();
            }
            return Optional.of(new Target(api, project, iid, token));
        }
    }

    private MergeRequestComment() {
    }

    /** What went wrong, if anything. {@code null} means the comment is up. */
    public static String publish(Target target, String environment, IngestPayload.Policy policy) {
        try {
            String body = body(environment, policy);
            String notes = target.apiUrl() + "/projects/" + encode(target.projectId())
                    + "/merge_requests/" + encode(target.mergeRequestIid()) + "/notes";

            String existing = findOwnNote(target, notes);
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(15)).header("PRIVATE-TOKEN", target.token())
                    .header("Content-Type", "application/x-www-form-urlencoded");
            String form = "body=" + URLEncoder.encode(body, StandardCharsets.UTF_8);
            HttpResponse<String> response = HTTP.send(
                    (existing == null
                            ? request.uri(URI.create(notes)).POST(HttpRequest.BodyPublishers.ofString(form))
                            : request.uri(URI.create(notes + "/" + existing)).PUT(HttpRequest.BodyPublishers.ofString(form)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            // The status only: an error body from GitLab may repeat what was sent.
            return response.statusCode() / 100 == 2 ? null : "GitLab answered " + response.statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (Exception e) {
            return "could not reach GitLab";
        }
    }

    /** The id of the comment this tool left earlier on the merge request, if there is one. */
    private static String findOwnNote(Target target, String notes) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(notes + "?per_page=100&sort=desc"))
                .timeout(Duration.ofSeconds(15)).header("PRIVATE-TOKEN", target.token()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            return null;
        }
        for (JsonNode note : JSON.readTree(response.body())) {
            JsonNode text = note.get("body");
            if (text != null && text.asText().contains(MARKER) && note.hasNonNull("id")) {
                return note.get("id").asText();
            }
        }
        return null;
    }

    /** The comment text. pt-BR: a person reads it. Status is always a word, never only a symbol (RNF-13). */
    static String body(String environment, IngestPayload.Policy policy) {
        List<IngestPayload.Finding> findings = policy.findings() == null ? List.of() : policy.findings();
        String verdict = switch (policy.gateResult() == null ? "" : policy.gateResult()) {
            case "BLOCKED" -> "✕ Bloqueado";
            case "WARNED" -> "! Com avisos";
            default -> "✓ Aprovado";
        };
        StringBuilder out = new StringBuilder(MARKER).append('\n');
        out.append("### LiquiManager · checks: ").append(verdict).append("\n\n");
        out.append("Ambiente alvo: `").append(blank(environment) ? "n/d" : environment).append("` · ")
                .append(findings.size()).append(findings.size() == 1 ? " achado" : " achados").append("\n\n");
        if (findings.isEmpty()) {
            return out.append("Nenhum achado nos changesets desta mudança.\n").toString();
        }
        out.append("| Resultado | Severidade | Check | Changeset | Mensagem |\n|---|---|---|---|---|\n");
        List<IngestPayload.Finding> ordered = findings.stream()
                .sorted((a, b) -> Integer.compare(b.severity() == null ? 0 : b.severity(), a.severity() == null ? 0 : a.severity()))
                .toList();
        for (IngestPayload.Finding finding : ordered.stream().limit(MAX_ROWS).toList()) {
            out.append("| ").append(result(finding.result())).append(" | ").append(severity(finding.severity()))
                    .append(" | `").append(cell(finding.check())).append("` | `").append(cell(changesetId(finding.changeset())))
                    .append("` | ").append(cell(finding.message())).append(" |\n");
        }
        if (ordered.size() > MAX_ROWS) {
            out.append("\n… e mais ").append(ordered.size() - MAX_ROWS).append(" achados. O resto está na operação no LiquiManager.\n");
        }
        return out.toString();
    }

    private static String result(String result) {
        return switch (result == null ? "" : result) {
            case "BLOCKED" -> "✕ bloqueia";
            case "WARNED" -> "! avisa";
            default -> "informa";
        };
    }

    private static String severity(Integer severity) {
        return switch (severity == null ? 0 : severity) {
            case 4 -> "bloqueante";
            case 3 -> "crítica";
            case 2 -> "maior";
            case 1 -> "menor";
            default -> "info";
        };
    }

    /** "SUP-1-001::p.ricardo::db/x.xml" -> "SUP-1-001": the file path and author are noise in a table cell. */
    private static String changesetId(String key) {
        if (key == null) {
            return "";
        }
        int cut = key.indexOf("::");
        return cut < 0 ? key : key.substring(0, cut);
    }

    /**
     * One table cell: no pipes, no backticks that would close the code span. A message of several lines (a changed changeset shows what
     * changed) keeps them as line breaks, and the lines of the diff, which start with {@code - } or {@code + }, read as code.
     */
    private static String cell(String text) {
        if (text == null) {
            return "";
        }
        String clean = text.replace("\r", "").replace("|", "\\|").replace("`", "'");
        if (!clean.contains("\n")) {
            return clean;
        }
        List<String> lines = new java.util.ArrayList<>();
        for (String line : clean.split("\n")) {
            boolean diff = line.startsWith("- ") || line.startsWith("+ ");
            lines.add(diff ? "<code>" + line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</code>" : line);
        }
        return String.join("<br>", lines);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
