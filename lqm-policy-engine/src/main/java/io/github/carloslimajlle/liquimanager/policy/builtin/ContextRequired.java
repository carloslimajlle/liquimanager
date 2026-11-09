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
 * Changeset with no context declared.
 *
 * <p>In services that use the starter the Liquibase context is the active Spring profile
 * ({@code contexts: ${spring.profiles.active}}), so a changeset without a context
 * runs in EVERY environment — production included, by accident. That is why this
 * is MAJOR and not cosmetic.
 */
public final class ContextRequired implements PolicyCheck {

    @Override
    public String code() {
        return "ContextRequired";
    }

    @Override
    public CheckScope scope() {
        return CheckScope.CHANGELOG;
    }

    @Override
    public int defaultSeverity() {
        return Severity.MAJOR;
    }

    @Override
    public List<Finding> evaluate(CheckContext ctx, Map<String, Object> params) {
        List<Finding> findings = new ArrayList<>();
        for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
            if (hasNoContext(changeSet)) {
                findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                        ("O changeset %s nao declara context e por isso roda em todos os ambientes, "
                                + "inclusive PRD.").formatted(changeSet.getId())));
            }
        }
        return findings;
    }

    private static boolean hasNoContext(ChangeSet changeSet) {
        var filter = changeSet.getContextFilter();
        return filter == null || filter.isEmpty();
    }
}
