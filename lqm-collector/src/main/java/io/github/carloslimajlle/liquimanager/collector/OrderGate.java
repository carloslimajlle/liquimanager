package io.github.carloslimajlle.liquimanager.collector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.carloslimajlle.liquimanager.client.IngestPayload;
import io.github.carloslimajlle.liquimanager.client.PromotionGate;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.change.CheckSum;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.ChangeSetStatus;

/**
 * The order of the environments (D-22), as the deploy applies it: what is about to run in HML has to have run in DEV first, <b>and as the
 * same version</b>. A changeset that is not there yet is held back; one that is there with another checksum is held back too: the file
 * was edited after DEV ran it, so HML would get something DEV never tested. A changeset that declares {@code validCheckSum} for what DEV
 * ran says the two are the same, and passes.
 *
 * <p>It only judges what the answer carries: a checksum the control plane does not have for the previous stage is not a difference
 * ("na dúvida, não acusa"). Whether LiquiManager answered at all is the caller's business (fail-open, ADR-004).
 */
public final class OrderGate {

    private static final Logger LOG = Logger.getLogger(OrderGate.class.getName());

    /** What stops the deploy: changesets the previous stage lacks, and changesets it ran in another version (each already worded). */
    public record Verdict(String previousEnv, List<String> missing, List<String> differing) {

        public boolean blocked() {
            return !missing.isEmpty() || !differing.isEmpty();
        }

        /** The sentence the deploy log and the blocked operation carry. */
        public String why() {
            List<String> parts = new ArrayList<>();
            if (!missing.isEmpty()) {
                parts.add("not in " + previousEnv + " yet: " + String.join(", ", missing)
                        + ". Apply it to " + previousEnv + " first (and make sure that deploy reports to LiquiManager).");
            }
            if (!differing.isEmpty()) {
                parts.add("not the version " + previousEnv + " ran: " + String.join(", ", differing)
                        + ". The file changed after it was applied there; promote what ran, or declare validCheckSum if the result is the same.");
            }
            return String.join(" ", parts);
        }
    }

    private OrderGate() {
    }

    /**
     * @param accepted whether a changeset declares the checksum the previous stage ran as valid ({@code validCheckSum}); a changeset
     *                 whose answer is true is not a difference
     */
    public static Verdict evaluate(PromotionGate gate, List<IngestPayload.Changeset> changesets,
                                   BiPredicate<IngestPayload.Changeset, String> accepted) {
        Map<String, PromotionGate.Key> have = new HashMap<>();
        gate.executed().forEach(key -> have.put(key(key.id(), key.author(), key.filePath()), key));
        List<String> missing = new ArrayList<>();
        List<String> differing = new ArrayList<>();
        for (IngestPayload.Changeset cs : changesets) {
            if (!"NOT_EXECUTED".equals(cs.outcome())) {
                continue;
            }
            PromotionGate.Key there = have.get(key(cs.id(), cs.author(), cs.filePath()));
            if (there == null) {
                missing.add(cs.id());
            } else if (there.checksum() != null && cs.checksum() != null && !there.checksum().equals(cs.checksum())
                    && !accepted.test(cs, there.checksum())) {
                differing.add(cs.id() + " (" + gate.previousEnv() + ": " + there.checksum() + ", here: " + cs.checksum() + ")");
            }
        }
        return new Verdict(gate.previousEnv(), missing, differing);
    }

    /** The {@code validCheckSum} test against the real changelog: does the changeset accept what the previous stage ran as its own? */
    public static BiPredicate<IngestPayload.Changeset, String> declaredValid(Liquibase liquibase, Contexts contexts, LabelExpression labels) {
        Map<String, ChangeSet> byKey = new HashMap<>();
        try {
            for (ChangeSetStatus status : liquibase.getChangeSetStatuses(contexts, labels, true)) {
                ChangeSet cs = status.getChangeSet();
                byKey.put(key(cs.getId(), cs.getAuthor(), cs.getFilePath()), cs);
            }
        } catch (Exception | LinkageError e) {
            LOG.log(Level.WARNING, "lqm: could not read validCheckSum declarations: " + e.getMessage());
        }
        return (changeset, applied) -> {
            ChangeSet cs = byKey.get(key(changeset.id(), changeset.author(), changeset.filePath()));
            try {
                return cs != null && applied != null && cs.isCheckSumValid(CheckSum.parse(applied));
            } catch (RuntimeException e) {
                return false;
            }
        };
    }

    private static String key(String id, String author, String filePath) {
        return id + "::" + author + "::" + filePath;
    }
}
