package io.github.carloslimajlle.liquimanager.policy;

import java.util.List;

/**
 * Outcome of evaluating a policy package.
 *
 * @param gate       PASSED, WARNED or BLOCKED
 * @param findings   every finding, including the ones waived by an exception
 * @param suppressed findings that only failed to block because a live exception
 *                   covered them. They still surface in the UI, so an exception
 *                   never turns into a blind spot.
 */
public record EvaluationResult(GateResult gate, List<Finding> findings, List<Finding> suppressed) {

    public EvaluationResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
        suppressed = suppressed == null ? List.of() : List.copyOf(suppressed);
    }

    public boolean isBlocked() {
        return gate == GateResult.BLOCKED;
    }

    /** Worst severity among the findings that actually count towards the gate. */
    public int worstSeverity() {
        return findings.stream()
                .filter(f -> !suppressed.contains(f))
                .mapToInt(Finding::severity)
                .max()
                .orElse(-1);
    }
}
