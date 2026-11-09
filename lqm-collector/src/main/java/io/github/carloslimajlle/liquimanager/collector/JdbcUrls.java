package io.github.carloslimajlle.liquimanager.collector;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips credentials from a JDBC URL before it leaves the machine (RNF-06).
 *
 * <p>The API refuses a URL that carries them, so a collector that forgot would lose its
 * report; it is better to remove them here than to depend on the URL being clean.
 */
public final class JdbcUrls {

    /** user, password and their aliases, as a query parameter (?a=b&amp;) or a property (;a=b). */
    private static final Pattern CREDENTIAL_PARAM = Pattern.compile(
            "(?i)([;&?])(user|username|password|pwd|passwd)=[^;&]*");
    /** //user:pass@host */
    private static final Pattern USERINFO = Pattern.compile("(?i)(//)[^/@;?]+@");

    private JdbcUrls() {
    }

    public static String sanitize(String url) {
        if (url == null) {
            return null;
        }
        String clean = USERINFO.matcher(url).replaceAll("$1");
        boolean firstQuery = false;
        Matcher m = CREDENTIAL_PARAM.matcher(clean);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            firstQuery = firstQuery || "?".equals(m.group(1));
            m.appendReplacement(out, "");
        }
        m.appendTail(out);
        String result = out.toString();
        // If a credential was the first query parameter, the next one must take over the "?".
        if (firstQuery && result.indexOf('?') < 0 && result.contains("&")) {
            result = result.replaceFirst("&", "?");
        }
        return result;
    }
}
