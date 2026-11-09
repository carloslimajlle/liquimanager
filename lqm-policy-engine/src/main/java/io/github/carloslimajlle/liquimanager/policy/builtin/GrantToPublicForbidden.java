package io.github.carloslimajlle.liquimanager.policy.builtin;

import io.github.carloslimajlle.liquimanager.policy.Severity;

/** {@code GRANT ... TO PUBLIC}: opens the object to every login on the server. */
public final class GrantToPublicForbidden extends SqlPatternCheck {
    public GrantToPublicForbidden() {
        super("GrantToPublicForbidden", Severity.BLOCKER,
                "\\bgrant\\b.{0,200}?\\bto\\s+public\\b",
                "faz GRANT ... TO PUBLIC, liberando o objeto para qualquer login do servidor.");
    }
}
