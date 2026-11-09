package io.github.carloslimajlle.liquimanager.policy.builtin;

import io.github.carloslimajlle.liquimanager.policy.Severity;

/** {@code DROP TABLE}: irreversible data loss without a backup. */
public final class DropTableWarn extends SqlPatternCheck {
    public DropTableWarn() {
        super("DropTableWarn", Severity.CRITICAL,
                "\\bdrop\\s+table\\b",
                "faz DROP TABLE. Confirme que ha backup e que o rollback recria os dados, nao so a estrutura.");
    }
}
