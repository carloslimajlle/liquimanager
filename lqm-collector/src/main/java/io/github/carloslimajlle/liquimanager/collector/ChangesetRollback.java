package io.github.carloslimajlle.liquimanager.collector;

import java.util.ArrayList;
import java.util.List;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeLogHistoryService;
import liquibase.changelog.ChangeLogHistoryServiceFactory;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.RanChangeSet;
import liquibase.changelog.visitor.ChangeExecListener;
import liquibase.database.Database;
import liquibase.exception.LiquibaseException;
import liquibase.lockservice.LockService;
import liquibase.lockservice.LockServiceFactory;

/**
 * {@code rollback-one-changeset}: reverts ONE changeset, whichever its position in the history. The Liquibase
 * command of that name is a paid feature, so it is built here from the core API (docs/04 § 4.4): find the
 * changeset, ask it to roll itself back, and take its row out of DATABASECHANGELOG.
 *
 * <p>It refuses, with a message that says why, instead of guessing: a changeset that is not in the changelog,
 * that was never applied here, that has nothing to revert it with, or whose identity matches more than one.
 * Reverting the wrong thing in production is worse than not reverting.
 *
 * <p>This uses three internals that move between Liquibase versions ({@code ChangeSet.rollback},
 * {@code ChangeLogHistoryService.removeFromHistory} and the lock service); the version is pinned for that
 * reason (D-17), and a test runs the whole sequence against a real database.
 */
public final class ChangesetRollback {

    private ChangesetRollback() {
    }

    /** Why the rollback did not happen, in terms a person can act on. */
    public static final class Refused extends LiquibaseException {
        public Refused(String message) {
            super(message);
        }
    }

    /**
     * @param filePath the changeset's file, or null when the id and the author are enough
     * @param listener told about the rollback (the recorder, so the report says ROLLED_BACK)
     */
    public static void run(Liquibase liquibase, String id, String author, String filePath, ChangeExecListener listener)
            throws LiquibaseException {
        Database database = liquibase.getDatabase();
        ChangeSet changeSet = find(liquibase, id, author, filePath);

        LockService lock = LockServiceFactory.getInstance().getLockService(database);
        lock.waitForLock();
        try {
            ChangeLogHistoryService history = ChangeLogHistoryServiceFactory.getInstance().getChangeLogService(database);
            history.init();
            if (!wasApplied(history, changeSet)) {
                throw new Refused("changeset " + label(changeSet) + " was never applied to this database");
            }
            if (!canRollback(changeSet, database)) {
                throw new Refused("changeset " + label(changeSet)
                        + " has no rollback and is not automatically reversible; add a <rollback> block");
            }
            changeSet.rollback(database, listener);
            history.removeFromHistory(changeSet);
            database.commit();
            // ChangeSet.rollback does not tell the listener (the rollback visitor does, and it is not in play here).
            if (listener != null) {
                listener.rolledBack(changeSet, liquibase.getDatabaseChangeLog(), database);
            }
        } finally {
            lock.releaseLock();
        }
    }

    /**
     * What "go back to this changeset" would undo, in the order it would be undone: the changeset itself and every changeset applied
     * after it, newest first, as Liquibase's own {@code rollbackCount} does (never one in the middle, which would leave a later
     * changeset standing on something that is gone). Each entry is {@code id::author::filePath}.
     *
     * <p>Refuses, saying why, when the changeset is not applied here, when something to undo is no longer in the changelog, or
     * when something to undo has no rollback: all of it or nothing.
     */
    public static List<String> planTo(Liquibase liquibase, String id, String author, String filePath) throws LiquibaseException {
        Database database = liquibase.getDatabase();
        ChangeSet target = find(liquibase, id, author, filePath);
        ChangeLogHistoryService history = ChangeLogHistoryServiceFactory.getInstance().getChangeLogService(database);
        history.init();
        List<RanChangeSet> ran = history.getRanChangeSets();
        int from = -1;
        for (int i = 0; i < ran.size(); i++) {
            if (ran.get(i).isSameAs(target)) {
                from = i;
                break;
            }
        }
        if (from < 0) {
            throw new Refused("changeset " + label(target) + " was never applied to this database");
        }
        List<String> plan = new ArrayList<>();
        List<String> without = new ArrayList<>();
        for (int i = ran.size() - 1; i >= from; i--) {
            ChangeSet changeSet = changesetOf(liquibase, ran.get(i));
            if (changeSet == null) {
                throw new Refused("changeset " + ran.get(i).getId() + "::" + ran.get(i).getAuthor()
                        + " is applied here but is not in the changelog at this commit, so it cannot be reverted");
            }
            if (!canRollback(changeSet, database)) {
                without.add(label(changeSet));
            }
            plan.add(label(changeSet));
        }
        if (!without.isEmpty()) {
            throw new Refused("cannot go back to " + label(target) + ": no rollback and not automatically reversible: "
                    + String.join(", ", without));
        }
        return plan;
    }

    /**
     * Goes back to a changeset, itself included: Liquibase's {@code rollbackCount} for exactly the changesets {@link #planTo} lists, so
     * the order is the one Liquibase would use. When {@code expected} is given (what the person saw when they asked), a database
     * that moved since then (something applied or undone in between) is refused instead of undoing something nobody looked at.
     */
    public static List<String> runTo(Liquibase liquibase, String id, String author, String filePath, List<String> expected,
                                     Contexts contexts, LabelExpression labels) throws LiquibaseException {
        List<String> plan = planTo(liquibase, id, author, filePath);
        if (expected != null && !expected.isEmpty() && !expected.equals(plan)) {
            throw new Refused("the database changed since the rollback was requested: it would now undo " + plan.size()
                    + " changesets (" + String.join(", ", plan) + ") and the request was for " + expected.size()
                    + "; request it again to see what it would undo now");
        }
        liquibase.rollback(plan.size(), null, contexts, labels);
        return plan;
    }

    private static ChangeSet changesetOf(Liquibase liquibase, RanChangeSet ran) throws LiquibaseException {
        for (ChangeSet candidate : liquibase.getDatabaseChangeLog().getChangeSets()) {
            if (ran.isSameAs(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static ChangeSet find(Liquibase liquibase, String id, String author, String filePath) throws LiquibaseException {
        List<ChangeSet> found = new ArrayList<>();
        for (ChangeSet candidate : liquibase.getDatabaseChangeLog().getChangeSets()) {
            boolean same = candidate.getId().equals(id) && (author == null || candidate.getAuthor().equals(author));
            if (same && (filePath == null || candidate.getFilePath().equals(filePath))) {
                found.add(candidate);
            }
        }
        if (found.isEmpty()) {
            throw new Refused("changeset " + id + " is not in the changelog");
        }
        if (found.size() > 1) {
            throw new Refused("more than one changeset matches " + id + "; say the author and the file path");
        }
        return found.get(0);
    }

    private static boolean wasApplied(ChangeLogHistoryService history, ChangeSet changeSet) throws LiquibaseException {
        for (RanChangeSet ran : history.getRanChangeSets()) {
            if (ran.isSameAs(changeSet)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canRollback(ChangeSet changeSet, Database database) {
        try {
            if (changeSet.getRollback() != null && !changeSet.getRollback().getChanges().isEmpty()) {
                return true;
            }
            return changeSet.supportsRollback(database);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** {@code id::author::filePath}: how a changeset is named in a plan. */
    public static String label(ChangeSet changeSet) {
        return changeSet.getId() + "::" + changeSet.getAuthor() + "::" + changeSet.getFilePath();
    }
}
