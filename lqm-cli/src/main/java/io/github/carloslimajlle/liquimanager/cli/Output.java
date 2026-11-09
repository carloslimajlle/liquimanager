package io.github.carloslimajlle.liquimanager.cli;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Locale;

import io.github.carloslimajlle.liquimanager.client.IngestPayload;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * What the command prints (docs/07 § 2.1) and, at the same time, what it keeps as the
 * operation log that goes to LiquiManager. Printing and recording in one place is what
 * guarantees the log in the UI is exactly what the pipeline showed.
 *
 * <p>In {@code json} mode nothing is printed until the end, so stdout is one parseable
 * document.
 */
final class Output {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final PrintStream out;
    private final RunOptions.Format format;
    private final StringBuilder log = new StringBuilder();

    Output(PrintStream out, RunOptions.Format format) {
        this.out = out;
        this.format = format;
        emit("LiquiManager CLI " + Version.current());
    }

    /** One result line: {@code [PASS] update   6 changesets applied   1.8s}. */
    void line(String tag, String stage, String text, Duration took) {
        String seconds = String.format(Locale.ROOT, "%.1fs", took.toMillis() / 1000.0);
        emit(String.format(Locale.ROOT, "[%s] %-10s %s  %s", tag, stage, text, seconds));
    }

    /** An indented line under the previous result, for findings and similar detail. */
    void detail(String text) {
        emit("       " + text);
    }

    String log() {
        return log.toString();
    }

    void finish(IngestPayload payload, String reportUrl) {
        if (reportUrl != null) {
            emit("Relatorio: " + reportUrl);
        }
        if (format == RunOptions.Format.json) {
            try {
                // The log is already in the payload; the printed document is the summary of the run.
                IngestPayload brief = new IngestPayload(
                        payload.operationId(), payload.command(), payload.status(), payload.source(),
                        payload.executedBy(), payload.startedAt(), payload.endedAt(), payload.project(),
                        payload.environment(), payload.deploymentId(), payload.contexts(), payload.labels(),
                        payload.tag(), payload.connection(), payload.changelog(), payload.git(), payload.ci(),
                        payload.runtime(), payload.summary(), payload.changesets(), payload.policy(),
                        payload.error(), null, null, payload.parentOperationId());
                out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(brief));
            } catch (Exception e) {
                out.println("{\"error\":\"could not render the result\"}");
            }
        }
    }

    private void emit(String text) {
        log.append(text).append('\n');
        if (format == RunOptions.Format.text) {
            out.println(text);
        }
    }
}
