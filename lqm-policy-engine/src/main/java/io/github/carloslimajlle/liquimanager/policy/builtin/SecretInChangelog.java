package io.github.carloslimajlle.liquimanager.policy.builtin;

import io.github.carloslimajlle.liquimanager.policy.Severity;

/**
 * A password, token or connection string sitting inside the changeset.
 *
 * <p>RNF-06: no credential may be version-controlled. A secret that reaches Git has
 * already leaked, even if removed later — hence BLOCKER rather than a warning.
 */
public final class SecretInChangelog extends SqlPatternCheck {
    public SecretInChangelog() {
        super("SecretInChangelog", Severity.BLOCKER,
                "(?:password|pwd|senha|secret|api[_-]?key|token)\\s*(?:=|:)\\s*['\"][^'\"]{4,}['\"]",
                "parece conter uma credencial em texto. Use referencia a segredo (Vault), nunca o valor.");
    }
}
