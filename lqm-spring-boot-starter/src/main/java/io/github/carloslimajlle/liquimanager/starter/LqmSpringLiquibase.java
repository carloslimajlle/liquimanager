package io.github.carloslimajlle.liquimanager.starter;

import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.carloslimajlle.liquimanager.client.EffectivePolicies;
import io.github.carloslimajlle.liquimanager.client.IngestPayload;
import io.github.carloslimajlle.liquimanager.client.LqmClient;
import io.github.carloslimajlle.liquimanager.client.LqmConfig;
import io.github.carloslimajlle.liquimanager.collector.ChangeSetRecorder;
import io.github.carloslimajlle.liquimanager.collector.PolicyGate;
import io.github.carloslimajlle.liquimanager.collector.ReportBuilder;
import io.github.carloslimajlle.liquimanager.collector.RunInfo;
import io.github.carloslimajlle.liquimanager.policy.Severity;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;

/**
 * The Liquibase a service already runs at startup, with LiquiManager watching.
 *
 * <p>Everything Liquibase does is untouched: this class only calls {@code super.performUpdate}
 * between "look at the policies" and "say what happened". Anything that goes wrong in the
 * looking or the saying is swallowed and logged — a service must never fail to start because
 * LiquiManager is unwell (ADR-004). The two exceptions are deliberate and opt-in: a blocking
 * finding with {@code lqm.enforce=true}, and an unreachable LiquiManager with
 * {@code lqm.fail-closed=true}.
 */
public class LqmSpringLiquibase extends SpringLiquibase {

    private static final Logger LOG = Logger.getLogger(LqmSpringLiquibase.class.getName());

    private final LqmProperties lqm;
    private final String starterVersion;

    public LqmSpringLiquibase(LqmProperties lqm) {
        this.lqm = lqm;
        String version = LqmSpringLiquibase.class.getPackage().getImplementationVersion();
        this.starterVersion = version == null ? "dev" : version;
    }

    @Override
    protected void performUpdate(Liquibase liquibase) throws LiquibaseException {
        if (!lqm.isEnabled()) {
            super.performUpdate(liquibase);
            return;
        }

        LqmClient client = new LqmClient(new LqmConfig(lqm.getUrl(), lqm.getToken(), lqm.getTimeout(),
                lqm.getBufferDir() == null ? null : Paths.get(lqm.getBufferDir())));
        ChangeSetRecorder recorder = new ChangeSetRecorder();
        Contexts contexts = new Contexts(getContexts());
        LabelExpression labels = getLabelFilter() == null ? new LabelExpression()
                : new LabelExpression(getLabelFilter());

        ReportBuilder report = newReport(liquibase);
        Optional<PolicyGate.Verdict> verdict = Optional.empty();

        try {
            liquibase.setChangeExecListener(recorder);
            recorder.classify(liquibase, contexts, labels);
            verdict = gate(client, liquibase, contexts, labels);
        } catch (LqmPolicyViolationException e) {
            Optional<PolicyGate.Verdict> blocked = e instanceof BlockedStartup b ? b.verdict : Optional.empty();
            send(client, report, liquibase, recorder, blocked, "BLOCKED", e.getMessage());
            throw e;
        } catch (RuntimeException | LinkageError e) {
            LOG.log(Level.WARNING, "lqm: preparation failed; updating without LiquiManager: " + e);
        }

        try {
            super.performUpdate(liquibase);
        } catch (LiquibaseException | RuntimeException e) {
            send(client, report, liquibase, recorder, verdict, "FAILURE", rootMessage(e));
            throw e;
        }
        send(client, report, liquibase, recorder, verdict, recorder.anyFailed() ? "FAILURE" : "SUCCESS", null);
    }

    // ------------------------------------------------------------------- steps

    private Optional<PolicyGate.Verdict> gate(LqmClient client, Liquibase liquibase, Contexts contexts,
                                              LabelExpression labels) {
        if (!lqm.isEnforce() && !lqm.isFailClosed()) {
            return Optional.empty();
        }
        // fail-closed needs the proof that LiquiManager is up, so it never takes a cached answer for one.
        Optional<EffectivePolicies> policies = client.effectivePolicies(lqm.getProject(), lqm.getEnvironment(),
                getChangeLog(), !lqm.isFailClosed());
        if (policies.isEmpty()) {
            if (lqm.isFailClosed()) {
                throw new LqmPolicyViolationException(
                        "LiquiManager could not be reached and lqm.fail-closed=true: refusing to update");
            }
            LOG.warning("lqm: policies unavailable; updating without a gate");
            return Optional.empty();
        }
        if (!lqm.isEnforce()) {
            return Optional.empty();
        }
        Optional<PolicyGate.Verdict> verdict = PolicyGate.evaluate(
                liquibase, contexts, labels, policies.get(), lqm.getEnvironment(), Instant.now());
        if (verdict.map(PolicyGate.Verdict::blocked).orElse(false)) {
            StringBuilder message = new StringBuilder("blocked by LiquiManager policy:");
            verdict.get().findings().stream()
                    .filter(f -> f.severity() >= policies.get().blockThreshold())
                    .forEach(f -> message.append("\n  ").append(Severity.label(f.severity())).append(' ')
                            .append(f.checkCode()).append(": ").append(f.message()));
            throw new BlockedStartup(message.toString(), verdict);
        }
        return verdict;
    }

    /** Carries the verdict out of {@link #gate} so the BLOCKED report still includes the findings. */
    private static final class BlockedStartup extends LqmPolicyViolationException {
        final transient Optional<PolicyGate.Verdict> verdict;

        BlockedStartup(String message, Optional<PolicyGate.Verdict> verdict) {
            super(message);
            this.verdict = verdict;
        }
    }

    private ReportBuilder newReport(Liquibase liquibase) {
        Map<String, String> env = System.getenv();
        return new ReportBuilder(new RunInfo("update", "startup", lqm.getProject(), lqm.getEnvironment(),
                lqm.getConnectionKey(), getChangeLog(), blank(getContexts()), blank(getLabelFilter()), null, null,
                "starter", starterVersion, RunInfo.detectGit(env), RunInfo.detectCi(env)));
    }

    private void send(LqmClient client, ReportBuilder report, Liquibase liquibase, ChangeSetRecorder recorder,
                      Optional<PolicyGate.Verdict> verdict, String status, String error) {
        try {
            if (!client.isConfigured()) {
                return;
            }
            Optional<PolicyGate.Verdict> shown = verdict;
            String jdbcUrl = null;
            try {
                jdbcUrl = liquibase.getDatabase().getConnection().getURL();
            } catch (Exception | LinkageError ignored) {
                // the URL is a convenience for matching the connection; the key may be enough
            }
            IngestPayload payload = report.build(status, liquibase.getDatabase(), jdbcUrl, recorder, shown, error,
                    "SUCCESS".equals(status) ? null : 1, textLog(recorder), null);
            client.report(payload);
        } catch (RuntimeException | LinkageError e) {
            LOG.log(Level.WARNING, "lqm: could not report the operation: " + e);
        }
    }

    /** A short log made from what was recorded; the starter does not hook the application's logging. */
    private static String textLog(ChangeSetRecorder recorder) {
        StringBuilder log = new StringBuilder();
        recorder.changesets().forEach(c -> log.append(c.outcome()).append(' ').append(c.id()).append(" by ")
                .append(c.author()).append(c.durationMs() == null ? "" : " (" + c.durationMs() + " ms)")
                .append(c.error() == null ? "" : ": " + c.error()).append('\n'));
        return log.toString();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
