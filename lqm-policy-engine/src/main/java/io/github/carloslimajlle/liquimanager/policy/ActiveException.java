package io.github.carloslimajlle.liquimanager.policy;

import java.time.Instant;

/**
 * A live policy exception: temporary, approved permission for a check not to block.
 *
 * <p>Every exception has an expiry — there is no such thing as a permanent one
 * (docs/08 § 1). A null changeset means the exception covers every changeset in
 * scope.
 */
public record ActiveException(String checkCode, String changesetId, Instant expiresAt) {

    public ActiveException {
        if (checkCode == null || checkCode.isBlank()) {
            throw new IllegalArgumentException("checkCode is required");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("an exception without an expiry is not allowed");
        }
    }

    public boolean isLiveAt(Instant moment) {
        return moment.isBefore(expiresAt);
    }

    /** Does this exception cover the given finding? Matches the check and, when specific, the changeset. */
    public boolean covers(Finding finding) {
        if (!checkCode.equals(finding.checkCode())) {
            return false;
        }
        if (changesetId == null || changesetId.isBlank()) {
            return true;
        }
        String key = finding.changesetKey();
        return key != null && key.startsWith(changesetId + "::");
    }
}
