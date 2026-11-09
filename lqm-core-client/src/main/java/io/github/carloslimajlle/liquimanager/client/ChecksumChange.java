package io.github.carloslimajlle.liquimanager.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A changeset that already ran in the environment and was edited afterwards, as {@code POST /policies/checksum-check} answers.
 *
 * @param appliedChecksum what the database holds
 * @param currentChecksum what the file hashes to now
 * @param sourceKnown     whether the lines that changed could be read from the repository
 * @param equivalent      the checksum differs but no line that counts does (comments are not part of it)
 * @param diff            the lines only in the applied version ({@code -}) and only in the current one ({@code +})
 * @param identityChanged the id was applied under another author or file ({@code appliedAuthor}, {@code appliedFilePath}), so to
 *                        Liquibase this is a new changeset
 * @param removed         the environment ran it and the changelog no longer declares it
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChecksumChange(String id, String author, String filePath, String appliedChecksum, String currentChecksum,
                             String appliedCommit, String connection, String environment, Boolean sourceKnown,
                             Boolean equivalent, List<Line> diff, Boolean identityChanged, String appliedAuthor,
                             String appliedFilePath, Boolean removed) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(String op, String text) {
    }

    /** One changeset as the caller sees it now. */
    public record Item(String id, String author, String filePath, String checksum) {
    }

    public List<Line> diff() {
        return diff == null ? List.of() : diff;
    }

    public boolean isEquivalent() {
        return Boolean.TRUE.equals(equivalent);
    }

    public boolean isIdentityChanged() {
        return Boolean.TRUE.equals(identityChanged);
    }

    public boolean isRemoved() {
        return Boolean.TRUE.equals(removed);
    }

    public boolean isSourceKnown() {
        return Boolean.TRUE.equals(sourceKnown);
    }
}
