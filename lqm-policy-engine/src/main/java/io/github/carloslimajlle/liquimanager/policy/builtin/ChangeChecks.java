package io.github.carloslimajlle.liquimanager.policy.builtin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import io.github.carloslimajlle.liquimanager.policy.CheckContext;
import io.github.carloslimajlle.liquimanager.policy.CheckScope;
import io.github.carloslimajlle.liquimanager.policy.Finding;
import io.github.carloslimajlle.liquimanager.policy.PolicyCheck;
import io.github.carloslimajlle.liquimanager.policy.Severity;
import liquibase.change.AbstractSQLChange;
import liquibase.change.Change;
import liquibase.change.ChangeParameterMetaData;
import liquibase.change.ColumnConfig;
import liquibase.change.ConstraintsConfig;
import liquibase.change.core.AddColumnChange;
import liquibase.change.core.AddForeignKeyConstraintChange;
import liquibase.change.core.AddPrimaryKeyChange;
import liquibase.change.core.AddUniqueConstraintChange;
import liquibase.change.core.CreateIndexChange;
import liquibase.change.core.CreateTableChange;
import liquibase.changelog.ChangeSet;

/**
 * The phase-1 checks that read the changeset's MODEL (who wrote it, how it is labelled,
 * what the changes name) instead of the SQL it generates. They exist as separate checks,
 * and not as SQL patterns, because the thing they judge is not in the SQL: an author or a
 * label never reaches the database.
 *
 * <p>The same rule as everywhere: when the model cannot give a straight answer, say
 * nothing. A nested class's simple name IS its catalogue code.
 */
public final class ChangeChecks {

    private ChangeChecks() {
    }

    // ------------------------------------------------------------- authorship

    /** An empty author: the audit trail would name nobody. */
    public static final class AuthorRequired implements PolicyCheck {

        @Override
        public String code() {
            return "AuthorRequired";
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
            List<Finding> findings = new ArrayList<>();
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                String author = changeSet.getAuthor();
                if (author == null || author.isBlank()) {
                    findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                            "O changeset %s nao tem autor.".formatted(changeSet.getId())));
                }
            }
            return findings;
        }
    }

    /**
     * An author outside the {@code allowed} list (comma-separated). With no list given the
     * check has nothing to compare against and stays quiet: an empty allow-list that
     * rejected everyone would block every deployment the moment the check was switched on.
     */
    public static final class AuthorAllowedList implements PolicyCheck {

        @Override
        public String code() {
            return "AuthorAllowedList";
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
            Set<String> allowed = Arrays.stream(param(params, "allowed", "").split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            if (allowed.isEmpty()) {
                return List.of();
            }
            List<Finding> findings = new ArrayList<>();
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                String author = changeSet.getAuthor() == null ? "" : changeSet.getAuthor().toLowerCase(Locale.ROOT);
                if (!allowed.contains(author)) {
                    findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                            "O autor \"%s\" do changeset %s nao esta na lista permitida."
                                    .formatted(changeSet.getAuthor(), changeSet.getId())));
                }
            }
            return findings;
        }
    }

    // ------------------------------------------------------------------ labels

    /**
     * No release label. The Deployment Matrix and the release filter group changesets by
     * the {@code release-} label, so one without it falls out of both. The {@code prefix}
     * parameter changes what counts as a release label.
     */
    public static final class LabelRequired implements PolicyCheck {

        @Override
        public String code() {
            return "LabelRequired";
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
            String prefix = param(params, "prefix", "release-");
            List<Finding> findings = new ArrayList<>();
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                boolean hasRelease = changeSet.getLabels() != null
                        && changeSet.getLabels().getLabels().stream().anyMatch(label -> label.startsWith(prefix));
                if (!hasRelease) {
                    findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                            "O changeset %s nao tem label de release (%s...).".formatted(changeSet.getId(), prefix)));
                }
            }
            return findings;
        }
    }

    // ----------------------------------------------------------------- schema

    /**
     * A change that does not name its schema runs against whatever the connection's default
     * schema is — which differs between the developer's login and the application's.
     *
     * <p>Read through the change's own metadata: any change that HAS a {@code schemaName}
     * parameter is checked, without this class having to know every change type.
     */
    public static final class SchemaRequired implements PolicyCheck {

        @Override
        public String code() {
            return "SchemaRequired";
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
            List<Finding> findings = new ArrayList<>();
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                for (Change change : orEmpty(changeSet.getChanges())) {
                    if (missingSchema(change)) {
                        findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                                "O changeset %s tem uma mudanca (%s) sem schemaName explicito."
                                        .formatted(changeSet.getId(), change.getClass().getSimpleName())));
                        break;
                    }
                }
            }
            return findings;
        }

        private static boolean missingSchema(Change change) {
            try {
                ChangeParameterMetaData parameter = change.createChangeMetaData().getParameters().get("schemaName");
                if (parameter == null) {
                    return false;
                }
                Object value = parameter.getCurrentValue(change);
                return value == null || value.toString().isBlank();
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ dbms

    /**
     * Raw SQL ({@code <sql>}, {@code <sqlFile>}) with no {@code dbms}: it will run, as
     * written, against every database the project ever targets. A statement that only means
     * something on SQL Server then fails on the first PostgreSQL.
     */
    public static final class DbmsAttributeRequiredForRawSql implements PolicyCheck {

        @Override
        public String code() {
            return "DbmsAttributeRequiredForRawSql";
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
            List<Finding> findings = new ArrayList<>();
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                boolean changesetHasDbms = changeSet.getDbmsSet() != null && !changeSet.getDbmsSet().isEmpty();
                if (changesetHasDbms) {
                    continue;
                }
                for (Change change : orEmpty(changeSet.getChanges())) {
                    if (change instanceof AbstractSQLChange sql && (sql.getDbms() == null || sql.getDbms().isBlank())) {
                        findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                                "O changeset %s usa SQL cru sem o atributo dbms.".formatted(changeSet.getId())));
                        break;
                    }
                }
            }
            return findings;
        }
    }

    // ------------------------------------------------------------- structure

    /** A table with more than {@code max} columns (default 50): usually a design that wants splitting. */
    public static final class TableColumnLimit implements PolicyCheck {

        @Override
        public String code() {
            return "TableColumnLimit";
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
            int max = Math.max(1, intParam(params, "max", 50));
            List<Finding> findings = new ArrayList<>();
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                for (Change change : orEmpty(changeSet.getChanges())) {
                    if (change instanceof CreateTableChange table && table.getColumns().size() > max) {
                        findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                                "A tabela %s tem %d colunas; o limite e %d."
                                        .formatted(table.getTableName(), table.getColumns().size(), max)));
                    }
                }
            }
            return findings;
        }
    }

    // ------------------------------------------------------------- naming

    /**
     * Constraint and index names carry their kind as a prefix: {@code pk_}, {@code fk_},
     * {@code uk_}, {@code ix_}. A name nobody gave is not this check's business — an
     * unnamed constraint is a different complaint — so only names that exist are judged.
     * Each prefix can be changed through a parameter of the same short name.
     */
    public static final class ObjectNamingConvention implements PolicyCheck {

        @Override
        public String code() {
            return "ObjectNamingConvention";
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
            String pk = param(params, "pk", "pk_");
            String fk = param(params, "fk", "fk_");
            String uk = param(params, "uk", "uk_");
            String ix = param(params, "ix", "ix_");

            List<Finding> findings = new ArrayList<>();
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                String key = CheckContext.keyOf(changeSet);
                for (Change change : orEmpty(changeSet.getChanges())) {
                    if (change instanceof AddPrimaryKeyChange c) {
                        verify(findings, key, "chave primaria", c.getConstraintName(), pk);
                    } else if (change instanceof AddForeignKeyConstraintChange c) {
                        verify(findings, key, "chave estrangeira", c.getConstraintName(), fk);
                    } else if (change instanceof AddUniqueConstraintChange c) {
                        verify(findings, key, "unique", c.getConstraintName(), uk);
                    } else if (change instanceof CreateIndexChange c) {
                        verify(findings, key, "indice", c.getIndexName(), ix);
                    } else if (change instanceof CreateTableChange table) {
                        for (ColumnConfig column : orEmpty(table.getColumns())) {
                            ConstraintsConfig constraints = column.getConstraints();
                            if (constraints != null) {
                                verify(findings, key, "chave primaria", constraints.getPrimaryKeyName(), pk);
                                verify(findings, key, "chave estrangeira", constraints.getForeignKeyName(), fk);
                                verify(findings, key, "unique", constraints.getUniqueConstraintName(), uk);
                            }
                        }
                    }
                }
            }
            return findings;
        }

        private void verify(List<Finding> findings, String key, String kind, String name, String prefix) {
            if (name != null && !name.isBlank() && !name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                findings.add(new Finding(code(), defaultSeverity(), key, name, null,
                        "O nome \"%s\" (%s) deveria comecar com \"%s\".".formatted(name, kind, prefix)));
            }
        }
    }

    /** Base for the two name-pattern checks: the pattern comes from the {@code pattern} parameter. */
    abstract static class NamePatternCheck implements PolicyCheck {

        /** snake_case, starting with a letter: what most databases already use. */
        static final String DEFAULT_PATTERN = "^[a-z][a-z0-9_]*$";

        @Override
        public final CheckScope scope() {
            return CheckScope.CHANGELOG;
        }

        @Override
        public final int defaultSeverity() {
            return Severity.MINOR;
        }

        /** The (kind, name) pairs this check judges in a change. */
        abstract List<String> namesIn(Change change);

        abstract String kind();

        @Override
        public final List<Finding> evaluate(CheckContext ctx, Map<String, Object> params) {
            Pattern pattern;
            try {
                pattern = Pattern.compile(param(params, "pattern", DEFAULT_PATTERN));
            } catch (PatternSyntaxException e) {
                return List.of();
            }
            List<Finding> findings = new ArrayList<>();
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                for (Change change : orEmpty(changeSet.getChanges())) {
                    for (String name : namesIn(change)) {
                        if (name != null && !name.isBlank() && !pattern.matcher(name).matches()) {
                            findings.add(new Finding(code(), defaultSeverity(), CheckContext.keyOf(changeSet), name, null,
                                    "O nome de %s \"%s\" nao segue o padrao %s.".formatted(kind(), name, pattern.pattern())));
                        }
                    }
                }
            }
            return findings;
        }
    }

    /** Table names outside the pattern (default: snake_case). */
    public static final class TableNamePattern extends NamePatternCheck {

        @Override
        public String code() {
            return "TableNamePattern";
        }

        @Override
        String kind() {
            return "tabela";
        }

        @Override
        List<String> namesIn(Change change) {
            return change instanceof CreateTableChange table ? List.of(String.valueOf(table.getTableName())) : List.of();
        }
    }

    /** Column names outside the pattern (default: snake_case), for new tables and added columns. */
    public static final class ColumnNamePattern extends NamePatternCheck {

        @Override
        public String code() {
            return "ColumnNamePattern";
        }

        @Override
        String kind() {
            return "coluna";
        }

        @Override
        List<String> namesIn(Change change) {
            // AddColumnChange hands back AddColumnConfig, a subtype, so the list is read as "some ColumnConfig".
            List<? extends ColumnConfig> columns = change instanceof CreateTableChange table ? table.getColumns()
                    : change instanceof AddColumnChange add ? add.getColumns() : List.<ColumnConfig>of();
            return orEmpty(columns).stream().map(ColumnConfig::getName).toList();
        }
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
