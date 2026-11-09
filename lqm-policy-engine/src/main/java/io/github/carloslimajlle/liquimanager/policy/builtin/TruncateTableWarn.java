package io.github.carloslimajlle.liquimanager.policy.builtin;

import io.github.carloslimajlle.liquimanager.policy.Severity;

/** {@code TRUNCATE}: wipes every row and cannot be undone through the changelog. */
public final class TruncateTableWarn extends SqlPatternCheck {
    public TruncateTableWarn() {
        super("TruncateTableWarn", Severity.BLOCKER,
                "\\btruncate\\s+table\\b",
                "faz TRUNCATE, que apaga todas as linhas e nao e reversivel pelo changelog.");
    }
}
