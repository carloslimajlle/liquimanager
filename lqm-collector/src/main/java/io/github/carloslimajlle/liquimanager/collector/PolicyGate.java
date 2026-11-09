package io.github.carloslimajlle.liquimanager.collector;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.carloslimajlle.liquimanager.client.EffectivePolicies;
import io.github.carloslimajlle.liquimanager.client.IngestPayload;
import io.github.carloslimajlle.liquimanager.policy.CheckContext;
import io.github.carloslimajlle.liquimanager.policy.EvaluationResult;
import io.github.carloslimajlle.liquimanager.policy.Finding;
import io.github.carloslimajlle.liquimanager.policy.GateResult;
import io.github.carloslimajlle.liquimanager.policy.PolicyEngine;
import io.github.carloslimajlle.liquimanager.policy.Severity;
import io.github.carloslimajlle.liquimanager.policy.SqlGeneration;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;

/**
 * Runs the policy engine over the changesets a command is about to apply.
 *
 * <p>The engine is the same library the API uses (docs/04 § 3); this class only feeds it:
 * which changesets are pending, and the SQL each one would generate. Every failure here
 * yields an empty result — "no verdict" — because a gate that cannot be computed must not
 * stop a deploy (ADR-004).
 */
public final class PolicyGate {

    private static final Logger LOG = Logger.getLogger(PolicyGate.class.getName());

    /** Result of an evaluation: the engine's verdict and the findings in wire format. */
    public record Verdict(GateResult gate, List<Finding> findings, IngestPayload.Policy payload) {

        public boolean blocked() {
            return gate == GateResult.BLOCKED;
        }

        /**
         * The same verdict with findings the engine does not produce (the process checks, which need what only the server knows).
         * The gate is recomputed with the package's own rule: the worst finding against the block threshold.
         */
        public Verdict with(List<Finding> extra, EffectivePolicies policies) {
            if (extra.isEmpty()) {
                return this;
            }
            List<Finding> all = new java.util.ArrayList<>(findings);
            all.addAll(extra);
            int worst = extra.stream().mapToInt(Finding::severity).max().orElse(-1);
            GateResult next = gate;
            if (worst >= policies.blockThreshold()) {
                next = GateResult.BLOCKED;
            } else if (worst >= Severity.MAJOR && gate == GateResult.PASSED) {
                next = GateResult.WARNED;
            }
            List<IngestPayload.Finding> wire = new java.util.ArrayList<>(payload.findings());
            for (Finding f : extra) {
                String result = f.severity() >= policies.blockThreshold() ? "BLOCKED" : f.severity() >= Severity.MAJOR ? "WARNED" : "PASSED";
                wire.add(new IngestPayload.Finding(f.checkCode(), f.severity(), result, f.changesetKey(), f.message()));
            }
            return new Verdict(next, all, new IngestPayload.Policy(next.name(), wire));
        }
    }

    private PolicyGate() {
    }

    public static Optional<Verdict> evaluate(Liquibase liquibase, Contexts contexts, LabelExpression labels,
                                             EffectivePolicies policies, String environment, Instant now) {
        return evaluate(liquibase, contexts, labels, PendingScope.all(), policies, environment, now);
    }

    /** As above, judging only what {@code scope} says the command runs ({@code update-count}, {@code update-to-tag}). */
    public static Optional<Verdict> evaluate(Liquibase liquibase, Contexts contexts, LabelExpression labels, PendingScope scope,
                                             EffectivePolicies policies, String environment, Instant now) {
        try {
            Database database = liquibase.getDatabase();
            List<ChangeSet> pending = scope.narrow(liquibase.listUnrunChangeSets(contexts, labels), liquibase.getDatabaseChangeLog());
            Map<ChangeSet, List<String>> sqlCache = new HashMap<>();

            CheckContext ctx = new CheckContext(
                    liquibase.getDatabaseChangeLog(), pending, database,
                    cs -> sqlCache.computeIfAbsent(cs, c -> SqlGeneration.of(c, database)),
                    Optional.empty(), environment, "PRD".equalsIgnoreCase(environment));

            EvaluationResult result = PolicyEngine.withBuiltins().evaluate(
                    ctx, policies.toPackage(), policies.toExceptions(), now);

            return Optional.of(new Verdict(result.gate(), result.findings(),
                    toPayload(result, policies.blockThreshold())));
        } catch (Exception | LinkageError e) {
            LOG.log(Level.WARNING, "lqm: could not evaluate policies; no gate applied: " + e.getMessage());
            return Optional.empty();
        }
    }

    private static IngestPayload.Policy toPayload(EvaluationResult result, int threshold) {
        List<IngestPayload.Finding> findings = result.findings().stream().map(f -> {
            boolean waived = result.suppressed().contains(f);
            String verdict = waived ? "PASSED"
                    : f.severity() >= threshold ? "BLOCKED"
                    : f.severity() >= Severity.MAJOR ? "WARNED" : "PASSED";
            return new IngestPayload.Finding(f.checkCode(), f.severity(), verdict, f.changesetKey(), f.message());
        }).toList();
        return new IngestPayload.Policy(result.gate().name(), findings);
    }
}
