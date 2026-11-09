package io.github.carloslimajlle.liquimanager.client;

import java.util.List;

/**
 * Response of {@code GET /policies/promotion-gate}: whether a change may go to {@code env} only after the stage before it, and what that
 * stage already has.
 *
 * @param gated       false when the environment is not a later stage of any pipeline of the project: nothing to hold back
 * @param previousEnv the environment that has to have the change first (DEV before HML)
 * @param executed    the changesets LiquiManager knows are executed in that stage's database, each with the checksum that database ran
 *                    (absent when it is not known)
 */
public record PromotionGate(boolean gated, String previousEnv, List<Key> executed) {

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Key(String id, String author, String filePath, String checksum) {
    }

    public List<Key> executed() {
        return executed == null ? List.of() : executed;
    }
}
