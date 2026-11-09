package io.github.carloslimajlle.liquimanager.cli;

import java.util.ArrayList;
import java.util.List;

import picocli.CommandLine.Option;

/**
 * Options every command shares (docs/07 § 2). Each one can come from a flag or from an
 * environment variable, so a pipeline sets them once as CI variables and the command line
 * stays short. Database credentials have NO flag in the CI path: they come from
 * {@code LQM_DB_USERNAME} / {@code LQM_DB_PASSWORD}, masked by the CI (RNF-06).
 */
public class RunOptions {

    @Option(names = "--changelog-file", description = "Root changelog, relative to the working directory.",
            defaultValue = "${env:LQM_CHANGELOG_FILE:-}")
    String changelogFile;

    @Option(names = "--url", description = "JDBC URL of the target database, without credentials.",
            defaultValue = "${env:LQM_DB_URL:-}")
    String url;

    @Option(names = "--username", description = "Database user. For local use only; in CI use LQM_DB_USERNAME.",
            defaultValue = "${env:LQM_DB_USERNAME:-}")
    String username;

    @Option(names = "--password", description = "Database password. For local use only; in CI use LQM_DB_PASSWORD.",
            defaultValue = "${env:LQM_DB_PASSWORD:-}")
    String password;

    @Option(names = "--env", description = "Environment code (DEV, TST, HML, PRD...).", defaultValue = "${env:LQM_ENV:-}")
    String environment;

    @Option(names = "--project", description = "Project slug in LiquiManager.", defaultValue = "${env:LQM_PROJECT:-}")
    String project;

    @Option(names = "--connection-key", description = "Connection key in LiquiManager; when absent the JDBC URL identifies it.",
            defaultValue = "${env:LQM_CONNECTION_KEY:-}")
    String connectionKey;

    @Option(names = "--contexts", description = "Liquibase contexts.", defaultValue = "${env:LQM_CONTEXTS:-}")
    String contexts;

    @Option(names = "--labels", description = "Liquibase label expression.", defaultValue = "${env:LQM_LABELS:-}")
    String labels;

    @Option(names = "--lqm-url", description = "LiquiManager base URL.", defaultValue = "${env:LQM_URL:-}")
    String lqmUrl;

    @Option(names = "--lqm-token", description = "LiquiManager service token. Prefer LQM_TOKEN: a flag is visible in the process list.",
            defaultValue = "${env:LQM_TOKEN:-}")
    String lqmToken;

    @Option(names = "--secret-ref", description = "Where to fetch the database credentials from, when they are not in the variables: "
            + "vault:<path>, consul:<key>, azure:<secret> or env:<PREFIX> (the provider's own environment, e.g. VAULT_ADDR and VAULT_TOKEN, "
            + "tells how to reach it). Ignored when --username or --password is given.", defaultValue = "${env:LQM_SECRET_REF:-}")
    String secretRef;

    @Option(names = "--buffer-dir", description = "Where reports wait when LiquiManager is unreachable (default ~/.lqm/buffer).",
            defaultValue = "${env:LQM_BUFFER_DIR:-}")
    String bufferDir;

    @Option(names = "--parent-operation", description = "The operation this one answers (a rollback runs on behalf of an earlier operation).",
            defaultValue = "${env:LQM_PARENT_OPERATION:-}")
    String parentOperation;

    @Option(names = "--workdir", description = "Directory the changelog path is relative to (default: the current directory).",
            defaultValue = "${env:LQM_WORKDIR:-}")
    String workdir;

    @Option(names = "--operation-id", hidden = true, description = "Id of the operation this run reports; a runner sets it.")
    String operationId;

    @Option(names = "--source", hidden = true, description = "Where the operation comes from (pipeline, cli-local, ui...).")
    String source;

    @Option(names = "--report", description = "Send the report to LiquiManager: ${COMPLETION-CANDIDATES}.",
            defaultValue = "on")
    Toggle report = Toggle.on;

    @Option(names = "--checks", description = "Evaluate the policy package before running: ${COMPLETION-CANDIDATES}.",
            defaultValue = "on")
    Toggle checks = Toggle.on;

    @Option(names = "--mr-comment", description = "Post the checks summary on the GitLab merge request, when the job has one "
            + "and LQM_GITLAB_TOKEN is set: ${COMPLETION-CANDIDATES}.", defaultValue = "on")
    Toggle mrComment = Toggle.on;

    @Option(names = "--format", description = "Output format: ${COMPLETION-CANDIDATES}.", defaultValue = "text")
    Format format = Format.text;

    @Option(names = "--artifact", hidden = true, description = "Where update-sql also writes the SQL (a flow stage sets it).")
    String artifact;

    /**
     * The options that were SET, as the command line that would set them again. A flow uses it to run each of its
     * stages as the ordinary command it names, with the same database, environment and LiquiManager as the flow itself.
     */
    List<String> toArguments() {
        List<String> args = new ArrayList<>();
        add(args, "--changelog-file", changelogFile);
        add(args, "--url", url);
        add(args, "--username", username);
        add(args, "--password", password);
        add(args, "--secret-ref", secretRef);
        add(args, "--env", environment);
        add(args, "--project", project);
        add(args, "--connection-key", connectionKey);
        add(args, "--contexts", contexts);
        add(args, "--labels", labels);
        add(args, "--lqm-url", lqmUrl);
        add(args, "--lqm-token", lqmToken);
        add(args, "--buffer-dir", bufferDir);
        add(args, "--workdir", workdir);
        add(args, "--source", source);
        args.addAll(List.of("--report", report.name(), "--checks", checks.name(), "--mr-comment", mrComment.name()));
        return args;
    }

    /**
     * What {@code lqm login} saved, for the address and the token that were not given: the flags and the variables win. A saved token goes
     * only to the address it was saved for, never to another one.
     */
    void useSavedLogin(java.util.Map<String, String> env) {
        boolean noUrl = lqmUrl == null || lqmUrl.isBlank();
        boolean noToken = lqmToken == null || lqmToken.isBlank();
        if (!noToken && !noUrl) {
            return;
        }
        SavedLogin.read(env).ifPresent(saved -> {
            if (noUrl && noToken) {
                lqmUrl = saved.url();
                lqmToken = saved.token();
            } else if (noToken && SavedLogin.sameServer(lqmUrl, saved.url())) {
                lqmToken = saved.token();
            }
        });
    }

    private static void add(List<String> args, String name, String value) {
        if (value != null && !value.isBlank()) {
            args.add(name);
            args.add(value);
        }
    }

    public enum Toggle { on, off }

    public enum Format { text, json }
}
