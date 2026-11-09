package io.github.carloslimajlle.liquimanager.policy;

import java.util.List;

/**
 * The set of checks that applies to a project in an environment, already resolved
 * through the precedence stage &gt; changelog &gt; project &gt; workspace (docs/06).
 */
public record EffectivePackage(String name, int blockThreshold, List<EffectiveCheck> checks) {

    public EffectivePackage {
        Severity.validate(blockThreshold);
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    /** Default threshold by environment: CRITICAL in production, BLOCKER elsewhere. */
    public static EffectivePackage withDefaultThreshold(String name, boolean production,
                                                        List<EffectiveCheck> checks) {
        return new EffectivePackage(
                name,
                production ? Severity.BLOCK_THRESHOLD_PROD : Severity.BLOCK_THRESHOLD_NONPROD,
                checks);
    }
}
