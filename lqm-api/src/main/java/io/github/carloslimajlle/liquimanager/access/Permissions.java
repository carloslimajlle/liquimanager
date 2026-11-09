package io.github.carloslimajlle.liquimanager.access;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * The permission language of docs/09 § 2, as pure functions.
 *
 * <p>A permission is {@code resource:what}. {@code what} is either an action ({@code view},
 * {@code approve}) or an environment list ({@code dev,tst}); a grant may use {@code *}. So a grant
 * {@code deploy:dev,tst} satisfies a requirement {@code deploy:tst}, and {@code rollback:*}
 * satisfies both {@code rollback:prd} and {@code rollback:approve}.
 *
 * <p>A denial beats a grant, wherever it comes from: if ANY denied permission covers what is
 * required, the answer is no, however many grants cover it.
 */
public final class Permissions {

    private Permissions() {
    }

    /** Does one granted (or denied) permission cover the required one? */
    public static boolean covers(String held, String required) {
        if (held == null || required == null) {
            return false;
        }
        String h = held.trim().toLowerCase(Locale.ROOT);
        String r = required.trim().toLowerCase(Locale.ROOT);
        if (h.equals(r)) {
            return true;
        }
        int hi = h.indexOf(':');
        int ri = r.indexOf(':');
        if (hi < 0 || ri < 0 || !h.substring(0, hi).equals(r.substring(0, ri))) {
            return false;
        }
        String heldWhat = h.substring(hi + 1);
        String requiredWhat = r.substring(ri + 1);
        return heldWhat.equals("*") || Arrays.asList(heldWhat.split(",")).contains(requiredWhat);
    }

    /** Granted and not denied. */
    public static boolean allowed(Collection<String> granted, Collection<String> denied, String required) {
        return granted.stream().anyMatch(g -> covers(g, required))
                && denied.stream().noneMatch(d -> covers(d, required));
    }

    /** What a binding really grants: its template and its extras, minus its own denials. */
    public static List<String> effective(Collection<String> template, Collection<String> extra, Collection<String> denied) {
        return java.util.stream.Stream.concat(template.stream(), extra.stream())
                .distinct()
                .filter(p -> denied.stream().noneMatch(d -> covers(d, p)))
                .toList();
    }

    /** SoD-3: deploying to production and approving exceptions in the same binding. */
    public static boolean violatesSod3(Collection<String> effective) {
        return effective.stream().anyMatch(p -> covers(p, "deploy:prd"))
                && effective.stream().anyMatch(p -> covers(p, "exception:approve"));
    }

    public static boolean isWellFormed(String permission) {
        return permission != null && permission.matches("^[a-z][a-z-]*:[a-z0-9*,:-]+$");
    }
}
