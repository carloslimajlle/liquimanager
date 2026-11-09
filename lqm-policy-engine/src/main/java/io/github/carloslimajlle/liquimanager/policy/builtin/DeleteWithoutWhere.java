package io.github.carloslimajlle.liquimanager.policy.builtin;

import io.github.carloslimajlle.liquimanager.policy.Severity;

/**
 * {@code DELETE} with no {@code WHERE}.
 *
 * <p>The regex walks to the end of the statement without ever meeting a WHERE. It
 * makes no attempt to understand SQL: a false negative on very exotic SQL is far
 * better than blocking a legitimate deployment (ADR-004).
 */
public final class DeleteWithoutWhere extends SqlPatternCheck {
    public DeleteWithoutWhere() {
        super("DeleteWithoutWhere", Severity.BLOCKER,
                "\\bdelete\\s+from\\b(?:(?!\\bwhere\\b)[^;])*(?:;|$)",
                "faz DELETE sem WHERE, o que apaga a tabela inteira.");
    }
}
