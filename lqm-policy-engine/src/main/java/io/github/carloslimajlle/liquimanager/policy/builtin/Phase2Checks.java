package io.github.carloslimajlle.liquimanager.policy.builtin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.carloslimajlle.liquimanager.policy.CheckContext;
import io.github.carloslimajlle.liquimanager.policy.CheckScope;
import io.github.carloslimajlle.liquimanager.policy.Finding;
import io.github.carloslimajlle.liquimanager.policy.PolicyCheck;
import io.github.carloslimajlle.liquimanager.policy.Severity;
import liquibase.change.Change;
import liquibase.change.ColumnConfig;
import liquibase.change.core.AddColumnChange;
import liquibase.change.core.CreateIndexChange;
import liquibase.change.core.CreateSequenceChange;
import liquibase.change.core.CreateTableChange;
import liquibase.change.core.CreateViewChange;
import liquibase.change.core.LoadDataChange;
import liquibase.change.core.ModifyDataTypeChange;
import liquibase.change.core.RenameColumnChange;
import liquibase.changelog.ChangeSet;
import liquibase.precondition.Precondition;
import liquibase.precondition.PreconditionLogic;
import liquibase.precondition.core.PreconditionContainer;
import liquibase.precondition.core.SqlPrecondition;

/**
 * The phase-2 checks. Every one of them is written to stay quiet when it cannot be sure: a false positive blocks a deployment
 * (ADR-004), and several of these sit in the default package at a severity that blocks production.
 *
 * <p>A nested class's simple name IS its catalogue code.
 */
public final class Phase2Checks {

    private Phase2Checks() {
    }

    // ------------------------------------------------------------------ helpers

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    /** {@code [dbo].[veiculo]} -> {@code veiculo}: the table, whatever schema or quoting wrote it. */
    static String tableOf(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replaceAll("[\\[\\]\"`]", "").toLowerCase(Locale.ROOT);
        int dot = cleaned.lastIndexOf('.');
        return dot >= 0 ? cleaned.substring(dot + 1) : cleaned;
    }

    private static String unquote(String text) {
        return text.replaceAll("[\\[\\]\"`]", "");
    }

    // ----------------------------------------------------------- runAlways, runOnChange

    /** {@code runAlways} makes a changeset run on every update, which is almost never meant; say why, in the comment. */
    public static final class RunAlwaysForbidden implements PolicyCheck {

        @Override
        public String code() {
            return "RunAlwaysForbidden";
        }

        @Override
        public CheckScope scope() {
            return CheckScope.CHANGELOG;
        }

        @Override
        public int defaultSeverity() {
            return Severity.MAJOR;
        }

        @Override
        public List<Finding> evaluate(CheckContext ctx, Map<String, Object> params) {
            List<Finding> findings = new ArrayList<>();
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                if (changeSet.isAlwaysRun() && blank(changeSet.getComments())) {
                    findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                            "O changeset %s usa runAlways (roda em todo update) sem um comentario que explique por que."
                                    .formatted(changeSet.getId())));
                }
            }
            return findings;
        }
    }

    /** {@code runOnChange} reruns a changeset whenever its text changes; the reviewer should know that was intended. */
    public static final class RunOnChangeReview implements PolicyCheck {

        @Override
        public String code() {
            return "RunOnChangeReview";
        }

        @Override
        public CheckScope scope() {
            return CheckScope.CHANGELOG;
        }

        @Override
        public int defaultSeverity() {
            return Severity.MAJOR;
        }

        @Override
        public List<Finding> evaluate(CheckContext ctx, Map<String, Object> params) {
            List<Finding> findings = new ArrayList<>();
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                if (changeSet.isRunOnChange() && blank(changeSet.getComments())) {
                    findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                            "O changeset %s usa runOnChange (roda de novo quando o texto muda) sem um comentario que explique por que."
                                    .formatted(changeSet.getId())));
                }
            }
            return findings;
        }
    }

    // ---------------------------------------------------------------- the chained one

    /**
     * A check that gathers others and says one thing per changeset, with the worst severity among them (docs/08 § 2).
     * By default: a rollback, a comment and a release label. The parameter {@code checks} lists the codes to chain.
     */
    public static final class ChangesetStandards implements PolicyCheck {

        private static final String DEFAULT_CHAIN = "RollbackRequired,ChangesetCommentRequired,LabelRequired";

        private static final Map<String, Supplier<PolicyCheck>> CHAINABLE = Map.of(
                "RollbackRequired", RollbackRequired::new,
                "ChangesetCommentRequired", ChangesetCommentRequired::new,
                "LabelRequired", ChangeChecks.LabelRequired::new,
                "AuthorRequired", ChangeChecks.AuthorRequired::new,
                "ContextRequired", ContextRequired::new,
                "ChangesetIdPattern", ChangesetIdPattern::new,
                "OneChangePerChangeset", OneChangePerChangeset::new);

        @Override
        public String code() {
            return "ChangesetStandards";
        }

        @Override
        public CheckScope scope() {
            return CheckScope.CHANGELOG;
        }

        @Override
        public int defaultSeverity() {
            return Severity.MAJOR;
        }

        @Override
        public List<Finding> evaluate(CheckContext ctx, Map<String, Object> params) {
            Map<String, List<Finding>> byChangeset = new LinkedHashMap<>();
            for (String name : param(params, "checks", DEFAULT_CHAIN).split(",")) {
                Supplier<PolicyCheck> factory = CHAINABLE.get(name.trim());
                if (factory == null) {
                    continue; // a code that is not chainable here says nothing, rather than breaking the chain
                }
                for (Finding finding : factory.get().evaluate(ctx, Map.of())) {
                    byChangeset.computeIfAbsent(finding.changesetKey(), key -> new ArrayList<>()).add(finding);
                }
            }
            List<Finding> findings = new ArrayList<>();
            byChangeset.forEach((key, found) -> {
                int worst = found.stream().mapToInt(Finding::severity).max().orElse(defaultSeverity());
                String failed = String.join(", ", found.stream().map(Finding::checkCode).distinct().toList());
                findings.add(Finding.of(code(), worst, key,
                        "O changeset %s nao cumpre o padrao do time (%s).".formatted(idOf(key), failed)));
            });
            return findings;
        }

        private static String idOf(String key) {
            int cut = key == null ? -1 : key.indexOf("::");
            return cut < 0 ? String.valueOf(key) : key.substring(0, cut);
        }
    }

    // ---------------------------------------------------------- data protection

    /**
     * A column made smaller than it was ({@code VARCHAR(200)} to {@code VARCHAR(50)}, {@code BIGINT} to {@code INT},
     * {@code DECIMAL(18,2)} to {@code DECIMAL(10,2)}): the database cuts or refuses the data that no longer fits.
     *
     * <p>A changelog check has no snapshot, so the old type is the one the changelog itself declared earlier (a
     * {@code createTable}, an {@code addColumn}, a previous {@code modifyDataType}). A column whose earlier type the changelog
     * never states is not judged.
     */
    public static final class ModifyDataTypeNarrowing implements PolicyCheck {

        private static final Pattern ALTER_COLUMN = Pattern.compile(
                "alter\\s+table\\s+(?:only\\s+)?(\\S+)\\s+alter\\s+column\\s+(\\S+)\\s+(?:set\\s+data\\s+)?(?:type\\s+)?"
                        + "([a-z][a-z0-9_]*(?:\\s+varying)?(?:\\s*\\(\\s*(?:\\d+(?:\\s*,\\s*\\d+)?|max)\\s*\\))?)");

        @Override
        public String code() {
            return "ModifyDataTypeNarrowing";
        }

        @Override
        public CheckScope scope() {
            return CheckScope.CHANGELOG;
        }

        @Override
        public int defaultSeverity() {
            return Severity.CRITICAL;
        }

        @Override
        public List<Finding> evaluate(CheckContext ctx, Map<String, Object> params) {
            List<Finding> findings = new ArrayList<>();
            Map<String, String> known = new HashMap<>();
            Set<String> reviewed = new HashSet<>();
            ctx.changeSetsUnderReview().forEach(cs -> reviewed.add(CheckContext.keyOf(cs)));

            for (ChangeSet changeSet : orderedChangeSets(ctx)) {
                if (reviewed.contains(CheckContext.keyOf(changeSet))) {
                    for (String statement : ctx.lowerCaseSql(changeSet).split(";")) {
                        Matcher alter = ALTER_COLUMN.matcher(statement);
                        if (alter.find()) {
                            String key = tableOf(alter.group(1)) + "." + unquote(alter.group(2));
                            String before = known.get(key);
                            if (before != null && narrows(before, alter.group(3))) {
                                findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                                        "O changeset %s reduz a coluna %s de %s para %s; o que nao couber sera cortado ou recusado."
                                                .formatted(changeSet.getId(), key, before, alter.group(3).replaceAll("\\s+", " "))));
                            }
                        }
                    }
                }
                remember(changeSet, known);
            }
            return findings;
        }

        private static void remember(ChangeSet changeSet, Map<String, String> known) {
            for (Change change : orEmpty(changeSet.getChanges())) {
                if (change instanceof CreateTableChange table) {
                    for (ColumnConfig column : orEmpty(table.getColumns())) {
                        put(known, table.getTableName(), column.getName(), column.getType());
                    }
                } else if (change instanceof AddColumnChange add) {
                    for (ColumnConfig column : orEmpty(add.getColumns())) {
                        put(known, add.getTableName(), column.getName(), column.getType());
                    }
                } else if (change instanceof ModifyDataTypeChange modify) {
                    put(known, modify.getTableName(), modify.getColumnName(), modify.getNewDataType());
                }
            }
        }

        private static void put(Map<String, String> known, String table, String column, String type) {
            if (!blank(table) && !blank(column) && !blank(type)) {
                known.put(tableOf(table) + "." + unquote(column).toLowerCase(Locale.ROOT), type.toLowerCase(Locale.ROOT));
            }
        }

        /** A type and its size, as far as it can be read: {@code varchar(200)} is (varchar, 200, -). */
        private record Type(String base, long first, long second) {
        }

        private static Type parse(String text) {
            String t = text.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+varying", "").replaceAll("\\s+", "");
            Matcher sized = Pattern.compile("^([a-z0-9_]+)(?:\\((\\d+|max)(?:,(\\d+))?\\))?").matcher(t);
            if (!sized.find()) {
                return new Type(t, -1, -1);
            }
            long first = sized.group(2) == null ? -1 : "max".equals(sized.group(2)) ? Long.MAX_VALUE : Long.parseLong(sized.group(2));
            long second = sized.group(3) == null ? -1 : Long.parseLong(sized.group(3));
            return new Type(sized.group(1), first, second);
        }

        private static final Set<String> TEXT = Set.of("varchar", "nvarchar", "char", "nchar", "character", "varbinary", "binary");
        private static final Map<String, Integer> WHOLE = Map.of(
                "tinyint", 1, "smallint", 2, "int2", 2, "int", 4, "integer", 4, "int4", 4, "bigint", 8, "int8", 8);
        private static final Set<String> EXACT = Set.of("decimal", "numeric");

        /** True only when it is certain the new type holds less than the old one. */
        public static boolean narrows(String before, String after) {
            Type from = parse(before);
            Type to = parse(after);
            if (TEXT.contains(from.base()) && TEXT.contains(to.base())) {
                return from.first() > 0 && to.first() > 0 && to.first() < from.first();
            }
            if (WHOLE.containsKey(from.base()) && WHOLE.containsKey(to.base())) {
                return WHOLE.get(to.base()) < WHOLE.get(from.base());
            }
            if (EXACT.contains(from.base()) && EXACT.contains(to.base()) && from.first() > 0 && to.first() > 0) {
                return to.first() < from.first() || (from.second() >= 0 && to.second() >= 0 && to.second() < from.second());
            }
            return false;
        }
    }

    /**
     * A UNIQUE or PRIMARY KEY added to a table that already exists, with nothing in the changelog that removes duplicates first.
     * The constraint fails in production, where the real data is, after passing everywhere else.
     *
     * <p>What counts as dealing with duplicates: an earlier changeset (or this one, before the constraint) that deletes from the
     * table, one whose id or comment says {@code dedup}, or a precondition on this changeset that counts or groups. A table the
     * same changeset creates is new, so it is not judged.
     */
    public static final class UniqueIndexRequiresDedup implements PolicyCheck {

        private static final Pattern ADD_CONSTRAINT = Pattern.compile(
                "alter\\s+table\\s+(?:only\\s+)?([^\\s(]+)\\s+add\\s+(?:constraint\\s+\\S+\\s+)?(?:unique|primary\\s+key)\\b");
        private static final Pattern UNIQUE_INDEX = Pattern.compile(
                "create\\s+unique\\s+(?:clustered\\s+|nonclustered\\s+)?index\\s+\\S+\\s+on\\s+([^\\s(]+)");
        private static final Pattern CREATES = Pattern.compile("create\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([^\\s(]+)");
        private static final Pattern DEDUP_WORDS = Pattern.compile("dedup|duplicad|duplicate");

        @Override
        public String code() {
            return "UniqueIndexRequiresDedup";
        }

        @Override
        public CheckScope scope() {
            return CheckScope.CHANGELOG;
        }

        @Override
        public int defaultSeverity() {
            return Severity.BLOCKER;
        }

        @Override
        public List<Finding> evaluate(CheckContext ctx, Map<String, Object> params) {
            List<Finding> findings = new ArrayList<>();
            Set<String> reviewed = new HashSet<>();
            ctx.changeSetsUnderReview().forEach(cs -> reviewed.add(CheckContext.keyOf(cs)));
            List<ChangeSet> before = new ArrayList<>();

            for (ChangeSet changeSet : orderedChangeSets(ctx)) {
                if (reviewed.contains(CheckContext.keyOf(changeSet))) {
                    findings.addAll(judge(ctx, changeSet, before));
                }
                before.add(changeSet);
            }
            return findings;
        }

        private List<Finding> judge(CheckContext ctx, ChangeSet changeSet, List<ChangeSet> earlier) {
            List<Finding> findings = new ArrayList<>();
            List<String> statements = List.of(ctx.lowerCaseSql(changeSet).split(";"));
            Set<String> createdHere = new HashSet<>();
            for (String statement : statements) {
                Matcher create = CREATES.matcher(statement);
                if (create.find()) {
                    createdHere.add(tableOf(create.group(1)));
                }
            }
            Set<String> reported = new LinkedHashSet<>();
            for (int i = 0; i < statements.size(); i++) {
                String table = uniqueOn(statements.get(i));
                if (table == null || createdHere.contains(table) || !reported.add(table)) {
                    continue;
                }
                boolean dedup = dedupInThisChangeset(statements.subList(0, i), table)
                        || deduplicatedEarlier(ctx, earlier, table)
                        || preconditionCounts(changeSet);
                if (!dedup) {
                    findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                            "O changeset %s cria uma restricao UNIQUE/PRIMARY KEY na tabela %s, que ja existe, sem um changeset anterior que remova duplicados nem uma precondition de duplicidade."
                                    .formatted(changeSet.getId(), table)));
                }
            }
            return findings;
        }

        private static String uniqueOn(String statement) {
            Matcher constraint = ADD_CONSTRAINT.matcher(statement);
            if (constraint.find()) {
                return tableOf(constraint.group(1));
            }
            Matcher index = UNIQUE_INDEX.matcher(statement);
            return index.find() ? tableOf(index.group(1)) : null;
        }

        private static boolean mentionsTable(String text, String table) {
            return Pattern.compile("(?<![\\w])" + Pattern.quote(table) + "(?![\\w])").matcher(text.replaceAll("[\\[\\]\"`]", "")).find();
        }

        private static boolean removesFrom(String statement, String table) {
            return statement.contains("delete") && mentionsTable(statement, table);
        }

        private static boolean dedupInThisChangeset(List<String> before, String table) {
            return before.stream().anyMatch(statement -> removesFrom(statement, table));
        }

        private static boolean deduplicatedEarlier(CheckContext ctx, List<ChangeSet> earlier, String table) {
            for (ChangeSet changeSet : earlier) {
                String words = (String.valueOf(changeSet.getId()) + " " + String.valueOf(changeSet.getComments())).toLowerCase(Locale.ROOT);
                if (DEDUP_WORDS.matcher(words).find() && mentionsTable(words + " " + ctx.lowerCaseSql(changeSet), table)) {
                    return true;
                }
                for (String statement : ctx.lowerCaseSql(changeSet).split(";")) {
                    if (removesFrom(statement, table)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static boolean preconditionCounts(ChangeSet changeSet) {
            StringBuilder text = new StringBuilder();
            collect(changeSet.getPreconditions(), text);
            String sql = text.toString().toLowerCase(Locale.ROOT);
            return sql.contains("count(") || sql.contains("group by") || sql.contains("having");
        }

        private static void collect(PreconditionContainer container, StringBuilder out) {
            if (container != null) {
                collect(container.getNestedPreconditions(), out);
            }
        }

        private static void collect(List<Precondition> preconditions, StringBuilder out) {
            for (Precondition precondition : orEmpty(preconditions)) {
                if (precondition instanceof SqlPrecondition sql && sql.getSql() != null) {
                    out.append(sql.getSql()).append(' ');
                } else if (precondition instanceof PreconditionLogic logic) {
                    collect(logic.getNestedPreconditions(), out);
                }
            }
        }
    }

    // --------------------------------------------------------------- authorization

    /** Adding or removing a member of a role changes who can do what; it deserves a second pair of eyes. */
    public static final class AlterRoleMembership extends SqlPatternCheck {
        public AlterRoleMembership() {
            super("AlterRoleMembership", Severity.CRITICAL,
                    "\\balter\\s+(?:server\\s+)?role\\b[^;]*\\b(?:add|drop)\\s+member\\b|\\bsp_(?:add|drop)(?:srv)?rolemember\\b",
                    "altera quem participa de um papel (ALTER ROLE ... ADD/DROP MEMBER ou sp_addrolemember). Isso muda permissoes e precisa de revisao.");
        }
    }

    // ------------------------------------------------------------------ portability

    /** An identity column and a sequence default in the same table: two ways of numbering rows that disagree. */
    public static final class IdentityVsSequence extends SqlPatternCheck {
        public IdentityVsSequence() {
            super("IdentityVsSequence", Severity.MINOR,
                    "create\\s+table(?=[^;]*\\b(?:identity|serial|bigserial|generated\\s+(?:always|by\\s+default)\\s+as\\s+identity)\\b)"
                            + "(?=[^;]*(?:next\\s+value\\s+for|nextval\\s*\\())[^;]*",
                    "mistura uma coluna identity com um valor padrao vindo de sequence na mesma tabela. Escolha uma das duas formas de numerar.");
        }
    }

    /**
     * An object named with a word the dialect reserves. Liquibase quotes it when it generates SQL, but every hand-written
     * query, report and tool that touches the object has to remember to, and the day the database moves, it breaks.
     */
    public static final class ReservedWordAsIdentifier implements PolicyCheck {

        @Override
        public String code() {
            return "ReservedWordAsIdentifier";
        }

        @Override
        public CheckScope scope() {
            return CheckScope.CHANGELOG;
        }

        @Override
        public int defaultSeverity() {
            return Severity.MAJOR;
        }

        @Override
        public List<Finding> evaluate(CheckContext ctx, Map<String, Object> params) {
            List<Finding> findings = new ArrayList<>();
            if (ctx.database() == null) {
                return findings;
            }
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                Set<String> reserved = new LinkedHashSet<>();
                for (Change change : orEmpty(changeSet.getChanges())) {
                    for (String name : namesOf(change)) {
                        if (!blank(name) && ctx.database().isReservedWord(name.trim())) {
                            reserved.add(name.trim());
                        }
                    }
                }
                if (!reserved.isEmpty()) {
                    findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                            "O changeset %s nomeia objetos com palavra reservada do banco: %s."
                                    .formatted(changeSet.getId(), String.join(", ", reserved))));
                }
            }
            return findings;
        }

        private static List<String> namesOf(Change change) {
            List<String> names = new ArrayList<>();
            if (change instanceof CreateTableChange table) {
                names.add(table.getTableName());
                orEmpty(table.getColumns()).forEach(column -> names.add(column.getName()));
            } else if (change instanceof AddColumnChange add) {
                orEmpty(add.getColumns()).forEach(column -> names.add(column.getName()));
            } else if (change instanceof RenameColumnChange rename) {
                names.add(rename.getNewColumnName());
            } else if (change instanceof CreateIndexChange index) {
                names.add(index.getIndexName());
            } else if (change instanceof CreateViewChange view) {
                names.add(view.getViewName());
            } else if (change instanceof CreateSequenceChange sequence) {
                names.add(sequence.getSequenceName());
            }
            return names;
        }
    }

    /**
     * A three-part name ({@code OtherDb.dbo.table}) ties the changelog to a database name that differs per environment. The
     * parameter {@code allowed} lists database names that are fine (the one the project's placeholder expands to).
     */
    public static final class CrossDatabaseReference implements PolicyCheck {

        private static final String PART = "(\\[?[a-z_][\\w$#]*\\]?)";
        private static final Pattern THREE_PARTS = Pattern.compile(
                "\\b(?:from|join|into|update|table|view|references|exec(?:ute)?)\\s+(?:only\\s+)?" + PART + "\\." + PART + "\\." + PART);

        @Override
        public String code() {
            return "CrossDatabaseReference";
        }

        @Override
        public CheckScope scope() {
            return CheckScope.CHANGELOG;
        }

        @Override
        public int defaultSeverity() {
            return Severity.MAJOR;
        }

        @Override
        public List<Finding> evaluate(CheckContext ctx, Map<String, Object> params) {
            Set<String> allowed = new HashSet<>();
            for (String name : param(params, "allowed", "").split(",")) {
                if (!name.isBlank()) {
                    allowed.add(unquote(name.trim().toLowerCase(Locale.ROOT)));
                }
            }
            List<Finding> findings = new ArrayList<>();
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                Matcher found = THREE_PARTS.matcher(ctx.lowerCaseSql(changeSet));
                while (found.find()) {
                    String database = unquote(found.group(1));
                    if (!allowed.contains(database)) {
                        findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                                "O changeset %s referencia o banco %s por nome (%s). O nome muda por ambiente; use um placeholder como ${db.name}."
                                        .formatted(changeSet.getId(), database, unquote(found.group(0).replaceAll("^\\S+\\s+", "")))));
                        break;
                    }
                }
            }
            return findings;
        }
    }

    /**
     * An explicit {@code COLLATE} in a column definition. The parameter {@code database} is the collation of the database: a
     * column that spells out the same one is fine. Without it every explicit collation is pointed out, because a changelog
     * cannot see the database it will run against.
     */
    public static final class CollationExplicit implements PolicyCheck {

        private static final Pattern COLLATE = Pattern.compile("\\bcollate\\s+([\\w.\"\\[\\]]+)");

        @Override
        public String code() {
            return "CollationExplicit";
        }

        @Override
        public CheckScope scope() {
            return CheckScope.CHANGELOG;
        }

        @Override
        public int defaultSeverity() {
            return Severity.MINOR;
        }

        @Override
        public List<Finding> evaluate(CheckContext ctx, Map<String, Object> params) {
            String same = unquote(param(params, "database", "").trim().toLowerCase(Locale.ROOT));
            List<Finding> findings = new ArrayList<>();
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                for (String statement : ctx.lowerCaseSql(changeSet).split(";")) {
                    String text = statement.strip();
                    if (!(text.startsWith("create table") || text.startsWith("alter table"))) {
                        continue;
                    }
                    Matcher collate = COLLATE.matcher(text);
                    while (collate.find()) {
                        String name = unquote(collate.group(1));
                        if (!name.equals(same)) {
                            findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                                    "O changeset %s define a collation %s numa coluna; ela pode divergir da do banco."
                                            .formatted(changeSet.getId(), name)));
                            break;
                        }
                    }
                }
            }
            return findings;
        }
    }

    // ------------------------------------------------------------------- metadata

    /** A table created without a description ({@code remarks}). */
    public static final class TableCommentRequired implements PolicyCheck {

        @Override
        public String code() {
            return "TableCommentRequired";
        }

        @Override
        public CheckScope scope() {
            return CheckScope.CHANGELOG;
        }

        @Override
        public int defaultSeverity() {
            return Severity.INFO;
        }

        @Override
        public List<Finding> evaluate(CheckContext ctx, Map<String, Object> params) {
            List<Finding> findings = new ArrayList<>();
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                for (Change change : orEmpty(changeSet.getChanges())) {
                    if (change instanceof CreateTableChange table && blank(table.getRemarks())) {
                        findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                                "A tabela %s foi criada sem descricao (remarks).".formatted(table.getTableName())));
                    }
                }
            }
            return findings;
        }
    }

    /** Columns created without a description ({@code remarks}); one finding per changeset, naming a few of them. */
    public static final class ColumnCommentRequired implements PolicyCheck {

        private static final int NAMED = 5;

        @Override
        public String code() {
            return "ColumnCommentRequired";
        }

        @Override
        public CheckScope scope() {
            return CheckScope.CHANGELOG;
        }

        @Override
        public int defaultSeverity() {
            return Severity.INFO;
        }

        @Override
        public List<Finding> evaluate(CheckContext ctx, Map<String, Object> params) {
            List<Finding> findings = new ArrayList<>();
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                List<String> missing = new ArrayList<>();
                for (Change change : orEmpty(changeSet.getChanges())) {
                    List<? extends ColumnConfig> columns = change instanceof CreateTableChange table ? orEmpty(table.getColumns())
                            : change instanceof AddColumnChange add ? orEmpty(add.getColumns()) : List.of();
                    columns.stream().filter(column -> blank(column.getRemarks())).forEach(column -> missing.add(column.getName()));
                }
                if (!missing.isEmpty()) {
                    String shown = String.join(", ", missing.subList(0, Math.min(NAMED, missing.size())))
                            + (missing.size() > NAMED ? " e mais " + (missing.size() - NAMED) : "");
                    findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                            "O changeset %s cria %d coluna(s) sem descricao (remarks): %s."
                                    .formatted(changeSet.getId(), missing.size(), shown)));
                }
            }
            return findings;
        }
    }

    // ------------------------------------------------------------------ sensitive

    /**
     * A column whose name suggests personal data, created in a changeset that does not carry the label {@code pii}. The label is
     * what lets everyone downstream (reviewers, the catalogue, retention) find the changes that touch personal data.
     * Names come from the parameter {@code columns}.
     */
    public static final class PiiColumnClassification implements PolicyCheck {

        @Override
        public String code() {
            return "PiiColumnClassification";
        }

        @Override
        public CheckScope scope() {
            return CheckScope.CHANGELOG;
        }

        @Override
        public int defaultSeverity() {
            return Severity.MAJOR;
        }

        @Override
        public List<Finding> evaluate(CheckContext ctx, Map<String, Object> params) {
            List<String> names = new ArrayList<>();
            for (String name : param(params, "columns", Phase3Checks.DEFAULT_PII_COLUMNS).split(",")) {
                if (!name.isBlank()) {
                    names.add(name.trim().toLowerCase(Locale.ROOT));
                }
            }
            List<Finding> findings = new ArrayList<>();
            if (names.isEmpty()) {
                return findings;
            }
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                if (hasPiiLabel(changeSet)) {
                    continue;
                }
                Set<String> columns = new LinkedHashSet<>();
                for (String statement : ctx.lowerCaseSql(changeSet).split(";")) {
                    columns.addAll(Phase3Checks.definedColumns(statement.strip(), names));
                }
                if (!columns.isEmpty()) {
                    findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                            "O changeset %s cria %s, que parece conter dado pessoal, e nao tem o label pii."
                                    .formatted(changeSet.getId(), String.join(", ", columns))));
                }
            }
            return findings;
        }

        private static boolean hasPiiLabel(ChangeSet changeSet) {
            return changeSet.getLabels() != null && changeSet.getLabels().getLabels().stream()
                    .anyMatch(label -> "pii".equalsIgnoreCase(label.trim()));
        }
    }

    /**
     * An {@code INSERT} whose values look like real people: a CPF with valid check digits, or an e-mail that is not on an example
     * domain. Real personal data does not belong in a changelog that sits in a repository.
     */
    public static final class SeedDataWithPii implements PolicyCheck {

        private static final Pattern CPF = Pattern.compile("(?<![\\d.])(\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2})(?![\\d])");
        private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@([a-z0-9-]+(?:\\.[a-z0-9-]+)+)");
        private static final List<String> FAKE_DOMAINS = List.of("example.com", "example.org", "example.net", "test", "local",
                "invalid", "localhost", "liquimanager.local");

        @Override
        public String code() {
            return "SeedDataWithPii";
        }

        @Override
        public CheckScope scope() {
            return CheckScope.CHANGELOG;
        }

        @Override
        public int defaultSeverity() {
            return Severity.CRITICAL;
        }

        @Override
        public List<Finding> evaluate(CheckContext ctx, Map<String, Object> params) {
            List<Finding> findings = new ArrayList<>();
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                String found = null;
                for (String statement : ctx.lowerCaseSql(changeSet).split(";")) {
                    if (!statement.strip().startsWith("insert")) {
                        continue;
                    }
                    found = realCpf(statement) != null ? "um CPF" : realEmail(statement) != null ? "um e-mail" : null;
                    if (found != null) {
                        break;
                    }
                }
                if (found != null) {
                    findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                            "O changeset %s insere dados que parecem conter %s de uma pessoa real."
                                    .formatted(changeSet.getId(), found)));
                }
            }
            return findings;
        }

        private static String realCpf(String statement) {
            Matcher matcher = CPF.matcher(statement);
            while (matcher.find()) {
                if (validCpf(matcher.group(1).replaceAll("[.-]", ""))) {
                    return matcher.group(1);
                }
            }
            return null;
        }

        private static String realEmail(String statement) {
            Matcher matcher = EMAIL.matcher(statement);
            while (matcher.find()) {
                String domain = matcher.group(1);
                if (FAKE_DOMAINS.stream().noneMatch(fake -> domain.equals(fake) || domain.endsWith("." + fake))) {
                    return matcher.group();
                }
            }
            return null;
        }

        /** The two check digits, and not a run of one digit: an id or a counter has no reason to pass both. */
        static boolean validCpf(String digits) {
            if (digits.length() != 11 || digits.chars().distinct().count() == 1) {
                return false;
            }
            return digit(digits, 9) == digits.charAt(9) - '0' && digit(digits, 10) == digits.charAt(10) - '0';
        }

        private static int digit(String digits, int count) {
            int sum = 0;
            for (int i = 0; i < count; i++) {
                sum += (digits.charAt(i) - '0') * (count + 1 - i);
            }
            int rest = (sum * 10) % 11;
            return rest == 10 ? 0 : rest;
        }
    }

    /**
     * A {@code loadData} that brings in a lot of data. The catalogue measures the CSV; a check has no file system, so this measures
     * what Liquibase generates from it (the INSERTs), which is larger than the file: the parameter {@code maxMb} (default 5) is
     * therefore in megabytes of generated SQL. When nothing was generated, nothing is said.
     */
    public static final class LoadDataFileSize implements PolicyCheck {

        @Override
        public String code() {
            return "LoadDataFileSize";
        }

        @Override
        public CheckScope scope() {
            return CheckScope.CHANGELOG;
        }

        @Override
        public int defaultSeverity() {
            return Severity.MINOR;
        }

        @Override
        public List<Finding> evaluate(CheckContext ctx, Map<String, Object> params) {
            long limit = Math.max(1, intParam(params, "maxMb", 5)) * 1_000_000L;
            List<Finding> findings = new ArrayList<>();
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                boolean loads = orEmpty(changeSet.getChanges()).stream().anyMatch(change -> change instanceof LoadDataChange);
                if (!loads) {
                    continue;
                }
                long bytes = ctx.generatedSql().apply(changeSet).stream().mapToLong(sql -> sql.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum();
                if (bytes > limit) {
                    findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                            "O changeset %s carrega dados que geram cerca de %.1f MB de SQL (limite %d MB). Considere um carregador proprio para volumes grandes."
                                    .formatted(changeSet.getId(), bytes / 1_000_000.0, limit / 1_000_000)));
                }
            }
            return findings;
        }
    }

    // ------------------------------------------------------------------- ordering

    /** The changelog's changesets in order, with the ones under review added if the changelog does not list them. */
    private static List<ChangeSet> orderedChangeSets(CheckContext ctx) {
        List<ChangeSet> ordered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (ctx.changelog() != null) {
            for (ChangeSet changeSet : orEmpty(ctx.changelog().getChangeSets())) {
                if (seen.add(CheckContext.keyOf(changeSet))) {
                    ordered.add(changeSet);
                }
            }
        }
        for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
            if (seen.add(CheckContext.keyOf(changeSet))) {
                ordered.add(changeSet);
            }
        }
        return ordered;
    }
}
