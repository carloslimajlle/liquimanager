package io.github.carloslimajlle.liquimanager.policy.builtin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.carloslimajlle.liquimanager.policy.CheckContext;
import io.github.carloslimajlle.liquimanager.policy.CheckScope;
import io.github.carloslimajlle.liquimanager.policy.Finding;
import io.github.carloslimajlle.liquimanager.policy.PolicyCheck;
import io.github.carloslimajlle.liquimanager.policy.Severity;
import liquibase.changelog.ChangeSet;

/**
 * More than N changes in a single changeset (default 1).
 *
 * <p>It matters because Liquibase records a changeset as one unit: with several
 * changes bundled together, a failure halfway through leaves the changeset
 * partially applied and targeted rollback loses its granularity.
 */
public final class OneChangePerChangeset implements PolicyCheck {

    @Override
    public String code() {
        return "OneChangePerChangeset";
    }

    @Override
    public CheckScope scope() {
        return CheckScope.CHANGELOG;
    }

    @Override
    public int defaultSeverity() {
        return Severity.MINOR;
    }

    @Override
    public List<Finding> evaluate(CheckContext ctx, Map<String, Object> params) {
        int max = Math.max(1, intParam(params, "max", 1));
        List<Finding> findings = new ArrayList<>();
        for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
            int count = changeSet.getChanges() == null ? 0 : changeSet.getChanges().size();
            if (count > max) {
                findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                        "O changeset %s tem %d changes; o limite e %d."
                                .formatted(changeSet.getId(), count, max)));
            }
        }
        return findings;
    }
}
