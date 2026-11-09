package io.github.carloslimajlle.liquimanager.policy;

/** Outcome of running a policy package at a pipeline stage (docs/08 § 1). */
public enum GateResult {
    PASSED,
    WARNED,
    BLOCKED
}
