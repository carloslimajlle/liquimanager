package io.github.carloslimajlle.liquimanager.collector;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import liquibase.CatalogAndSchema;
import liquibase.database.Database;
import liquibase.diff.DiffGeneratorFactory;
import liquibase.diff.DiffResult;
import liquibase.diff.compare.CompareControl;
import liquibase.diff.output.DiffOutputControl;
import liquibase.diff.output.changelog.DiffToChangeLog;
import liquibase.diff.output.report.DiffToReport;
import liquibase.serializer.SnapshotSerializerFactory;
import liquibase.snapshot.DatabaseSnapshot;
import liquibase.snapshot.SnapshotControl;
import liquibase.snapshot.SnapshotGeneratorFactory;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Column;
import liquibase.structure.core.ForeignKey;
import liquibase.structure.core.Index;
import liquibase.structure.core.PrimaryKey;
import liquibase.structure.core.Schema;
import liquibase.structure.core.Sequence;
import liquibase.structure.core.Table;
import liquibase.structure.core.UniqueConstraint;
import liquibase.structure.core.View;

/**
 * What {@code lqm snapshot}, {@code lqm diff} and {@code lqm diff-changelog} do with Liquibase, kept apart from the commands so it is the
 * same thing the drift job does on the server: the same object types, and the schema named explicitly (the database object may not hold it
 * as its default, and the comparison would then be between two empty schemas and report nothing).
 */
public final class SchemaTools {

    /** What a comparison found, as numbers; the lines are in the report. */
    public record Differences(int missing, int unexpected, int changed) {

        public int total() {
            return missing + unexpected + changed;
        }
    }

    private SchemaTools() {
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends DatabaseObject>[] types() {
        return new Class[] {Table.class, Column.class, PrimaryKey.class, ForeignKey.class, UniqueConstraint.class,
                Index.class, View.class, Sequence.class};
    }

    /** The snapshot of one schema (the connection's default when {@code schema} is blank). */
    public static DatabaseSnapshot snapshot(Database database, String schema) throws Exception {
        String name = schema == null || schema.isBlank() ? database.getDefaultSchemaName() : schema;
        CatalogAndSchema scope = new CatalogAndSchema(database.getDefaultCatalogName(), name);
        return SnapshotGeneratorFactory.getInstance().createSnapshot(new CatalogAndSchema[] {scope}, database, new SnapshotControl(database, types()));
    }

    /** The snapshot as Liquibase writes it: {@code json}, {@code yaml} or {@code txt}. */
    public static String serialize(DatabaseSnapshot snapshot, String format) throws IOException {
        String wanted = format == null || format.isBlank() ? "json" : format.toLowerCase(java.util.Locale.ROOT);
        var serializer = SnapshotSerializerFactory.getInstance().getSerializer(wanted);
        if (serializer == null) {
            throw new IllegalArgumentException("--snapshot-format must be json, yaml or txt, not " + format);
        }
        return serializer.serialize(snapshot, true);
    }

    /**
     * Compares {@code reference} (what the target should look like) with {@code target}. As in Liquibase's {@code diff}, an object the
     * reference has and the target lacks is "missing", the other way round is "unexpected".
     */
    public static DiffResult compare(DatabaseSnapshot reference, DatabaseSnapshot target) throws Exception {
        var schemas = reference.get(Schema.class);
        var types = reference.getSnapshotControl().getTypesToInclude();
        CompareControl control = new CompareControl(types);
        if (!schemas.isEmpty()) {
            CatalogAndSchema scope = schemas.iterator().next().toCatalogAndSchema();
            CatalogAndSchema other = target.get(Schema.class).isEmpty() ? scope : target.get(Schema.class).iterator().next().toCatalogAndSchema();
            control = new CompareControl(new CompareControl.SchemaComparison[] {new CompareControl.SchemaComparison(scope, other)}, types);
        }
        return DiffGeneratorFactory.getInstance().compare(reference, target, control);
    }

    public static Differences count(DiffResult diff) {
        return new Differences(diff.getMissingObjects().size(), diff.getUnexpectedObjects().size(), diff.getChangedObjects().size());
    }

    /** Liquibase's own text report of the differences. */
    public static String report(DiffResult diff) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new DiffToReport(diff, new PrintStream(out, true, StandardCharsets.UTF_8)).print();
        return out.toString(StandardCharsets.UTF_8);
    }

    /** The changes that turn the target into the reference, as XML (to a file, the format follows its extension). */
    public static String changelog(DiffResult diff) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new DiffToChangeLog(diff, new DiffOutputControl(false, false, false, null)).print(new PrintStream(out, true, StandardCharsets.UTF_8));
        return out.toString(StandardCharsets.UTF_8);
    }

    public static void changelogTo(DiffResult diff, String file) throws Exception {
        new DiffToChangeLog(diff, new DiffOutputControl(false, false, false, null)).print(file);
    }
}
