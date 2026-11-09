package io.github.carloslimajlle.liquimanager.cli;

import java.io.PrintStream;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import io.github.carloslimajlle.liquimanager.client.EffectivePolicies;
import io.github.carloslimajlle.liquimanager.client.IngestPayload;
import io.github.carloslimajlle.liquimanager.client.LqmClient;
import io.github.carloslimajlle.liquimanager.client.LqmConfig;
import io.github.carloslimajlle.liquimanager.client.PromotionGate;
import io.github.carloslimajlle.liquimanager.collector.ChangeSetRecorder;
import io.github.carloslimajlle.liquimanager.collector.ChecksumGate;
import io.github.carloslimajlle.liquimanager.collector.MergeRequestComment;
import io.github.carloslimajlle.liquimanager.collector.OrderGate;
import io.github.carloslimajlle.liquimanager.collector.PendingScope;
import io.github.carloslimajlle.liquimanager.collector.SecretRef;
import io.github.carloslimajlle.liquimanager.collector.PolicyGate;
import io.github.carloslimajlle.liquimanager.collector.ReportBuilder;
import io.github.carloslimajlle.liquimanager.collector.RunInfo;
import io.github.carloslimajlle.liquimanager.policy.Severity;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.resource.DirectoryResourceAccessor;
import picocli.CommandLine.Mixin;

/**
 * Template of every command that touches a changelog.
 *
 * <p>Order matters and is the same for all of them: open the database, classify the
 * changesets, evaluate the policies, run, then report. Reporting is the LAST step and can
 * never change the exit code (ADR-004): if LiquiManager is down the deploy still succeeds.
 *
 * <p>Exit codes: {@code 0} ok, {@code 1} the operation failed, {@code 2} bad usage (picocli),
 * {@code 3} blocked by a policy.
 */
abstract class LiquibaseCommand implements Callable<Integer> {

    static final int EXIT_OK = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_BLOCKED = 3;

    @Mixin
    RunOptions options;

    /** What the command is called in the report, e.g. {@code update}. */
    abstract String commandName();

    /** Whether the policy package is evaluated before the command runs. */
    boolean gated() {
        return false;
    }

    /** Whether the verdict is also posted on the GitLab merge request of the job, when there is one. */
    boolean commentsOnMergeRequest() {
        return false;
    }

    /** Which pending changesets this command runs: all of them, or the first few, or up to a tag (the gate judges exactly those). */
    PendingScope pendingScope() {
        return PendingScope.all();
    }

    /** Refuses a request the changelog cannot satisfy, before anything is judged or run; throwing is a failure (exit 1). */
    void prepare(Liquibase liquibase) throws Exception {
    }

    abstract void perform(Context ctx) throws Exception;

    /** What {@link #perform} gets: everything a command needs, prepared by the template. */
    static final class Context {
        final Liquibase liquibase;
        final Contexts contexts;
        final LabelExpression labels;
        final Writer sql = new StringWriter();
        final PrintStream out;
        final Output output;
        final ChangeSetRecorder recorder;

        Context(Liquibase liquibase, Contexts contexts, LabelExpression labels, PrintStream out, Output output,
                ChangeSetRecorder recorder) {
            this.recorder = recorder;
            this.liquibase = liquibase;
            this.contexts = contexts;
            this.labels = labels;
            this.out = out;
            this.output = output;
        }

        String sqlText() {
            String text = sql.toString();
            return text.isBlank() ? null : text;
        }
    }

    @Override
    public Integer call() {
        Output output = new Output(System.out, options.format);
        Instant started = Instant.now();
        options.useSavedLogin(System.getenv());

        String missing = missingOption();
        if (missing != null) {
            System.err.println("lqm: missing " + missing);
            return 2;
        }

        Map<String, String> env = System.getenv();
        RunInfo info = new RunInfo(commandName(), source(env),
                blank(options.project), blank(options.environment), blank(options.connectionKey),
                blank(options.changelogFile), blank(options.contexts), blank(options.labels), tagName(),
                env.get("CI_PIPELINE_ID"), "cli", Version.current(),
                RunInfo.detectGit(env), RunInfo.detectCi(env));
        ReportBuilder report = new ReportBuilder(info, operationId());
        ChangeSetRecorder recorder = new ChangeSetRecorder();
        LqmClient client = client();

        Database database = null;
        Optional<PolicyGate.Verdict> verdict = Optional.empty();
        String status = "SUCCESS";
        String error = null;
        int exit = EXIT_OK;
        Context ctx = null;

        try {
            database = openDatabase();
            Liquibase liquibase = openLiquibase(database);
            liquibase.setChangeExecListener(recorder);
            Contexts contexts = new Contexts(blank(options.contexts));
            LabelExpression labels = new LabelExpression(blank(options.labels));
            prepare(liquibase);
            // A command that takes no changelog (snapshot, diff, history) has nothing to classify: parsing nothing would only warn.
            if (needsChangelog() || blank(options.changelogFile) != null) {
                recorder.classify(liquibase, contexts, labels, pendingScope());
            }
            ctx = new Context(liquibase, contexts, labels, System.out, output, recorder);

            if (gated() && options.checks == RunOptions.Toggle.on && client.isConfigured()) {
                verdict = evaluate(client, liquibase, contexts, labels, output, started);
                if (commentsOnMergeRequest() && options.mrComment == RunOptions.Toggle.on) {
                    verdict.ifPresent(v -> commentOnMergeRequest(env, v, output));
                }
            }

            String notInPrevious = gated() && options.checks == RunOptions.Toggle.on && client.isConfigured()
                    ? notInPreviousEnvironment(client, recorder, liquibase, contexts, labels, output) : null;

            if (verdict.map(PolicyGate.Verdict::blocked).orElse(false)) {
                status = "BLOCKED";
                exit = EXIT_BLOCKED;
                error = "blocked by policy";
            } else if (notInPrevious != null) {
                status = "BLOCKED";
                exit = EXIT_BLOCKED;
                error = notInPrevious;
            } else {
                perform(ctx);
            }
        } catch (Exception e) {
            status = "FAILURE";
            exit = EXIT_FAILED;
            error = rootMessage(e);
            output.line("FAIL", commandName(), error, Duration.between(started, Instant.now()));
        } finally {
            if (database != null) {
                try {
                    database.close();
                } catch (Exception ignored) {
                    // closing a connection that is already broken tells us nothing new
                }
            }
        }

        if (exit == EXIT_OK && recorder.anyFailed()) {
            status = "FAILURE";
            exit = EXIT_FAILED;
        }

        IngestPayload payload = report.build(status, database, options.url, recorder, verdict, error,
                exit == EXIT_OK ? null : exit, output.log(), ctx == null ? null : ctx.sqlText(), parent());

        boolean sent = false;
        if (options.report == RunOptions.Toggle.on && client.isConfigured()) {
            sent = client.report(payload);
        }
        output.finish(payload, sent ? reportUrl(payload) : null);
        return exit;
    }

    // ---------------------------------------------------------------------- steps

    private Optional<PolicyGate.Verdict> evaluate(LqmClient client, Liquibase liquibase, Contexts contexts,
                                                  LabelExpression labels, Output output, Instant started) {
        Optional<EffectivePolicies> policies = client.effectivePolicies(
                blank(options.project), blank(options.environment), blank(options.changelogFile));
        if (policies.isEmpty()) {
            output.line("SKIP", "checks", "LiquiManager unreachable; running without a gate", Duration.ZERO);
            return Optional.empty();
        }
        Instant t0 = Instant.now();
        Optional<PolicyGate.Verdict> verdict = PolicyGate.evaluate(
                liquibase, contexts, labels, pendingScope(), policies.get(), blank(options.environment), t0);
        // The process check the engine cannot make: a changeset that already ran and was edited afterwards.
        if (verdict.isPresent()) {
            var changed = ChecksumGate.evaluate(liquibase, contexts, labels, client, blank(options.project),
                    blank(options.environment), commitOf(), blank(options.changelogFile), policies.get());
            verdict = Optional.of(verdict.get().with(changed, policies.get()));
        }
        verdict.ifPresent(v -> {
            output.line(v.blocked() ? "BLOCK" : "PASS", "checks",
                    v.findings().size() + " findings · gate " + v.gate().name().toLowerCase(),
                    Duration.between(t0, Instant.now()));
            v.findings().forEach(f -> output.detail(
                    Severity.label(f.severity()) + " " + f.checkCode() + ": " + f.message()));
        });
        return verdict;
    }

    private static String commitOf() {
        var git = RunInfo.detectGit(System.getenv());
        return git == null ? null : git.commit();
    }

    /**
     * What is in HML has to be in DEV first: the changesets this run is about to apply must already be executed in the stage before
     * the target environment, as LiquiManager knows it. Returns why it must not run, or null.
     *
     * <p>Fail-open when LiquiManager cannot answer (ADR-004): the deploy is not held hostage by the control plane. When it answers
     * and the previous stage lacks the changesets, the deploy stops with the exit code of a blocked gate.
     */
    private String notInPreviousEnvironment(LqmClient client, ChangeSetRecorder recorder, Liquibase liquibase, Contexts contexts,
                                            LabelExpression labels, Output output) {
        if (blank(options.project) == null || blank(options.environment) == null) {
            return null;
        }
        Instant t0 = Instant.now();
        Optional<PromotionGate> answer = client.promotionGate(blank(options.project), blank(options.environment));
        if (answer.isEmpty()) {
            output.line("SKIP", "order", "LiquiManager unreachable; the environment order was not checked", Duration.ZERO);
            return null;
        }
        PromotionGate gate = answer.get();
        if (!gate.gated()) {
            return null;
        }
        OrderGate.Verdict verdict = OrderGate.evaluate(gate, recorder.changesets(), OrderGate.declaredValid(liquibase, contexts, labels));
        if (!verdict.blocked()) {
            output.line("PASS", "order", "everything to apply is already in " + gate.previousEnv(), Duration.between(t0, Instant.now()));
            return null;
        }
        String why = verdict.why();
        output.line("BLOCK", "order", why, Duration.between(t0, Instant.now()));
        return why;
    }

    /** Fail-open like the rest of the reporting: a comment that cannot be posted is a line in the log, not a failure. */
    private void commentOnMergeRequest(Map<String, String> env, PolicyGate.Verdict verdict, Output output) {
        Optional<MergeRequestComment.Target> target = MergeRequestComment.Target.from(env);
        if (target.isEmpty()) {
            return;
        }
        Instant t0 = Instant.now();
        String problem = MergeRequestComment.publish(target.get(), blank(options.environment), verdict.payload());
        output.line(problem == null ? "PASS" : "SKIP", "mr-comment",
                problem == null ? "summary posted on the merge request" : "not posted: " + problem,
                Duration.between(t0, Instant.now()));
    }

    private Database openDatabase() throws Exception {
        String user = blank(options.username);
        String password = blank(options.password);
        if (user == null && password == null && blank(options.secretRef) != null) {
            // The job asks the secret provider itself; LiquiManager never sees the credentials. A failure here is a failure of the
            // run (there is no database to open), and the message names the provider and the status, never a value.
            SecretRef.Credentials credentials = SecretRef.resolve(options.secretRef.trim(), System.getenv());
            user = credentials.username();
            password = credentials.password();
        }
        return DatabaseFactory.getInstance().openDatabase(
                options.url, user, password,
                null, null, null, null, new DirectoryResourceAccessor(workDir()));
    }

    private Liquibase openLiquibase(Database database) throws Exception {
        Path changelog = Paths.get(options.changelogFile);
        Path root = changelog.isAbsolute() ? changelog.getParent() : workDir();
        String name = changelog.isAbsolute() ? changelog.getFileName().toString()
                : options.changelogFile.replace('\\', '/');
        return new Liquibase(name, new DirectoryResourceAccessor(root), database);
    }

    private Path workDir() {
        String chosen = blank(options.workdir);
        return (chosen == null ? Paths.get("") : Paths.get(chosen)).toAbsolutePath();
    }

    private String source(Map<String, String> env) {
        String chosen = blank(options.source);
        return chosen != null ? chosen : RunInfo.detectCi(env) != null ? "pipeline" : "cli-local";
    }

    private java.util.UUID operationId() {
        String value = blank(options.operationId);
        try {
            return value == null ? java.util.UUID.randomUUID() : java.util.UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return java.util.UUID.randomUUID();
        }
    }

    LqmClient client() {
        Path buffer = Optional.ofNullable(blank(options.bufferDir)).map(Paths::get)
                .orElseGet(() -> Paths.get(System.getProperty("user.home"), ".lqm", "buffer"));
        return new LqmClient(new LqmConfig(blank(options.lqmUrl), blank(options.lqmToken), null, buffer));
    }

    private String reportUrl(IngestPayload payload) {
        return options.lqmUrl.replaceAll("/+$", "") + "/operations/" + payload.operationId();
    }

    /** Names the first thing the command cannot do without, or null when it can run. */
    String missingOption() {
        if (blank(options.url) == null) {
            return "--url (or LQM_DB_URL)";
        }
        if (blank(options.changelogFile) == null && needsChangelog()) {
            return "--changelog-file (or LQM_CHANGELOG_FILE)";
        }
        if (options.report == RunOptions.Toggle.on && blank(options.lqmUrl) != null
                && (blank(options.project) == null || blank(options.environment) == null)) {
            return "--project and --env (or LQM_PROJECT / LQM_ENV) when reporting to LiquiManager";
        }
        return null;
    }

    boolean needsChangelog() {
        return true;
    }

    private java.util.UUID parent() {
        String value = blank(options.parentOperation);
        try {
            return value == null ? null : java.util.UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The tag this command creates or targets, for the report. */
    String tagName() {
        return null;
    }

    static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
