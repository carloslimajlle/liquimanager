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

/** Changeset without a {@code <comment>}: makes later auditing harder than it needs to be. */
public final class ChangesetCommentRequired implements PolicyCheck {

    @Override
    public String code() {
        return "ChangesetCommentRequired";
    }

    @Override
    public CheckScope scope() {
        return CheckScope.CHANGELOG;
    }

    @Override
    public int defaultSeverity() {
        return Severity.INFO;
    }

    @Override
    public List<Finding> evaluate(CheckContext ctx, Map<String, Object> params) {
        List<Finding> findings = new ArrayList<>();
        for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
            String comment = changeSet.getComments();
            if (comment == null || comment.isBlank()) {
                findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                        "O changeset %s nao tem <comment>. Explique o porque da mudanca."
                                .formatted(changeSet.getId())));
            }
        }
        return findings;
    }
}
