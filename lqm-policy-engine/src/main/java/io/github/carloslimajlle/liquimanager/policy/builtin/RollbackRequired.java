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
 * Changeset with no usable rollback.
 *
 * <p>Both an explicit {@code <rollback>} block and the automatic rollback Liquibase
 * derives on its own count — a {@code createTable} already knows how to undo
 * itself. That is why the question goes to {@code supportsRollback} instead of
 * only inspecting the block: demanding an explicit rollback for a
 * {@code createTable} would be noise.
 */
public final class RollbackRequired implements PolicyCheck {

    @Override
    public String code() {
        return "RollbackRequired";
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
            if (!hasRollback(changeSet, ctx)) {
                findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                        "O changeset %s nao tem rollback e nao e auto-reversivel. Adicione um bloco <rollback>."
                                .formatted(changeSet.getId())));
            }
        }
        return findings;
    }

    static boolean hasRollback(ChangeSet changeSet, CheckContext ctx) {
        if (changeSet.getRollback() != null && !changeSet.getRollback().getChanges().isEmpty()) {
            return true;
        }
        try {
            return changeSet.supportsRollback(ctx.database());
        } catch (RuntimeException e) {
            // If we cannot tell, do not report: a false positive here blocks a
            // deployment (ADR-004 — when in doubt, let it through).
            return true;
        }
    }
}
