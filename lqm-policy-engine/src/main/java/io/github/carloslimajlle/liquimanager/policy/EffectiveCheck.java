package io.github.carloslimajlle.liquimanager.policy;

import java.util.List;
import java.util.Map;

/**
 * A check as it applies right here, right now: code, effective severity (package
 * override already applied) and effective parameters. This is what
 * {@code GET /policies/effective} hands to a collector.
 *
 * <p>A BUILTIN check is known to the engine by its code. Everything else is described here,
 * because the engine cannot have its code in advance:
 * <ul>
 *   <li>{@code implementation}: a customised COPY of a builtin has its own code but runs the
 *       parent's logic, so the engine looks this one up instead.
 *   <li>{@code kind} REGEX / SQL_PATTERN: the parameters are the whole rule, and
 *       {@code message} is the template of what a finding says.
 *   <li>{@code kind} CHAINED: {@code members} are evaluated and the worst severity wins.
 * </ul>
 *
 * @param kind           BUILTIN, REGEX, SQL_PATTERN, SPEL or CHAINED
 * @param implementation code of the builtin to run, when it differs from {@code code}; may be null
 * @param message        finding template for custom kinds, with variables like {@code <CHANGESET_ID>}; may be null
 * @param members        the chained checks; empty for every other kind
 */
public record EffectiveCheck(String code, int severity, Map<String, Object> parameters, String kind,
                             String implementation, String message, List<EffectiveCheck> members) {

    public static final String BUILTIN = "BUILTIN";
    public static final String REGEX = "REGEX";
    public static final String SQL_PATTERN = "SQL_PATTERN";
    public static final String SPEL = "SPEL";
    public static final String CHAINED = "CHAINED";

    public EffectiveCheck {
        Severity.validate(severity);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        kind = kind == null || kind.isBlank() ? BUILTIN : kind;
        members = members == null ? List.of() : List.copyOf(members);
    }

    /** A builtin with the given parameters: the form phase 1 used everywhere. */
    public EffectiveCheck(String code, int severity, Map<String, Object> parameters) {
        this(code, severity, parameters, BUILTIN, null, null, List.of());
    }

    public static EffectiveCheck of(String code, int severity) {
        return new EffectiveCheck(code, severity, Map.of());
    }
}
