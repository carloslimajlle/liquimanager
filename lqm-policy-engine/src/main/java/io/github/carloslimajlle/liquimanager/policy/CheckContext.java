package io.github.carloslimajlle.liquimanager.policy;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.database.Database;
import liquibase.snapshot.DatabaseSnapshot;

/**
 * Everything a check is allowed to look at. Shape defined in
 * docs/08-motor-de-politicas.md § 2.
 *
 * @param changelog             changelog already parsed by liquibase-core
 * @param changeSetsUnderReview the ones pending for this environment, or all of them
 * @param database              may be an offline connection, so SQL can be analysed without touching the server
 * @param generatedSql          SQL per changeset, already cached by the caller
 * @param snapshot              only present for DATABASE scope
 * @param environmentCode       DEV, TST, HML, PRD...
 * @param isProduction          turns on the stricter rules
 */
public record CheckContext(
        DatabaseChangeLog changelog,
        List<ChangeSet> changeSetsUnderReview,
        Database database,
        Function<ChangeSet, List<String>> generatedSql,
        Optional<DatabaseSnapshot> snapshot,
        String environmentCode,
        boolean isProduction) {

    public CheckContext {
        changeSetsUnderReview = changeSetsUnderReview == null ? List.of() : List.copyOf(changeSetsUnderReview);
        snapshot = snapshot == null ? Optional.empty() : snapshot;
        if (generatedSql == null) {
            generatedSql = cs -> List.of();
        }
    }

    /** Liquibase identity of a changeset: id::author::filePath. */
    public static String keyOf(ChangeSet changeSet) {
        return changeSet.getId() + "::" + changeSet.getAuthor() + "::" + changeSet.getFilePath();
    }

    /**
     * Generated SQL, lower-cased once, so pattern checks don't each repeat the conversion.
     *
     * <p>Statements are joined with a semicolon and a newline. Liquibase hands them over
     * WITHOUT their end delimiter, and several checks need to know where one statement
     * stops (an UPDATE with no WHERE must not borrow the WHERE of the next statement).
     * A stray double semicolon where a statement already ended in one is harmless to
     * every pattern.
     */
    public String lowerCaseSql(ChangeSet changeSet) {
        return String.join(";\n", generatedSql.apply(changeSet)).toLowerCase(Locale.ROOT);
    }
}
