package io.github.carloslimajlle.liquimanager.common;

/** Helpers over Liquibase's comma-separated {@code labels} string. */
public final class Labels {

    private Labels() {
    }

    /** First label starting with {@code release-}, or null. This is the release a changeset ships in. */
    public static String release(String labels) {
        if (labels == null) {
            return null;
        }
        for (String label : labels.split(",")) {
            String trimmed = label.trim();
            if (trimmed.startsWith("release-")) {
                return trimmed;
            }
        }
        return null;
    }

    /** True when any label is {@code break-fix}: a change applied outside the normal flow. */
    public static boolean isBreakFix(String labels) {
        if (labels == null) {
            return false;
        }
        for (String label : labels.split(",")) {
            if (label.trim().equalsIgnoreCase("break-fix")) {
                return true;
            }
        }
        return false;
    }
}
