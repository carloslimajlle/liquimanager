package io.github.carloslimajlle.liquimanager.collector;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.carloslimajlle.liquimanager.client.IngestPayload;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.ChangeSetStatus;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.changelog.filter.ChangeSetFilterResult;
import liquibase.changelog.visitor.AbstractChangeExecListener;
import liquibase.database.Database;

/**
 * Records what Liquibase did to each changeset, for the report.
 *
 * <p>Two phases. {@link #classify} runs BEFORE the command and labels every changeset in the
 * changelog as pending, already applied, or filtered out (and why); that is what lets the
 * matrix say "skipped by the label filter" instead of staying silent. The listener callbacks
 * then turn the pending ones into EXECUTED or FAILED as Liquibase runs them, and whatever is
 * still pending when a run aborts stays NOT_EXECUTED.
 *
 * <p>Extends {@code AbstractChangeExecListener} rather than implementing the interface, so a
 * method Liquibase adds in a later version does not break a collector running inside a
 * service that brings its own.
 *
 * <p>Never throws into Liquibase: a recorder bug must not fail a deploy (ADR-004).
 */
public class ChangeSetRecorder extends AbstractChangeExecListener {

    private static final Logger LOG = Logger.getLogger(ChangeSetRecorder.class.getName());

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private int order;
    private boolean classified;

    /** True when {@link #classify} saw the whole changelog, so the report lists every changeset in it (and not just what ran). */
    public synchronized boolean classified() {
        return classified;
    }

    private static final class Entry {
        final ChangeSet changeSet;
        String outcome;
        String filterReason;
        Instant startedAt;
        Long durationMs;
        String error;
        String checksum;
        int position;

        Entry(ChangeSet changeSet) {
            this.changeSet = changeSet;
        }
    }

    /** Labels every changeset of the changelog before the command touches the database. */
    public synchronized void classify(Liquibase liquibase, Contexts contexts, LabelExpression labels) {
        classify(liquibase, contexts, labels, PendingScope.all());
    }

    /** As above; a pending changeset the command will not run (past the count, or after the tag) is {@code FILTERED}, not about to run. */
    public synchronized void classify(Liquibase liquibase, Contexts contexts, LabelExpression labels, PendingScope scope) {
        try {
            List<ChangeSetStatus> statuses = liquibase.getChangeSetStatuses(contexts, labels, true);
            List<ChangeSet> willRun = new java.util.ArrayList<>();
            for (ChangeSetStatus status : statuses) {
                if (status.getWillRun()) {
                    willRun.add(status.getChangeSet());
                }
            }
            java.util.Set<ChangeSet> inScope = new java.util.HashSet<>(scope.narrow(willRun, liquibase.getDatabaseChangeLog()));
            for (ChangeSetStatus status : statuses) {
                Entry entry = entry(status.getChangeSet());
                entry.checksum = status.getCurrentCheckSum() == null ? null : status.getCurrentCheckSum().toString();
                if (status.getWillRun() && !inScope.contains(status.getChangeSet())) {
                    entry.outcome = "FILTERED";
                    entry.filterReason = "scope";
                } else if (status.getWillRun()) {
                    entry.outcome = "NOT_EXECUTED";
                } else if (status.getPreviouslyRan()) {
                    entry.outcome = "PREVIOUSLY_RUN";
                } else {
                    entry.outcome = "FILTERED";
                    entry.filterReason = reasonOf(status);
                }
            }
            classified = true;
        } catch (Exception | LinkageError e) {
            // Without the pre-classification the report still has what actually ran.
            LOG.log(Level.WARNING, "lqm: could not classify changesets before the run: " + e.getMessage());
        }
    }

    private static String reasonOf(ChangeSetStatus status) {
        if (status.getFilterResults() == null) {
            return null;
        }
        for (ChangeSetFilterResult result : status.getFilterResults()) {
            String filter = result.getFilter() == null ? "" : result.getFilter().getSimpleName().toLowerCase(Locale.ROOT);
            if (filter.contains("context")) {
                return "context";
            }
            if (filter.contains("label")) {
                return "label";
            }
            if (filter.contains("dbms")) {
                return "dbms";
            }
            if (filter.contains("ignore")) {
                return "ignore";
            }
        }
        return null;
    }

    // ------------------------------------------------------------ Liquibase callbacks

    @Override
    public synchronized void willRun(ChangeSet changeSet, DatabaseChangeLog changeLog, Database database,
                                     ChangeSet.RunStatus runStatus) {
        guard(() -> {
            Entry entry = entry(changeSet);
            entry.startedAt = Instant.now();
            entry.position = ++order;
        });
    }

    @Override
    public synchronized void ran(ChangeSet changeSet, DatabaseChangeLog changeLog, Database database,
                                 ChangeSet.ExecType execType) {
        guard(() -> {
            Entry entry = entry(changeSet);
            entry.durationMs = elapsed(entry);
            // MARK_RAN and SKIPPED executed no SQL: the changeset is accounted for, not deployed.
            entry.outcome = execType == ChangeSet.ExecType.EXECUTED || execType == ChangeSet.ExecType.RERAN
                    ? "EXECUTED" : "PREVIOUSLY_RUN";
        });
    }

    @Override
    public synchronized void runFailed(ChangeSet changeSet, DatabaseChangeLog databaseChangeLog,
                                       Database database, Exception exception) {
        guard(() -> {
            Entry entry = entry(changeSet);
            entry.durationMs = elapsed(entry);
            entry.outcome = "FAILED";
            entry.error = exception == null ? null : rootMessage(exception);
        });
    }

    @Override
    public synchronized void rolledBack(ChangeSet changeSet, DatabaseChangeLog databaseChangeLog, Database database) {
        guard(() -> {
            Entry entry = entry(changeSet);
            entry.outcome = "ROLLED_BACK";
            entry.durationMs = elapsed(entry);
        });
    }

    // ----------------------------------------------------------------------- results

    public synchronized List<IngestPayload.Changeset> changesets() {
        List<IngestPayload.Changeset> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.outcome == null) {
                continue;
            }
            ChangeSet cs = e.changeSet;
            out.add(new IngestPayload.Changeset(
                    cs.getId(), cs.getAuthor(), cs.getFilePath(), e.checksum,
                    blankToNull(cs.getLabels() == null ? null : cs.getLabels().toString()),
                    blankToNull(cs.getContextFilter() == null ? null : cs.getContextFilter().toString()),
                    hasRollback(cs), e.outcome, e.filterReason, e.position > 0 ? e.position : null,
                    e.startedAt, e.durationMs, e.error));
        }
        return out;
    }

    public synchronized IngestPayload.Summary summary() {
        int executed = 0;
        int previously = 0;
        int filtered = 0;
        int failed = 0;
        for (Entry e : entries.values()) {
            if (e.outcome == null) {
                continue;
            }
            switch (e.outcome) {
                case "EXECUTED" -> executed++;
                case "PREVIOUSLY_RUN" -> previously++;
                case "FILTERED" -> filtered++;
                case "FAILED" -> failed++;
                default -> { }
            }
        }
        return new IngestPayload.Summary(executed, previously, filtered, failed);
    }

    public synchronized boolean anyFailed() {
        return entries.values().stream().anyMatch(e -> "FAILED".equals(e.outcome));
    }

    // ----------------------------------------------------------------------- helpers

    private Entry entry(ChangeSet changeSet) {
        return entries.computeIfAbsent(
                changeSet.getId() + "::" + changeSet.getAuthor() + "::" + changeSet.getFilePath(),
                k -> new Entry(changeSet));
    }

    private static Long elapsed(Entry entry) {
        return entry.startedAt == null ? null : Duration.between(entry.startedAt, Instant.now()).toMillis();
    }

    /** A changeset can be reverted when it has an explicit rollback or every change knows its inverse. */
    private static Boolean hasRollback(ChangeSet cs) {
        try {
            if (cs.hasCustomRollbackChanges()) {
                return Boolean.TRUE;
            }
            return cs.getChanges().stream().allMatch(c -> c.supportsRollback(null));
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null ? root.getClass().getSimpleName() : message;
    }

    private static void guard(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError e) {
            LOG.log(Level.WARNING, "lqm: recorder error ignored: " + e.getMessage());
        }
    }
}
