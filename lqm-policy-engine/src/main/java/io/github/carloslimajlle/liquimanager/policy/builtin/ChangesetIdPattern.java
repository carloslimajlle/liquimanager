package io.github.carloslimajlle.liquimanager.policy.builtin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import io.github.carloslimajlle.liquimanager.policy.CheckContext;
import io.github.carloslimajlle.liquimanager.policy.CheckScope;
import io.github.carloslimajlle.liquimanager.policy.Finding;
import io.github.carloslimajlle.liquimanager.policy.PolicyCheck;
import io.github.carloslimajlle.liquimanager.policy.Severity;
import liquibase.changelog.ChangeSet;

/**
 * Changeset id that does not follow the agreed pattern (D-16:
 * {@code SUP-NNNN-NNN-description}).
 *
 * <p>The pattern comes from the {@code pattern} parameter so each workspace can use
 * its own. An invalid regex does not break the evaluation: the check stays quiet.
 */
public final class ChangesetIdPattern implements PolicyCheck {

    static final String DEFAULT_PATTERN = "^SUP-\\d+-\\d{3}.*";

    @Override
    public String code() {
        return "ChangesetIdPattern";
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
        Pattern pattern;
        try {
            pattern = Pattern.compile(param(params, "pattern", DEFAULT_PATTERN));
        } catch (PatternSyntaxException e) {
            return List.of();
        }

        List<Finding> findings = new ArrayList<>();
        for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
            String id = changeSet.getId() == null ? "" : changeSet.getId();
            if (!pattern.matcher(id).matches()) {
                findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                        "O id \"%s\" nao segue o padrao %s.".formatted(id, pattern.pattern())));
            }
        }
        return findings;
    }
}
