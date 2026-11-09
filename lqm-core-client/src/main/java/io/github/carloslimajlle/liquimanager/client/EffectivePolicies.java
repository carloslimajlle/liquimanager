package io.github.carloslimajlle.liquimanager.client;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.github.carloslimajlle.liquimanager.policy.ActiveException;
import io.github.carloslimajlle.liquimanager.policy.EffectiveCheck;
import io.github.carloslimajlle.liquimanager.policy.EffectivePackage;

/** Response of {@code GET /policies/effective}, already in the engine's own types. */
public record EffectivePolicies(@JsonProperty("package") String name, String etag, int blockThreshold,
                                List<Check> checks, List<Exception> exceptions) {

    public record Check(String code, String kind, int severity, Map<String, Object> parameters,
                         String implementation, String message, List<Check> members) {

        public EffectiveCheck toEngine() {
            return new EffectiveCheck(code, severity, parameters, kind, implementation, message,
                    members == null ? List.of() : members.stream().map(Check::toEngine).toList());
        }
    }

    public record Exception(String check, String changeset, Instant expiresAt) {
    }

    public EffectivePackage toPackage() {
        return new EffectivePackage(name, blockThreshold, checks == null ? List.of()
                : checks.stream().map(Check::toEngine).toList());
    }

    public List<ActiveException> toExceptions() {
        return exceptions == null ? List.of()
                : exceptions.stream().map(e -> new ActiveException(e.check(), e.changeset(), e.expiresAt())).toList();
    }
}
