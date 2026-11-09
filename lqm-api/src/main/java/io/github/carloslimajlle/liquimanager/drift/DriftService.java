package io.github.carloslimajlle.liquimanager.drift;

import java.io.IOException;
import java.io.PrintStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.common.UuidV7;
import io.github.carloslimajlle.liquimanager.ingest.BlobStore;
import io.github.carloslimajlle.liquimanager.ingest.OperationIngested;
import liquibase.CatalogAndSchema;
import liquibase.database.Database;
import liquibase.diff.DiffGeneratorFactory;
import liquibase.diff.DiffResult;
import liquibase.diff.ObjectDifferences;
import liquibase.diff.compare.CompareControl;
import liquibase.diff.output.DiffOutputControl;
import liquibase.diff.output.changelog.DiffToChangeLog;
import liquibase.parser.SnapshotParserFactory;
import liquibase.resource.DirectoryResourceAccessor;
import liquibase.serializer.SnapshotSerializerFactory;
import liquibase.snapshot.DatabaseSnapshot;
import liquibase.snapshot.SnapshotControl;
import liquibase.snapshot.SnapshotGeneratorFactory;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Column;
import liquibase.structure.core.ForeignKey;
import liquibase.structure.core.Index;
import liquibase.structure.core.PrimaryKey;
import liquibase.structure.core.Sequence;
import liquibase.structure.core.Schema;
import liquibase.structure.core.Table;
import liquibase.structure.core.UniqueConstraint;
import liquibase.structure.core.View;

/**
 * Drift: the difference between a database and the last state that was known to be clean (D-06).
 *
 * <p>"Clean" means right after a successful update: that is when the reference snapshot is taken, so
 * the reference always includes what the changelog legitimately did. Whatever differs from it later was
 * done by someone else.
 *
 * <p>Snapshots and the comparison are Liquibase's own ({@code SnapshotGeneratorFactory},
 * {@code DiffGeneratorFactory}); the reference is kept as the JSON its serializer writes. A run is also an
 * operation of type {@code drift}, so it shows up wherever operations do.
 *
 * <p>Several schemas per connection are looked at when the connection's schema field lists them ("dbo, audit"); each is compared
 * with itself and every name carries its schema. Grants are read from the catalog and compared too, for PostgreSQL only (see
 * {@link GrantReader}); elsewhere drift says nothing about them. Known limit: column-level and schema-level privileges are not read.
 */
@Service
public class DriftService {

    private static final Logger LOG = LoggerFactory.getLogger(DriftService.class);

    /** One difference as it is stored and shown. */
    record Item(String change, String objectType, String name, Map<String, Object> details) {

        String fingerprint() {
            String content = change + "|" + objectType + "|" + name + "|" + new TreeMap<>(details);
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is part of every JDK", e);
            }
        }
    }

    /** What a run produced, for the caller. */
    public record RunResult(UUID runId, String status, boolean hasDrift, int added, int removed, int modified, String reference,
                            String error) {
    }

    private final JdbcClient jdbc;
    private final TargetConnections targets;
    private final BlobStore blobs;
    private final ApplicationEventPublisher events;

    DriftService(JdbcClient jdbc, TargetConnections targets, BlobStore blobs, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.targets = targets;
        this.blobs = blobs;
        this.events = events;
    }

    // --------------------------------------------------------------- reference

    /** Declares the current state of the database as the clean one. */
    public void takeReference(UUID workspace, UUID connectionId) {
        try (TargetConnections.Target target = targets.open(workspace, connectionId)) {
            storeSnapshot(connectionId, null, serialize(snapshot(target)), true, readGrants(target));
        } catch (LqmException e) {
            throw e;
        } catch (Exception e) {
            throw new LqmException(org.springframework.http.HttpStatus.BAD_GATEWAY, "LQM-050",
                    "could not take the reference snapshot: " + rootMessage(e));
        }
    }

    // --------------------------------------------------------------------- run

    public RunResult run(UUID workspace, UUID connectionId, String actor, String source) {
        Instant started = Instant.now();
        UUID runId = UuidV7.next();
        UUID operationId = UuidV7.next();
        var connection = jdbc.sql("select slug, (select code from environment e where e.id = c.environment_id) as env "
                        + "from db_connection c where id = :id and workspace_id = :ws and archived_at is null")
                .param("id", connectionId).param("ws", workspace).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> LqmException.notFound("connection"));

        List<Item> items = new ArrayList<>();
        String referenceDesc;
        String status = "SUCCESS";
        String error = null;
        boolean baseline = false;
        String currentJson = null;
        Optional<List<String>> currentGrants = Optional.empty();

        try (TargetConnections.Target target = targets.open(workspace, connectionId)) {
            DatabaseSnapshot current = snapshot(target);
            // Serialised while the connection is open: a snapshot reads the live database to describe itself.
            currentJson = serialize(current);
            currentGrants = readGrants(target);
            Optional<String> referenceRef = latestReference(connectionId);
            if (referenceRef.isEmpty()) {
                baseline = true;
                referenceDesc = "first snapshot: it becomes the reference";
                storeSnapshot(connectionId, null, currentJson, true, currentGrants);
            } else {
                referenceDesc = "last clean snapshot";
                items = compare(parse(referenceRef.get()), current);
                // Grants are compared only when both sides were read: a reference from before they were kept says nothing about them.
                Optional<List<String>> referenceGrants = latestReferenceGrants(connectionId);
                if (referenceGrants.isPresent() && currentGrants.isPresent()) {
                    items.addAll(grantItems(referenceGrants.get(), currentGrants.get(), items));
                    items.sort(Comparator.comparing(Item::objectType).thenComparing(Item::name).thenComparing(Item::change));
                }
            }
        } catch (Exception e) {
            status = "FAILED";
            error = e instanceof LqmException ? e.getMessage() : rootMessage(e);
            referenceDesc = "not compared";
            LOG.warn("drift run on {} failed: {}", connection.get("slug"), error);
        }

        // Accepted earlier, and not changed since: carried over, and not counted.
        Set<String> carried = items.isEmpty() ? Set.of() : acceptedFingerprints(connectionId);
        int added = 0;
        int removed = 0;
        int modified = 0;
        for (Item item : items) {
            if (carried.contains(item.fingerprint())) {
                continue;
            }
            switch (item.change()) {
                case "ADDED" -> added++;
                case "REMOVED" -> removed++;
                default -> modified++;
            }
        }
        boolean hasDrift = added + removed + modified > 0;

        Instant ended = Instant.now();
        insertOperation(operationId, workspace, connectionId, status, source, actor, started, ended);
        jdbc.sql("""
                insert into drift_run (id, operation_id, db_connection_id, reference_type, reference_desc, status, has_drift,
                    added, removed, modified, started_at, ended_at, error)
                values (:id, :op, :conn, 'SNAPSHOT', :ref, :status, :drift, :added, :removed, :modified, :started, :ended, :error)
                """)
                .param("id", runId).param("op", operationId).param("conn", connectionId).param("ref", referenceDesc)
                .param("status", status).param("drift", status.equals("SUCCESS") ? hasDrift : null)
                .param("added", added).param("removed", removed).param("modified", modified)
                .param("started", Timestamp.from(started)).param("ended", Timestamp.from(ended)).param("error", error).update();
        for (Item item : items) {
            insertItem(runId, connectionId, item, carried.contains(item.fingerprint()));
        }
        if (currentJson != null && status.equals("SUCCESS") && !baseline) {
            storeSnapshot(connectionId, operationId, currentJson, false, currentGrants);
        }

        events.publishEvent(new OperationIngested(operationId, "diff", status, "drift", source, actor, null,
                (String) connection.get("slug"), (String) connection.get("env"), null, started,
                Duration.between(started, ended).toMillis(), false, workspace, connectionId));
        return new RunResult(runId, status, hasDrift, added, removed, modified, referenceDesc, error);
    }

    // ------------------------------------------------------------- changelog

    /** The Liquibase changelog (XML) that would turn the reference into what the run found. */
    public String diffChangelog(UUID workspace, UUID runId) {
        var run = jdbc.sql("""
                select r.operation_id, r.db_connection_id, c.slug from drift_run r join db_connection c on c.id = r.db_connection_id
                 where r.id = :id and c.workspace_id = :ws
                """).param("id", runId).param("ws", workspace).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> LqmException.notFound("drift run"));
        UUID connectionId = (UUID) run.get("db_connection_id");
        Optional<String> current = jdbc.sql("select storage_ref from schema_snapshot where operation_id = :op and not is_reference")
                .param("op", run.get("operation_id")).query(String.class).optional();
        Optional<String> reference = latestReference(connectionId);
        if (current.isEmpty() || reference.isEmpty()) {
            throw LqmException.invalid("this run has no snapshot to generate a changelog from");
        }
        try (TargetConnections.Target target = targets.open(workspace, connectionId)) {
            DatabaseSnapshot referenceSnapshot = parse(reference.get());
            DatabaseSnapshot currentSnapshot = parse(current.get());
            // Liquibase writes the changes that turn the SECOND snapshot into the FIRST. Here the first is what was found
            // (the state to adopt) and the second is the clean reference: applying the result to a database in the
            // reference state brings it to the drifted one, which is what lets the drift become a changeset.
            DiffResult diff = DiffGeneratorFactory.getInstance().compare(currentSnapshot, referenceSnapshot, compareControl(currentSnapshot));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DiffOutputControl control = new DiffOutputControl(false, false, false, null);
            new DiffToChangeLog(diff, control).print(new PrintStream(out, true, StandardCharsets.UTF_8));
            return out.toString(StandardCharsets.UTF_8);
        } catch (LqmException e) {
            throw e;
        } catch (Exception e) {
            throw LqmException.invalid("could not generate the changelog: " + rootMessage(e));
        }
    }

    // ---------------------------------------------------------------- snapshot

    private DatabaseSnapshot snapshot(TargetConnections.Target target) throws Exception {
        Database database = target.database();
        if ("mssql".equals(database.getShortName()) && !SqlServerAccess.canViewDefinitions(target.jdbc())) {
            throw LqmException.invalid(SqlServerAccess.VIEW_DEFINITION_MISSING);
        }
        // The connection's own schema, named explicitly: the database object may not hold it as its default (SQL Server
        // refuses to set a default schema that is not the login's), and then it would snapshot the wrong one.
        List<String> names = target.schemas().isEmpty() ? List.of(database.getDefaultSchemaName()) : target.schemas();
        CatalogAndSchema[] scopes = names.stream().map(name -> new CatalogAndSchema(database.getDefaultCatalogName(), name))
                .toArray(CatalogAndSchema[]::new);
        SnapshotControl control = new SnapshotControl(database, types());
        return SnapshotGeneratorFactory.getInstance().createSnapshot(scopes, database, control);
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends DatabaseObject>[] types() {
        return new Class[] {Table.class, Column.class, PrimaryKey.class, ForeignKey.class, UniqueConstraint.class,
                Index.class, View.class, Sequence.class};
    }

    /**
     * Compares the schema the snapshot is OF, named explicitly. The default {@code CompareControl} compares "the default
     * schema" of each side, which on SQL Server is the login's (dbo) and not the connection's: the comparison would be
     * between two empty schemas and report no drift at all.
     */
    private static CompareControl compareControl(DatabaseSnapshot snapshot) {
        var types = snapshot.getSnapshotControl().getTypesToInclude();
        var schemas = snapshot.get(Schema.class);
        if (schemas.isEmpty()) {
            return new CompareControl(types);
        }
        // One comparison per schema of the connection: each is compared with itself, not with the login's default.
        CompareControl.SchemaComparison[] comparisons = schemas.stream().map(Schema::toCatalogAndSchema)
                .map(scope -> new CompareControl.SchemaComparison(scope, scope)).toArray(CompareControl.SchemaComparison[]::new);
        return new CompareControl(comparisons, types);
    }

    private static String serialize(DatabaseSnapshot snapshot) throws IOException {
        return SnapshotSerializerFactory.getInstance().getSerializer("json").serialize(snapshot, true);
    }

    private void storeSnapshot(UUID connectionId, UUID operationId, String json, boolean reference, Optional<List<String>> grants) {
        UUID snapshotId = UuidV7.next();
        String ref = blobs.put("snapshots/" + connectionId + "/" + snapshotId + ".json", json);
        String grantsRef = grants.map(list -> blobs.put("snapshots/" + connectionId + "/" + snapshotId + ".grants.json", toJsonList(list))).orElse(null);
        if (reference) {
            jdbc.sql("update schema_snapshot set is_reference = false where db_connection_id = :c and is_reference")
                    .param("c", connectionId).update();
        }
        jdbc.sql("""
                insert into schema_snapshot (id, db_connection_id, operation_id, taken_at, is_reference, storage_ref, grants_ref)
                values (:id, :c, :op, now(), :ref, :storage, :grants)
                """).param("id", snapshotId).param("c", connectionId).param("op", operationId)
                .param("ref", reference).param("storage", ref).param("grants", grantsRef).update();
    }

    // ------------------------------------------------------------------ grants

    /** The grants of the connection's schemas, or empty when this kind of database is not read (or the read failed: drift goes on without). */
    private Optional<List<String>> readGrants(TargetConnections.Target target) {
        try {
            List<String> schemas = target.schemas().isEmpty() ? List.of(target.database().getDefaultSchemaName()) : target.schemas();
            return GrantReader.read(target, schemas);
        } catch (Exception e) {
            LOG.warn("could not read the grants of {}: {}", target.slug(), rootMessage(e));
            return Optional.empty();
        }
    }

    private Optional<List<String>> latestReferenceGrants(UUID connectionId) {
        Optional<String> ref = jdbc.sql("""
                select grants_ref from schema_snapshot where db_connection_id = :c and is_reference
                 order by taken_at desc limit 1
                """).param("c", connectionId).query(String.class).optional();
        if (ref.isEmpty() || ref.get() == null) {
            return Optional.empty();
        }
        return blobs.get(ref.get()).map(DriftService::fromJsonList);
    }

    /** Grants given and grants taken away since the reference. A grant on a table that came or went with it is not extra news. */
    private static List<Item> grantItems(List<String> reference, List<String> current, List<Item> objectItems) {
        Set<String> tablesThatChanged = new HashSet<>();
        for (Item item : objectItems) {
            if ("TABLE".equals(item.objectType()) && !"MODIFIED".equals(item.change())) {
                tablesThatChanged.add(item.name().toLowerCase(Locale.ROOT));
            }
        }
        List<Item> items = new ArrayList<>();
        Set<String> before = new HashSet<>(reference);
        Set<String> now = new HashSet<>(current);
        for (String grant : current) {
            if (!before.contains(grant)) {
                grantItem("ADDED", grant, tablesThatChanged).ifPresent(items::add);
            }
        }
        for (String grant : reference) {
            if (!now.contains(grant)) {
                grantItem("REMOVED", grant, tablesThatChanged).ifPresent(items::add);
            }
        }
        return items;
    }

    private static Optional<Item> grantItem(String change, String grant, Set<String> tablesThatChanged) {
        String[] parts = grant.split("\\|", 3);
        String object = parts[1];
        String table = object.substring(object.indexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (tablesThatChanged.contains(table) || tablesThatChanged.contains(object.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("privilege", parts[2]);
        return Optional.of(new Item(change, "GRANT", parts[0] + " on " + object, details));
    }

    private static String toJsonList(List<String> list) {
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(list);
    }

    @SuppressWarnings("unchecked")
    private static List<String> fromJsonList(String json) {
        return tools.jackson.databind.json.JsonMapper.builder().build().readValue(json, List.class);
    }

    private Optional<String> latestReference(UUID connectionId) {
        return jdbc.sql("select storage_ref from schema_snapshot where db_connection_id = :c and is_reference order by taken_at desc limit 1")
                .param("c", connectionId).query(String.class).optional();
    }

    private DatabaseSnapshot parse(String reference) {
        String json = blobs.get(reference).orElseThrow(() -> LqmException.invalid("the stored snapshot is gone"));
        Path dir = null;
        try {
            dir = Files.createTempDirectory("lqm-snapshot");
            Files.writeString(dir.resolve("snapshot.json"), json, StandardCharsets.UTF_8);
            DirectoryResourceAccessor accessor = new DirectoryResourceAccessor(dir);
            return SnapshotParserFactory.getInstance().getParser("snapshot.json", accessor).parse("snapshot.json", accessor);
        } catch (Exception e) {
            throw LqmException.invalid("could not read the stored snapshot: " + rootMessage(e));
        } finally {
            deleteQuietly(dir);
        }
    }

    // ----------------------------------------------------------------- compare

    private List<Item> compare(DatabaseSnapshot reference, DatabaseSnapshot current) throws Exception {
        DiffResult diff = DiffGeneratorFactory.getInstance().compare(reference, current, compareControl(current));
        // With one schema a name is enough; with several, two tables can share one, so every name carries its schema.
        Naming naming = new Naming(current.get(Schema.class).size() > 1);

        List<Item> items = new ArrayList<>();
        Set<String> removedOrAddedTables = new HashSet<>();
        for (DatabaseObject o : diff.getMissingObjects()) {
            if (o instanceof Table) {
                removedOrAddedTables.add(naming.key(o));
            }
        }
        for (DatabaseObject o : diff.getUnexpectedObjects()) {
            if (o instanceof Table) {
                removedOrAddedTables.add(naming.key(o));
            }
        }
        for (DatabaseObject o : diff.getMissingObjects()) {
            addItem(items, "REMOVED", o, definitionOf(o), removedOrAddedTables, naming);
        }
        for (DatabaseObject o : diff.getUnexpectedObjects()) {
            addItem(items, "ADDED", o, definitionOf(o), removedOrAddedTables, naming);
        }
        for (Map.Entry<DatabaseObject, ObjectDifferences> e : diff.getChangedObjects().entrySet()) {
            Map<String, Object> details = new LinkedHashMap<>();
            e.getValue().getDifferences().forEach(d -> {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("before", String.valueOf(d.getReferenceValue()));
                change.put("after", String.valueOf(d.getComparedValue()));
                details.put(d.getField(), change);
            });
            addItem(items, "MODIFIED", e.getKey(), details, removedOrAddedTables, naming);
        }
        items.sort(Comparator.comparing(Item::objectType).thenComparing(Item::name).thenComparing(Item::change));
        return items;
    }

    private static void addItem(List<Item> items, String change, DatabaseObject object, Map<String, Object> details,
                                Set<String> changedTables, Naming naming) {
        String name = naming.nameOf(object);
        // Liquibase's own bookkeeping tables are not the application's schema.
        if (name.toLowerCase(Locale.ROOT).contains("databasechangelog")) {
            return;
        }
        // A new or dropped table brings its columns, keys and indexes with it; they are not extra news.
        String owner = naming.ownerOf(object);
        if (owner != null && changedTables.contains(owner)) {
            return;
        }
        items.add(new Item(change, typeOf(object), name, details));
    }

    /**
     * What an added or removed object looks like. It is part of the item's identity: accepting "a column email was
     * added" must not also accept the same column coming back with another type.
     */
    private static Map<String, Object> definitionOf(DatabaseObject o) {
        Map<String, Object> definition = new LinkedHashMap<>();
        if (o instanceof Column c) {
            definition.put("type", String.valueOf(c.getType()));
            definition.put("nullable", String.valueOf(c.isNullable()));
            if (c.getDefaultValue() != null) {
                definition.put("default", String.valueOf(c.getDefaultValue()));
            }
        } else if (o instanceof Index i) {
            definition.put("unique", String.valueOf(i.isUnique()));
            definition.put("columns", String.valueOf(i.getColumns()));
        } else if (o instanceof PrimaryKey p) {
            definition.put("columns", String.valueOf(p.getColumns()));
        } else if (o instanceof UniqueConstraint u) {
            definition.put("columns", String.valueOf(u.getColumns()));
        } else if (o instanceof ForeignKey f) {
            definition.put("references", String.valueOf(f.getPrimaryKeyTable()) + String.valueOf(f.getPrimaryKeyColumns()));
            definition.put("columns", String.valueOf(f.getForeignKeyColumns()));
        } else if (o instanceof View v && v.getDefinition() != null) {
            definition.put("definition", v.getDefinition());
        }
        return definition;
    }

    private static String typeOf(DatabaseObject o) {
        if (o instanceof Table) {
            return "TABLE";
        }
        if (o instanceof Column) {
            return "COLUMN";
        }
        if (o instanceof Index) {
            return "INDEX";
        }
        if (o instanceof PrimaryKey || o instanceof ForeignKey || o instanceof UniqueConstraint) {
            return "CONSTRAINT";
        }
        if (o instanceof View) {
            return "VIEW";
        }
        if (o instanceof Sequence) {
            return "SEQUENCE";
        }
        return o.getClass().getSimpleName().toUpperCase(Locale.ROOT);
    }

    /** How objects are named in an item: by name alone with one schema, schema-qualified with several. */
    private record Naming(boolean qualify) {

        String nameOf(DatabaseObject o) {
            String owner = ownerOf(o);
            String name = o.getName();
            if (o instanceof Column && owner != null) {
                return owner + "." + name;
            }
            if (name == null) {
                return o.toString();
            }
            String schema = qualify ? schemaOf(o) : null;
            return schema == null ? name : schema + "." + name;
        }

        /** The table an object lives on, by name, or null for objects that are not on a table. */
        String ownerOf(DatabaseObject o) {
            if (o instanceof Column c && c.getRelation() != null) {
                return key(c.getRelation());
            }
            if (o instanceof Index i && i.getRelation() != null) {
                return key(i.getRelation());
            }
            if (o instanceof PrimaryKey p && p.getTable() != null) {
                return key(p.getTable());
            }
            if (o instanceof ForeignKey f && f.getForeignKeyTable() != null) {
                return key(f.getForeignKeyTable());
            }
            if (o instanceof UniqueConstraint u && u.getRelation() != null) {
                return key(u.getRelation());
            }
            return null;
        }

        String key(DatabaseObject o) {
            String name = o.getName() == null ? "" : o.getName().toLowerCase(Locale.ROOT);
            String schema = qualify ? schemaOf(o) : null;
            return schema == null ? name : schema.toLowerCase(Locale.ROOT) + "." + name;
        }

        /** The name of the schema an object is in, or null when it does not say. */
        private static String schemaOf(DatabaseObject o) {
            if (o instanceof liquibase.structure.core.Relation r) {
                return r.getSchema() == null ? null : r.getSchema().getName();
            }
            if (o instanceof Sequence s) {
                return s.getSchema() == null ? null : s.getSchema().getName();
            }
            if (o instanceof Column c && c.getRelation() != null) {
                return schemaOf(c.getRelation());
            }
            if (o instanceof Index i && i.getRelation() != null) {
                return schemaOf(i.getRelation());
            }
            if (o instanceof PrimaryKey p && p.getTable() != null) {
                return schemaOf(p.getTable());
            }
            if (o instanceof ForeignKey f && f.getForeignKeyTable() != null) {
                return schemaOf(f.getForeignKeyTable());
            }
            if (o instanceof UniqueConstraint u && u.getRelation() != null) {
                return schemaOf(u.getRelation());
            }
            return null;
        }
    }

    // ----------------------------------------------------------------- storage

    private void insertOperation(UUID id, UUID workspace, UUID connectionId, String status, String source, String actor,
                                 Instant started, Instant ended) {
        jdbc.sql("""
                insert into operation (id, workspace_id, db_connection_id, command, type, status, source, executed_by,
                    started_at, ended_at, duration_ms, collector)
                values (:id, :ws, :conn, 'diff', 'drift', :status, :source, :by, :started, :ended, :duration, 'lqm-api')
                """)
                .param("id", id).param("ws", workspace).param("conn", connectionId)
                .param("status", status.equals("SUCCESS") ? "SUCCESS" : "FAILURE").param("source", source).param("by", actor)
                .param("started", Timestamp.from(started)).param("ended", Timestamp.from(ended))
                .param("duration", Duration.between(started, ended).toMillis()).update();
    }

    private void insertItem(UUID runId, UUID connectionId, Item item, boolean carriedOver) {
        var previous = carriedOver ? jdbc.sql("""
                select i.accepted_by, i.accepted_reason from drift_item i join drift_run r on r.id = i.drift_run_id
                 where r.db_connection_id = :c and i.fingerprint = :fp and i.accepted order by r.started_at desc limit 1
                """).param("c", connectionId).param("fp", item.fingerprint()).query().listOfRows().stream().findFirst()
                : Optional.<Map<String, Object>>empty();
        jdbc.sql("""
                insert into drift_item (id, drift_run_id, change_type, object_type, object_name, details, accepted,
                    accepted_by, accepted_reason, fingerprint)
                values (:id, :run, :change, :type, :name, cast(:details as jsonb), :accepted, :by, :reason, :fp)
                """)
                .param("id", UuidV7.next()).param("run", runId).param("change", item.change()).param("type", item.objectType())
                .param("name", item.name()).param("details", toJson(item.details())).param("accepted", carriedOver)
                .param("by", previous.map(p -> p.get("accepted_by")).orElse(null))
                .param("reason", previous.map(p -> p.get("accepted_reason")).orElse(null))
                .param("fp", item.fingerprint()).update();
    }

    private Set<String> acceptedFingerprints(UUID connectionId) {
        return new HashSet<>(jdbc.sql("""
                select distinct i.fingerprint from drift_item i join drift_run r on r.id = i.drift_run_id
                 where r.db_connection_id = :c and i.accepted and i.fingerprint is not null
                """).param("c", connectionId).query(String.class).list());
    }

    private static String toJson(Map<String, Object> details) {
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(details);
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // a leftover temporary directory is not worth failing a run for
        }
    }
}
