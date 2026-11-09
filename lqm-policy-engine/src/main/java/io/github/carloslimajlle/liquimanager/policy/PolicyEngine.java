package io.github.carloslimajlle.liquimanager.policy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.carloslimajlle.liquimanager.policy.builtin.CustomChecks;

/**
 * Evaluates a policy package against a context.
 *
 * <p>This class is the heart of the product and runs in three places — API, CLI
 * and starter — with identical results (docs/04 § 3). That is why it has no Spring
 * dependency, reads no configuration, and takes the current instant as a
 * parameter: nothing here may vary with the environment it runs in.
 *
 * <p>Gate rule (docs/08 § 2): take the worst severity among findings not waived by
 * an exception and compare it with the package threshold. At or above the
 * threshold it is BLOCKED; above MAJOR it is WARNED; otherwise PASSED.
 */
public final class PolicyEngine {

    private final Map<String, PolicyCheck> registry;

    public PolicyEngine(Collection<PolicyCheck> checks) {
        Map<String, PolicyCheck> byCode = new HashMap<>();
        for (PolicyCheck check : checks) {
            PolicyCheck previous = byCode.put(check.code(), check);
            if (previous != null) {
                throw new IllegalArgumentException("two checks share the same code: " + check.code());
            }
        }
        this.registry = Map.copyOf(byCode);
    }

    /** Engine loaded with every prebuilt check that is already implemented. */
    public static PolicyEngine withBuiltins() {
        return new PolicyEngine(BuiltinChecks.all());
    }

    public EvaluationResult evaluate(CheckContext ctx, EffectivePackage pkg,
                                     List<ActiveException> exceptions, Instant now) {

        List<Finding> findings = new ArrayList<>();
        for (EffectiveCheck effective : pkg.checks()) {
            findings.addAll(evaluateOne(ctx, effective));
        }

        List<ActiveException> live = exceptions == null ? List.of()
                : exceptions.stream().filter(e -> e.isLiveAt(now)).toList();

        List<Finding> waived = findings.stream()
                .filter(f -> live.stream().anyMatch(e -> e.covers(f)))
                .toList();

        int worst = findings.stream()
                .filter(f -> !waived.contains(f))
                .mapToInt(Finding::severity)
                .max()
                .orElse(-1);

        GateResult gate;
        if (worst >= pkg.blockThreshold()) {
            gate = GateResult.BLOCKED;
        } else if (worst >= Severity.MAJOR) {
            gate = GateResult.WARNED;
        } else {
            gate = GateResult.PASSED;
        }

        return new EvaluationResult(gate, findings, waived);
    }

    public EvaluationResult evaluate(CheckContext ctx, EffectivePackage pkg,
                                     List<ActiveException> exceptions) {
        return evaluate(ctx, pkg, exceptions, Instant.now());
    }

    /**
     * One package entry, with the package's severity and the entry's own code on every finding.
     * A check that has no implementation here (a catalogue entry not built yet) yields
     * nothing: it must not bring the whole evaluation down, and whether it deserves an alert
     * is for the layer above to decide.
     */
    private List<Finding> evaluateOne(CheckContext ctx, EffectiveCheck effective) {
        if (EffectiveCheck.CHAINED.equals(effective.kind())) {
            return evaluateChain(ctx, effective);
        }
        PolicyCheck check = implementationOf(effective);
        if (check == null) {
            return List.of();
        }
        List<Finding> out = new ArrayList<>();
        for (Finding raw : check.evaluate(ctx, effective.parameters())) {
            // The severity that counts is the package's, not the one the check returned.
            out.add(new Finding(effective.code(), effective.severity(), raw.changesetKey(),
                    raw.objectName(), raw.line(), raw.message()));
        }
        return out;
    }

    private PolicyCheck implementationOf(EffectiveCheck effective) {
        String implementation = effective.implementation() == null ? effective.code() : effective.implementation();
        PolicyCheck builtin = registry.get(implementation);
        if (builtin != null) {
            return builtin;
        }
        return CustomChecks.create(effective);
    }

    /**
     * A chained check runs its members and reports, per changeset, ONE finding carrying the
     * worst severity among them (docs/08 § 1). The chain's own severity is not used: the point of
     * chaining is that the members decide.
     */
    private List<Finding> evaluateChain(CheckContext ctx, EffectiveCheck chain) {
        Map<String, List<Finding>> byChangeset = new LinkedHashMap<>();
        for (EffectiveCheck member : chain.members()) {
            for (Finding finding : evaluateOne(ctx, member)) {
                byChangeset.computeIfAbsent(String.valueOf(finding.changesetKey()), k -> new ArrayList<>()).add(finding);
            }
        }
        List<Finding> out = new ArrayList<>();
        for (List<Finding> group : byChangeset.values()) {
            int worst = group.stream().mapToInt(Finding::severity).max().orElse(Severity.INFO);
            String detail = group.stream().map(f -> f.checkCode() + " (" + Severity.label(f.severity()) + ")")
                    .distinct().collect(java.util.stream.Collectors.joining(", "));
            out.add(new Finding(chain.code(), worst, group.get(0).changesetKey(), null, null,
                    chain.code() + ": " + detail));
        }
        return out;
    }

    /** Implemented check codes, so the API can warn when a package asks for one that does not exist. */
    public Set<String> implementedCodes() {
        return registry.keySet();
    }
}
