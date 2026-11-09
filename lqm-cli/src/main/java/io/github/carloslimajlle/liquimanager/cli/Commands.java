package io.github.carloslimajlle.liquimanager.cli;

import java.time.Duration;
import java.time.Instant;

import io.github.carloslimajlle.liquimanager.collector.ChangesetRollback;
import io.github.carloslimajlle.liquimanager.collector.PendingScope;
import liquibase.changelog.ChangeSet;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** The commands, one small class each; the template in {@link LiquibaseCommand} does the rest. */
final class Commands {

    private Commands() {
    }

    @Command(name = "update", mixinStandardHelpOptions = true,
            description = "Applies the pending changesets, after the policy gate.")
    static final class Update extends LiquibaseCommand {

        @Option(names = "--tag", description = "Tag the database after a successful update.")
        String tagAfter;

        @Override
        String commandName() {
            return "update";
        }

        @Override
        String tagName() {
            return tagAfter;
        }

        @Override
        boolean gated() {
            return true;
        }

        @Override
        void perform(Context ctx) throws Exception {
            Instant t0 = Instant.now();
            ctx.liquibase.update(ctx.contexts, ctx.labels);
            ctx.output.line("PASS", "update", ctx.recorder.summary().executed() + " changesets applied",
                    Duration.between(t0, Instant.now()));
            if (tagAfter != null && !tagAfter.isBlank()) {
                Instant t1 = Instant.now();
                ctx.liquibase.tag(tagAfter);
                ctx.output.line("PASS", "tag", tagAfter, Duration.between(t1, Instant.now()));
            }
        }
    }

    @Command(name = "update-count", mixinStandardHelpOptions = true,
            description = "Applies the next N pending changesets, after the policy gate (which judges only those N).")
    static final class UpdateCount extends LiquibaseCommand {

        @Option(names = "--count", required = true, description = "How many pending changesets to apply (at least 1).")
        int count;

        @Override
        String commandName() {
            return "update-count";
        }

        @Override
        boolean gated() {
            return true;
        }

        @Override
        PendingScope pendingScope() {
            return PendingScope.first(count);
        }

        @Override
        String missingOption() {
            return count < 1 ? "--count (at least 1)" : super.missingOption();
        }

        @Override
        void perform(Context ctx) throws Exception {
            Instant t0 = Instant.now();
            ctx.liquibase.update(count, ctx.contexts, ctx.labels);
            ctx.output.line("PASS", "update-count", ctx.recorder.summary().executed() + " changesets applied",
                    Duration.between(t0, Instant.now()));
        }
    }

    @Command(name = "update-to-tag", mixinStandardHelpOptions = true,
            description = "Applies the pending changesets up to the one that tags the database, after the policy gate "
                    + "(which judges only those).")
    static final class UpdateToTag extends LiquibaseCommand {

        @Option(names = "--tag", required = true, description = "The tag to stop at: the changeset that sets it is applied too.")
        String tag;

        @Override
        String commandName() {
            return "update-to-tag";
        }

        @Override
        boolean gated() {
            return true;
        }

        @Override
        PendingScope pendingScope() {
            return PendingScope.upToTag(tag);
        }

        @Override
        void prepare(liquibase.Liquibase liquibase) throws Exception {
            // A tag that is not in the changelog would run nothing (or everything) and look fine: say so before the gate.
            PendingScope.upToTag(tag).narrow(java.util.List.of(), liquibase.getDatabaseChangeLog());
        }

        @Override
        void perform(Context ctx) throws Exception {
            Instant t0 = Instant.now();
            ctx.liquibase.update(tag, ctx.contexts, ctx.labels);
            ctx.output.line("PASS", "update-to-tag", ctx.recorder.summary().executed() + " changesets applied up to " + tag,
                    Duration.between(t0, Instant.now()));
        }
    }

    @Command(name = "update-sql", mixinStandardHelpOptions = true,
            description = "Prints the SQL an update would run, without running it.")
    static final class UpdateSql extends LiquibaseCommand {

        @Override
        String commandName() {
            return "update-sql";
        }

        @Override
        void perform(Context ctx) throws Exception {
            Instant t0 = Instant.now();
            ctx.liquibase.update(ctx.contexts, ctx.labels, ctx.sql);
            ctx.output.line("PASS", "update-sql", "SQL generated", Duration.between(t0, Instant.now()));
            ctx.out.println(ctx.sqlText() == null ? "-- nothing to apply" : ctx.sqlText());
            String artifact = options.artifact == null || options.artifact.isBlank() ? null : options.artifact;
            if (artifact != null) {
                java.nio.file.Path target = java.nio.file.Path.of(artifact);
                if (target.getParent() != null) {
                    java.nio.file.Files.createDirectories(target.getParent());
                }
                java.nio.file.Files.writeString(target, ctx.sqlText() == null ? "-- nothing to apply\n" : ctx.sqlText());
            }
        }
    }

    @Command(name = "status", mixinStandardHelpOptions = true,
            description = "Lists the changesets that have not been applied.")
    static final class Status extends LiquibaseCommand {

        @Override
        String commandName() {
            return "status";
        }

        @Override
        void perform(Context ctx) throws Exception {
            Instant t0 = Instant.now();
            var pending = ctx.liquibase.listUnrunChangeSets(ctx.contexts, ctx.labels);
            ctx.output.line("PASS", "status", pending.size() + " changesets pending",
                    Duration.between(t0, Instant.now()));
            for (ChangeSet cs : pending) {
                ctx.output.detail(cs.getId() + " by " + cs.getAuthor() + "  " + cs.getFilePath());
            }
        }
    }

    @Command(name = "history", mixinStandardHelpOptions = true,
            description = "Lists what DATABASECHANGELOG says was applied, oldest first.")
    static final class History extends LiquibaseCommand {

        @Override
        String commandName() {
            return "history";
        }

        @Override
        boolean needsChangelog() {
            return false;
        }

        @Override
        void perform(Context ctx) throws Exception {
            Instant t0 = Instant.now();
            var ran = ctx.liquibase.getDatabase().getRanChangeSetList();
            ctx.output.line("PASS", "history", ran.size() + " changesets applied", Duration.between(t0, Instant.now()));
            for (var changeSet : ran) {
                ctx.output.detail(changeSet.getId() + " by " + changeSet.getAuthor() + "  " + changeSet.getChangeLog()
                        + "  " + changeSet.getDateExecuted() + "  " + changeSet.getExecType()
                        + (changeSet.getTag() == null ? "" : "  tag " + changeSet.getTag()));
            }
        }
    }

    @Command(name = "tag", mixinStandardHelpOptions = true, description = "Tags the current database state.")
    static final class Tag extends LiquibaseCommand {

        @Parameters(index = "0", paramLabel = "TAG")
        String name;

        @Override
        String commandName() {
            return "tag";
        }

        @Override
        String tagName() {
            return name;
        }

        @Override
        boolean needsChangelog() {
            return false;
        }

        @Override
        void perform(Context ctx) throws Exception {
            Instant t0 = Instant.now();
            ctx.liquibase.tag(name);
            ctx.output.line("PASS", "tag", name, Duration.between(t0, Instant.now()));
        }
    }

    @Command(name = "rollback", mixinStandardHelpOptions = true,
            description = "Reverts the database to a tag. Every changeset needs an explicit rollback.")
    static final class Rollback extends LiquibaseCommand {

        @Option(names = "--tag", required = true, description = "Tag to return to.")
        String tag;

        @Override
        String commandName() {
            return "rollback";
        }

        @Override
        String tagName() {
            return tag;
        }

        @Override
        void perform(Context ctx) throws Exception {
            Instant t0 = Instant.now();
            ctx.liquibase.rollback(tag, null, ctx.contexts, ctx.labels);
            ctx.output.line("PASS", "rollback", "back to " + tag, Duration.between(t0, Instant.now()));
        }
    }

    @Command(name = "rollback-count", mixinStandardHelpOptions = true,
            description = "Reverts the last N applied changesets. Every one needs an explicit rollback.")
    static final class RollbackCount extends LiquibaseCommand {

        @Option(names = "--count", required = true, description = "How many applied changesets to revert (at least 1).")
        int count;

        @Override
        String commandName() {
            return "rollback-count";
        }

        @Override
        String missingOption() {
            return count < 1 ? "--count (at least 1)" : super.missingOption();
        }

        @Override
        void perform(Context ctx) throws Exception {
            Instant t0 = Instant.now();
            ctx.liquibase.rollback(count, null, ctx.contexts, ctx.labels);
            ctx.output.line("PASS", "rollback-count", "reverted the last " + count, Duration.between(t0, Instant.now()));
        }
    }

    @Command(name = "rollback-to-date", mixinStandardHelpOptions = true,
            description = "Reverts what was applied after a date. Every changeset needs an explicit rollback.")
    static final class RollbackToDate extends LiquibaseCommand {

        @Option(names = "--date", required = true, description = "yyyy-MM-dd or yyyy-MM-ddTHH:mm:ss (the database's clock).")
        String date;

        @Override
        String commandName() {
            return "rollback-to-date";
        }

        @Override
        void perform(Context ctx) throws Exception {
            Instant t0 = Instant.now();
            java.util.Date when;
            try {
                when = date.length() <= 10
                        ? java.sql.Timestamp.valueOf(java.time.LocalDate.parse(date).atStartOfDay())
                        : java.sql.Timestamp.valueOf(java.time.LocalDateTime.parse(date));
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException("--date must be yyyy-MM-dd or yyyy-MM-ddTHH:mm:ss, not " + date);
            }
            ctx.liquibase.rollback(when, null, ctx.contexts, ctx.labels);
            ctx.output.line("PASS", "rollback-to-date", "back to " + date, Duration.between(t0, Instant.now()));
        }
    }

    @Command(name = "rollback-one-changeset", mixinStandardHelpOptions = true,
            description = "Reverts one changeset, wherever it sits in the history, and removes it from DATABASECHANGELOG.")
    static final class RollbackOne extends LiquibaseCommand {

        @Option(names = "--changeset-id", required = true, description = "Id of the changeset to revert.")
        String changesetId;

        @Option(names = "--author", description = "Author, when the id alone is ambiguous.")
        String author;

        @Option(names = "--changeset-file", description = "File path of the changeset, when the id alone is ambiguous.")
        String file;

        @Override
        String commandName() {
            return "rollback-one-changeset";
        }

        @Override
        void perform(Context ctx) throws Exception {
            Instant t0 = Instant.now();
            ChangesetRollback.run(ctx.liquibase, changesetId, author, file, ctx.recorder);
            ctx.output.line("PASS", "rollback", "reverted " + changesetId, Duration.between(t0, Instant.now()));
        }
    }

    @Command(name = "rollback-to-changeset", mixinStandardHelpOptions = true,
            description = "Goes back to a changeset: reverts it and every changeset applied after it, newest first, like rollback-count.")
    static final class RollbackToChangeset extends LiquibaseCommand {

        @Option(names = "--changeset-id", required = true, description = "Id of the oldest changeset to revert; everything after it goes too.")
        String changesetId;

        @Option(names = "--author", description = "Author, when the id alone is ambiguous.")
        String author;

        @Option(names = "--changeset-file", description = "File path of the changeset, when the id alone is ambiguous.")
        String file;

        @Option(names = "--expect", description = "A changeset (id::author::filePath) the rollback is expected to revert, newest first; "
                + "repeat it for each. If the database would revert something else, nothing is reverted.")
        java.util.List<String> expect;

        @Override
        String commandName() {
            return "rollback-to-changeset";
        }

        @Override
        void perform(Context ctx) throws Exception {
            Instant t0 = Instant.now();
            java.util.List<String> undone = ChangesetRollback.runTo(ctx.liquibase, changesetId, author, file, expect, ctx.contexts, ctx.labels);
            ctx.output.line("PASS", "rollback-to-changeset", "reverted " + undone.size() + " changeset(s), back to before " + changesetId,
                    Duration.between(t0, Instant.now()));
        }
    }

    @Command(name = "validate", mixinStandardHelpOptions = true,
            description = "Checks that the changelog parses and its changesets are well formed.")
    static final class Validate extends LiquibaseCommand {

        @Override
        String commandName() {
            return "validate";
        }

        @Override
        void perform(Context ctx) throws Exception {
            Instant t0 = Instant.now();
            ctx.liquibase.validate();
            ctx.output.line("PASS", "validate", "changelog is valid", Duration.between(t0, Instant.now()));
        }
    }

    @Command(name = "run", mixinStandardHelpOptions = true,
            description = "Evaluates the policy package against the pending changesets, changing nothing.")
    static final class ChecksRun extends LiquibaseCommand {

        @Override
        String commandName() {
            return "checks run";
        }

        @Override
        boolean gated() {
            return true;
        }

        @Override
        boolean commentsOnMergeRequest() {
            return true;
        }

        @Override
        void perform(Context ctx) {
            // The gate already ran in the template; this command only reports its verdict.
        }
    }

    @Command(name = "checks", mixinStandardHelpOptions = true, description = "Policy checks.",
            subcommands = ChecksRun.class)
    static final class Checks implements Runnable {

        @Override
        public void run() {
            // picocli prints usage when no subcommand is given
            new picocli.CommandLine(this).usage(System.out);
        }
    }
}
