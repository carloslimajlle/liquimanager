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
 * {@code failOnError="false"} hides the failure: the deployment stays green and
 * the database is left in a state nobody checked. One of the few things that
 * deserve CRITICAL even when the changeset itself looks harmless.
 */
public final class FailOnErrorFalse implements PolicyCheck {

    @Override
    public String code() {
        return "FailOnErrorFalse";
    }

    @Override
    public CheckScope scope() {
        return CheckScope.CHANGELOG;
    }

    @Override
    public int defaultSeverity() {
        return Severity.CRITICAL;
    }

    @Override
    public List<Finding> evaluate(CheckContext ctx, Map<String, Object> params) {
        List<Finding> findings = new ArrayList<>();
        for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
            if (Boolean.FALSE.equals(changeSet.getFailOnError())) {
                findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                        ("O changeset %s usa failOnError=\"false\": uma falha passaria despercebida "
                                + "e o deploy seguiria como sucesso.").formatted(changeSet.getId())));
            }
        }
        return findings;
    }
}
