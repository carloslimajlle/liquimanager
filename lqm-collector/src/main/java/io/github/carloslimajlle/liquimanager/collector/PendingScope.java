package io.github.carloslimajlle.liquimanager.collector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import liquibase.change.Change;
import liquibase.change.core.TagDatabaseChange;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;

/**
 * Which of the pending changesets a command is about to run. {@code update} runs all of them, {@code update-count} the first few and
 * {@code update-to-tag} the ones up to a tag; the policy gate, the order of the environments and the report have to judge exactly those,
 * not everything that is pending, or a changeset the command will never touch could block it.
 */
public interface PendingScope {

    /** The pending changesets (in the order Liquibase would run them) narrowed to what this command runs. */
    List<ChangeSet> narrow(List<ChangeSet> pending, DatabaseChangeLog changeLog);

    /** Every pending changeset: what {@code update} runs. */
    static PendingScope all() {
        return (pending, changeLog) -> pending;
    }

    /** The first {@code count} pending changesets ({@code update-count}). */
    static PendingScope first(int count) {
        return (pending, changeLog) -> new ArrayList<>(pending.subList(0, Math.max(0, Math.min(count, pending.size()))));
    }

    /**
     * The pending changesets up to and including the one that tags the database with {@code tag} ({@code update-to-tag}). A tag the
     * changelog does not have is an error, as it is for Liquibase: it would otherwise run nothing and look fine.
     */
    static PendingScope upToTag(String tag) {
        return (pending, changeLog) -> {
            List<ChangeSet> ordered = changeLog.getChangeSets();
            int last = -1;
            for (int i = 0; i < ordered.size() && last < 0; i++) {
                for (Change change : ordered.get(i).getChanges()) {
                    if (change instanceof TagDatabaseChange t && tag.equals(t.getTag())) {
                        last = i;
                    }
                }
            }
            if (last < 0) {
                throw new IllegalArgumentException("the tag " + tag + " is not in the changelog");
            }
            Set<ChangeSet> upTo = new HashSet<>(ordered.subList(0, last + 1));
            List<ChangeSet> out = new ArrayList<>();
            for (ChangeSet cs : pending) {
                if (upTo.contains(cs)) {
                    out.add(cs);
                }
            }
            return out;
        };
    }
}
