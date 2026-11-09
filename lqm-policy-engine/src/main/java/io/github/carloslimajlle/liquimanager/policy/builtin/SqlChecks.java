package io.github.carloslimajlle.liquimanager.policy.builtin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.carloslimajlle.liquimanager.policy.CheckContext;
import io.github.carloslimajlle.liquimanager.policy.CheckScope;
import io.github.carloslimajlle.liquimanager.policy.Finding;
import io.github.carloslimajlle.liquimanager.policy.PolicyCheck;
import io.github.carloslimajlle.liquimanager.policy.Severity;
import liquibase.changelog.ChangeSet;

/**
 * The phase-1 checks that read the GENERATED SQL, grouped here because each is a pattern
 * and a sentence. They share one rule, the one every SQL check lives by: a false
 * positive blocks a deployment, so when a pattern cannot be sure, it says nothing
 * (ADR-004).
 *
 * <p>Statements arrive lower-cased and joined with {@code ";\n"}
 * ({@link CheckContext#lowerCaseSql}), so {@code [^;]} reads as "within one statement".
 * A nested class's simple name IS its catalogue code, which the invariants test enforces.
 */
public final class SqlChecks {

    private SqlChecks() {
    }

    /** {@code ALTER TABLE ... DROP COLUMN}: the data in the column is gone, and rollback cannot bring it back. */
    public static final class DropColumnWarn extends SqlPatternCheck {
        public DropColumnWarn() {
            super("DropColumnWarn", Severity.CRITICAL,
                    "\\balter\\s+table\\b[^;]*\\bdrop\\s+column\\b",
                    "remove uma coluna (DROP COLUMN). Os dados dela se perdem e o rollback so recria a estrutura.");
        }
    }

    /**
     * {@code UPDATE} with no {@code WHERE}. Anchored on the start of a statement, because
     * {@code ON UPDATE CASCADE} in a foreign key and {@code FOR UPDATE} in a query are
     * not updates of rows.
     */
    public static final class UpdateWithoutWhere extends SqlPatternCheck {
        public UpdateWithoutWhere() {
            super("UpdateWithoutWhere", Severity.BLOCKER,
                    "(?:^|;)\\s*update\\s+\\S+\\s+set\\b(?:(?!\\bwhere\\b)[^;])*(?:;|$)",
                    "faz UPDATE sem WHERE, o que altera todas as linhas da tabela.");
        }
    }

    /**
     * {@code NOT NULL} on a table that already exists (ADD or ALTER COLUMN) with no DEFAULT:
     * the statement fails the moment the table has a single row, or has to backfill under a lock.
     */
    public static final class AddNotNullWithoutDefault extends SqlPatternCheck {
        public AddNotNullWithoutDefault() {
            super("AddNotNullWithoutDefault", Severity.CRITICAL,
                    "\\balter\\s+table\\b(?![^;]*\\bdefault\\b)[^;]*\\b(?:add|alter\\s+column)\\b[^;]*\\bnot\\s+null\\b",
                    "adiciona NOT NULL em tabela existente sem DEFAULT. Falha se a tabela ja tiver linhas.");
        }
    }

    /** A rename breaks every consumer that still uses the old name, and nothing in the database says who. */
    public static final class RenameObjectWarn extends SqlPatternCheck {
        public RenameObjectWarn() {
            super("RenameObjectWarn", Severity.MAJOR,
                    "\\bsp_rename\\b|\\balter\\s+table\\b[^;]*\\brename\\b|\\brename\\s+table\\b",
                    "renomeia um objeto. Quem ainda usa o nome antigo quebra no proximo deploy.");
        }
    }

    /** {@code WITH GRANT OPTION} lets the grantee hand the privilege on, outside any review. */
    public static final class GrantWithGrantOption extends SqlPatternCheck {
        public GrantWithGrantOption() {
            super("GrantWithGrantOption", Severity.CRITICAL,
                    "\\bwith\\s+grant\\s+option\\b",
                    "concede com WITH GRANT OPTION, permitindo repassar o privilegio sem revisao.");
        }
    }

    /** An application changelog creating logins or users is a privilege decision made in the wrong place. */
    public static final class CreateLoginOrUser extends SqlPatternCheck {
        public CreateLoginOrUser() {
            super("CreateLoginOrUser", Severity.CRITICAL,
                    "\\bcreate\\s+(?:login|user)\\b",
                    "cria login ou usuario de banco. Isso deve ser feito pela equipe de acesso, nao pelo changelog da aplicacao.");
        }
    }

    /**
     * {@code TEXT}, {@code NTEXT} and {@code IMAGE} in a table definition (SQL Server
     * deprecated them years ago). The type must follow whitespace, so a bracketed
     * column named {@code [text]} does not trip it.
     */
    public static final class DeprecatedDataType extends SqlPatternCheck {
        public DeprecatedDataType() {
            super("DeprecatedDataType", Severity.MAJOR,
                    "\\b(?:create|alter)\\s+table\\b[^;]*\\s(?:text|ntext|image)\\b(?!\\s*\\()",
                    "usa um tipo obsoleto (TEXT, NTEXT ou IMAGE). Prefira VARCHAR(MAX), NVARCHAR(MAX) ou VARBINARY(MAX).");
        }
    }

    /**
     * {@code SELECT *} inside a view, procedure or function breaks, or silently changes, when
     * a column is added.
     *
     * <p>A view is ONE statement, so the search stops at its end: a {@code select *} in the
     * next statement does not belong to it. A procedure or function body legitimately holds
     * several statements, so there the search runs on until the next CREATE/ALTER — which
     * can still pick up a stray {@code select *} that follows the body. That is tolerated
     * because the check is informational (severity 0).
     */
    public static final class SqlSelectStarWarn extends SqlPatternCheck {
        public SqlSelectStarWarn() {
            super("SqlSelectStarWarn", Severity.INFO,
                    "\\b(?:create|alter)\\s+(?:or\\s+replace\\s+)?view\\b[^;]*?\\bselect\\s+(?:distinct\\s+)?\\*"
                            + "|\\b(?:create|alter)\\s+(?:or\\s+replace\\s+)?(?:proc|procedure|function)\\b"
                            + "(?:(?!\\b(?:create|alter)\\s).)*?\\bselect\\s+(?:distinct\\s+)?\\*",
                    "usa SELECT * em view, procedure ou function. Liste as colunas.");
        }
    }

    /** {@code WITH (NOLOCK)} reads uncommitted data: phantom rows, missing rows, rows twice. */
    public static final class NoLockHintWarn extends SqlPatternCheck {
        public NoLockHintWarn() {
            super("NoLockHintWarn", Severity.MINOR,
                    "\\bwith\\s*\\(\\s*nolock\\b|\\(\\s*nolock\\s*\\)",
                    "usa a dica NOLOCK, que le dados nao confirmados.");
        }
    }

    /**
     * A {@code CREATE TABLE} with no primary key anywhere in the changeset. A key added by a
     * separate {@code ALTER TABLE} in the same changeset counts; a table that only gets one
     * in a later changeset is flagged, which is the right call — until then it has none.
     */
    public static final class PrimaryKeyRequired implements PolicyCheck {

        @Override
        public String code() {
            return "PrimaryKeyRequired";
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
                String sql = ctx.lowerCaseSql(changeSet);
                if (sql.contains("create table") && !sql.contains("primary key")) {
                    findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                            "O changeset %s cria uma tabela sem chave primaria.".formatted(changeSet.getId())));
                }
            }
            return findings;
        }
    }

    /**
     * A table created with a foreign key and not one index beyond the primary key. The
     * join on that key will scan. Informational: it is a hint, not a defect.
     */
    public static final class CheckTablesForIndex implements PolicyCheck {

        @Override
        public String code() {
            return "CheckTablesForIndex";
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
                String sql = ctx.lowerCaseSql(changeSet);
                boolean createsTable = sql.contains("create table");
                boolean hasForeignKey = sql.contains("foreign key") || sql.contains(" references ");
                if (createsTable && hasForeignKey && !sql.contains("create index") && !sql.contains("create unique index")
                        && !sql.contains("create nonclustered index") && !sql.contains("create clustered index")) {
                    findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                            "O changeset %s cria uma tabela com chave estrangeira e nenhum indice alem da PK."
                                    .formatted(changeSet.getId())));
                }
            }
            return findings;
        }
    }

    /**
     * A foreign key added to an existing table with no index created in the same changeset.
     * Deletes on the parent then scan the child table, and take locks while they do.
     */
    public static final class ForeignKeyWithoutIndex implements PolicyCheck {

        @Override
        public String code() {
            return "ForeignKeyWithoutIndex";
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
                String sql = ctx.lowerCaseSql(changeSet);
                boolean addsForeignKey = java.util.regex.Pattern
                        .compile("\\balter\\s+table\\b[^;]*\\badd\\b[^;]*\\bforeign\\s+key\\b").matcher(sql).find();
                if (addsForeignKey && !sql.contains("create index") && !sql.contains("create unique index")
                        && !sql.contains("create nonclustered index") && !sql.contains("create clustered index")) {
                    findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                            "O changeset %s adiciona uma chave estrangeira sem criar um indice na coluna."
                                    .formatted(changeSet.getId())));
                }
            }
            return findings;
        }
    }

    /**
     * {@code GRANT} on a table somebody asked to have reviewed. The {@code tables}
     * parameter is a comma-separated list; with none given, EVERY grant is reviewed —
     * the safer default for a check whose whole purpose is to put a person in the loop.
     */
    public static final class GrantStatementReview implements PolicyCheck {

        private static final java.util.regex.Pattern GRANT = java.util.regex.Pattern.compile("\\bgrant\\b");

        /** Drops the quoting around identifiers, so [dbo].[veiculo] and dbo.veiculo compare equal. */
        private static String unquote(String text) {
            return text.replaceAll("[\\[\\]\"`]", "");
        }

        @Override
        public String code() {
            return "GrantStatementReview";
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
            List<java.util.regex.Pattern> tables = new ArrayList<>();
            for (String table : param(params, "tables", "").split(",")) {
                if (!table.isBlank()) {
                    // Whole-word match: a table called "a" must not match the "a" in "grant".
                    tables.add(java.util.regex.Pattern.compile("\\b"
                            + java.util.regex.Pattern.quote(unquote(table.trim().toLowerCase(Locale.ROOT))) + "\\b"));
                }
            }
            List<Finding> findings = new ArrayList<>();
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                String sql = ctx.lowerCaseSql(changeSet);
                if (!GRANT.matcher(sql).find()) {
                    continue;
                }
                String bare = unquote(sql);
                boolean touchesListed = tables.isEmpty() || tables.stream().anyMatch(p -> p.matcher(bare).find());
                if (touchesListed) {
                    findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                            "O changeset %s concede privilegios (GRANT) e precisa de revisao.".formatted(changeSet.getId())));
                }
            }
            return findings;
        }
    }
}
