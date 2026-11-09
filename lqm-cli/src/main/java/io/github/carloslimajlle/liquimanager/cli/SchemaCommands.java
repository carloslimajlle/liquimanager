package io.github.carloslimajlle.liquimanager.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import io.github.carloslimajlle.liquimanager.collector.SchemaTools;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.diff.DiffResult;
import liquibase.resource.DirectoryResourceAccessor;
import liquibase.snapshot.DatabaseSnapshot;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code snapshot}, {@code diff} and {@code diff-changelog}: Liquibase's, reported like every other command (as read-only operations, so
 * they never move changeset state). None of them needs a changelog.
 */
final class SchemaCommands {

    private SchemaCommands() {
    }

    @Command(name = "snapshot", mixinStandardHelpOptions = true,
            description = "Takes a snapshot of the schema (tables, columns, keys, indexes, views, sequences) and prints it.")
    static final class Snapshot extends LiquibaseCommand {

        @Option(names = "--snapshot-format", description = "json, yaml or txt.", defaultValue = "json")
        String format;

        @Option(names = "--schema", description = "Schema to snapshot (default: the connection's).")
        String schema;

        @Option(names = "--snapshot-out", description = "Write the snapshot to this file instead of the standard output "
                + "(use it with --format json, where the standard output is the report).")
        String out;

        @Override
        String commandName() {
            return "snapshot";
        }

        @Override
        boolean needsChangelog() {
            return false;
        }

        @Override
        void perform(Context ctx) throws Exception {
            Instant t0 = Instant.now();
            DatabaseSnapshot snapshot = SchemaTools.snapshot(ctx.liquibase.getDatabase(), schema);
            String text = SchemaTools.serialize(snapshot, format);
            ctx.output.line("PASS", "snapshot", "schema captured", Duration.between(t0, Instant.now()));
            write(ctx, out, text);
        }
    }

    /** What {@code diff} and {@code diff-changelog} share: the reference database the target is compared with. */
    abstract static class Comparing extends LiquibaseCommand {

        @Option(names = "--reference-url", required = true, description = "JDBC URL of the reference: what the target should look like.")
        String referenceUrl;

        @Option(names = "--reference-username", description = "Reference database user. For local use only; in CI use LQM_REFERENCE_USERNAME.",
                defaultValue = "${env:LQM_REFERENCE_USERNAME:-}")
        String referenceUsername;

        @Option(names = "--reference-password", description = "Reference database password. For local use only; in CI use LQM_REFERENCE_PASSWORD.",
                defaultValue = "${env:LQM_REFERENCE_PASSWORD:-}")
        String referencePassword;

        @Option(names = "--schema", description = "Schema to compare (default: each connection's).")
        String schema;

        @Override
        boolean needsChangelog() {
            return false;
        }

        /** The comparison: what the reference has and the target lacks is missing, the other way round unexpected. */
        DiffResult compare(Context ctx) throws Exception {
            Database reference = DatabaseFactory.getInstance().openDatabase(referenceUrl,
                    blank(referenceUsername), blank(referencePassword), null, null, null, null,
                    new DirectoryResourceAccessor(java.nio.file.Paths.get("").toAbsolutePath()));
            try {
                return SchemaTools.compare(SchemaTools.snapshot(reference, schema), SchemaTools.snapshot(ctx.liquibase.getDatabase(), schema));
            } finally {
                try {
                    reference.close();
                } catch (Exception ignored) {
                    // a connection that is already broken tells us nothing new
                }
            }
        }
    }

    @Command(name = "diff", mixinStandardHelpOptions = true,
            description = "Compares the schema with a reference database and prints what differs.")
    static final class Diff extends Comparing {

        @Option(names = "--fail-on-diff", description = "Exit 1 when the schemas differ (a pipeline that must not drift).")
        boolean failOnDiff;

        @Override
        String commandName() {
            return "diff";
        }

        @Override
        void perform(Context ctx) throws Exception {
            Instant t0 = Instant.now();
            DiffResult diff = compare(ctx);
            SchemaTools.Differences found = SchemaTools.count(diff);
            ctx.output.line(found.total() == 0 ? "PASS" : "WARN", "diff",
                    found.missing() + " missing · " + found.unexpected() + " unexpected · " + found.changed() + " changed",
                    Duration.between(t0, Instant.now()));
            if (found.total() > 0) {
                ctx.output.detail(SchemaTools.report(diff).strip());
            }
            if (failOnDiff && found.total() > 0) {
                throw new IllegalStateException(found.total() + " differences from the reference");
            }
        }
    }

    @Command(name = "diff-changelog", mixinStandardHelpOptions = true,
            description = "Writes the changeset that turns the schema into the reference.")
    static final class DiffChangelog extends Comparing {

        @Option(names = "--changelog-out", description = "Write the changelog to this file; its extension (xml, yaml, json) picks the "
                + "format. Without it, XML goes to the standard output.")
        String out;

        @Override
        String commandName() {
            return "diff-changelog";
        }

        @Override
        void perform(Context ctx) throws Exception {
            Instant t0 = Instant.now();
            DiffResult diff = compare(ctx);
            SchemaTools.Differences found = SchemaTools.count(diff);
            ctx.output.line("PASS", "diff-changelog", found.total() + " differences turned into changes", Duration.between(t0, Instant.now()));
            if (blank(out) != null) {
                SchemaTools.changelogTo(diff, out);
            } else {
                ctx.out.println(SchemaTools.changelog(diff));
            }
        }
    }

    private static void write(LiquibaseCommand.Context ctx, String file, String text) throws java.io.IOException {
        if (file == null || file.isBlank()) {
            ctx.out.println(text);
            return;
        }
        Path target = Path.of(file);
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, text, StandardCharsets.UTF_8);
    }
}
