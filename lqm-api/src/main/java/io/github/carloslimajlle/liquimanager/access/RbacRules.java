package io.github.carloslimajlle.liquimanager.access;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Which permission each endpoint needs, in ONE ordered table.
 *
 * <p>A table instead of an annotation on every controller because the question "who can do what"
 * has to be answerable by reading one place, and because forgetting an annotation silently leaves an
 * endpoint open. Here, forgetting a row falls to the strictest default: an unknown write needs
 * {@code workspace:admin}, an unknown read needs {@code assignment:view}.
 *
 * <p>Paths are relative to {@code /api/v1}. The first matching row wins.
 */
public final class RbacRules {

    /** A requirement. {@code none} means "any authenticated caller". */
    public record Requirement(String permission) {
        public static final Requirement NONE = new Requirement(null);

        public boolean isNone() {
            return permission == null;
        }
    }

    private record Rule(Pattern method, Pattern path, String permission) {
        boolean matches(String m, String p) {
            return method.matcher(m).matches() && path.matcher(p).matches();
        }
    }

    private static final String WRITE = "POST|PUT|PATCH|DELETE";

    private static Rule rule(String method, String path, String permission) {
        return new Rule(Pattern.compile(method), Pattern.compile(path), permission);
    }

    private static final List<Rule> RULES = List.of(
            rule("GET", "/me", null),
            // Lists only what the caller could decide: the controller filters by permission and by who asked.
            rule("GET", "/me/pending", null),
            // Somebody with no access yet asks for it, and sees their own request; whoever administers access reads and sets aside the rest.
            rule("POST", "/access-requests", null),
            rule("GET", "/access-requests/mine", null),
            rule("GET", "/access-requests", "assignment:admin"),
            rule("POST", "/access-requests/[^/]+/dismiss", "assignment:admin"),
            // The emergency account signs in with its own credentials, before it has any identity.
            rule("POST", "/auth/break-glass(/logout)?", null),

            // Reading what the screens need to explain themselves is `view`; the dry-run only reads.
            rule("POST", "/policy-checks/[^/]+/test", "assignment:view"),

            // The permission depends on the environment of the operation (rollback:<env>): the controller checks it.
            rule("POST", "/operations/[^/]+/rollback-requests", null),
            rule("POST", "/rollback-requests/[^/]+/(approve|reject)", "rollback:approve"),
            // Withdrawing is the requester's own business; the controller refuses anybody else.
            rule("POST", "/rollback-requests/[^/]+/cancel", "assignment:view"),

            // promote:<env> depends on the stage being promoted to: the controller checks it.
            rule("POST", "/pipelines/[^/]+/stages/[^/]+/promote", null),
            // Deciding on a promotion needs promote:<env> of ITS stage, and cancelling needs being the requester: both are checked there.
            rule("POST", "/promotions/[^/]+/(approve|reject|cancel)", null),

            rule("POST", "/policy-exceptions", "exception:request"),
            rule("POST", "/policy-exceptions/[^/]+/(approve|reject|revoke)", "exception:approve"),

            rule(WRITE, "/policy-checks(/.*)?", "policy:edit"),
            rule(WRITE, "/policy-packages(/.*)?", "policy:edit"),

            rule("GET", "/audit/export", "audit:export"),
            rule(WRITE, "/access(/.*)?", "assignment:admin"),
            rule(WRITE, "/principals(/.*)?", "assignment:admin"),

            rule("POST", "/connections/[^/]+/drift-(runs|reference)", "drift:run"),
            rule("POST", "/connections/[^/]+/scan", "drift:run"),
            rule("POST", "/drift/runs/[^/]+/changelog", "drift:run"),
            rule("POST", "/drift/items/[^/]+/accept", "drift:accept"),

            rule(".*", "/notification-(rules|deliveries)(/.*)?", "workspace:admin"),
            rule("GET", "/secrets(/.*)?", "secret:view-status"),
            rule("POST", "/secrets/[^/]+/rotated", "connection:edit"),
            rule(".*", "/service-tokens(/.*)?", "token:manage"),
            rule(WRITE, "/connections(/.*)?", "connection:edit"),
            rule(WRITE, "/pipelines(/.*)?", "pipeline:edit"),
            rule(WRITE, "/(workspaces|environments)(/.*)?", "workspace:admin"),
            rule(WRITE, "/(teams|projects)(/.*)?", "catalog:edit"));

    private RbacRules() {
    }

    /** The requirement for a request, never empty: an unlisted route gets the strict default. */
    public static Requirement required(String method, String path) {
        String m = method.toUpperCase();
        for (Rule rule : RULES) {
            if (rule.matches(m, path)) {
                return rule.permission() == null ? Requirement.NONE : new Requirement(rule.permission());
            }
        }
        return new Requirement(m.equals("GET") || m.equals("HEAD") ? "assignment:view" : "workspace:admin");
    }

    /** First path segment after /projects/, to scope a binding to one project. */
    public static Optional<String> projectIdIn(String path) {
        java.util.regex.Matcher matcher = Pattern.compile("^/projects/([0-9a-fA-F-]{36})(/.*)?$").matcher(path);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
