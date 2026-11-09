package io.github.carloslimajlle.liquimanager.policy.builtin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.carloslimajlle.liquimanager.policy.CheckContext;
import io.github.carloslimajlle.liquimanager.policy.CheckScope;
import io.github.carloslimajlle.liquimanager.policy.Finding;
import io.github.carloslimajlle.liquimanager.policy.PolicyCheck;
import io.github.carloslimajlle.liquimanager.policy.Severity;
import liquibase.changelog.ChangeSet;

/**
 * The phase-3 checks. Two of them speak about SQL Server features (online index builds,
 * Always Encrypted, dynamic data masking), so they stay silent on any other dialect:
 * flagging a PostgreSQL column for lacking a feature PostgreSQL does not have would be a
 * false positive that blocks a deployment (ADR-004).
 *
 * <p>A nested class's simple name IS its catalogue code.
 */
public final class Phase3Checks {

    private Phase3Checks() {
    }

    /** The column names that suggest personal data, unless a package says otherwise. */
    static final String DEFAULT_PII_COLUMNS = "cpf,cnpj,rg,email,telefone,cnh,placa";

    /**
     * The columns of {@code names} that a CREATE TABLE or ALTER TABLE ... ADD statement defines. A definition starts after "(",
     * "," or "add [column]"; the next word must be a type, so a constraint or an index key over the column is not a definition.
     */
    static List<String> definedColumns(String statement, List<String> names) {
        List<String> found = new ArrayList<>();
        if (names.isEmpty() || !(statement.startsWith("create table") || statement.startsWith("alter table"))) {
            return found;
        }
        Matcher column = definition(names).matcher(statement);
        while (column.find()) {
            found.add(unquote(column.group(1)));
        }
        return found;
    }

    private static Pattern definition(List<String> names) {
        List<String> quoted = new ArrayList<>();
        for (String name : names) {
            quoted.add(Pattern.quote(name.trim().toLowerCase(Locale.ROOT)));
        }
        return Pattern.compile("(?:[(,]|\\badd(?:\\s+column)?)\\s*([\\[\"`]?(?:" + String.join("|", quoted)
                + ")[\\]\"`]?)\\s+(?!asc\\b|desc\\b|in\\b)(?=[a-z])");
    }

    private static boolean isSqlServer(CheckContext ctx) {
        return ctx.database() != null && "mssql".equals(ctx.database().getShortName());
    }

    private static String unquote(String text) {
        return text.replaceAll("[\\[\\]\"`]", "");
    }

    /**
     * {@code CREATE INDEX} without {@code ONLINE = ON}: on SQL Server Enterprise the build holds a table
     * lock for as long as it takes.
     *
     * <p>The catalogue wants the table size from a snapshot, which a changelog check does not have. The
     * parameter {@code tables} (comma separated) names the big ones instead; with none listed, every
     * index build is reviewed. The check is not in the default package for that reason.
     */
    public static final class CreateIndexOnline implements PolicyCheck {

        private static final Pattern CREATE_INDEX = Pattern.compile(
                "\\bcreate\\s+(?:unique\\s+)?(?:clustered\\s+|nonclustered\\s+)?index\\s+[^;]*?\\bon\\s+([\\w.\\[\\]\"`]+)");
        private static final Pattern ONLINE_ON = Pattern.compile("\\bonline\\s*=\\s*on\\b");

        @Override
        public String code() {
            return "CreateIndexOnline";
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
            if (!isSqlServer(ctx)) {
                return findings;
            }
            List<String> big = new ArrayList<>();
            for (String table : param(params, "tables", "").split(",")) {
                if (!table.isBlank()) {
                    big.add(unquote(table.trim().toLowerCase(Locale.ROOT)));
                }
            }
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                for (String statement : ctx.lowerCaseSql(changeSet).split(";")) {
                    Matcher index = CREATE_INDEX.matcher(statement);
                    if (!index.find() || ONLINE_ON.matcher(statement).find()) {
                        continue;
                    }
                    String table = unquote(index.group(1));
                    boolean listed = big.isEmpty() || big.stream().anyMatch(b -> table.equals(b) || table.endsWith("." + b));
                    if (listed) {
                        findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                                "O changeset %s cria um indice em %s sem ONLINE = ON; a tabela fica bloqueada durante a criacao."
                                        .formatted(changeSet.getId(), table)));
                        break;
                    }
                }
            }
            return findings;
        }
    }

    /** A cursor declared in a procedure or script: row-by-row work that a set-based statement usually replaces. */
    public static final class CursorUsageWarn extends SqlPatternCheck {
        public CursorUsageWarn() {
            super("CursorUsageWarn", Severity.MINOR,
                    "\\bdeclare\\s+@?\\w+\\s+(?:\\w+\\s+)?cursor\\b|\\bcursor\\s+(?:\\w+\\s+)*for\\s+select\\b",
                    "declara um cursor. Prefira uma instrucao baseada em conjunto, que o otimizador resolve de uma vez.");
        }
    }

    /**
     * A column whose name suggests personal data, created without {@code ENCRYPTED WITH} (Always Encrypted)
     * or {@code MASKED WITH} (dynamic data masking). Names come from the parameter {@code columns}.
     */
    public static final class PiiColumnEncryption implements PolicyCheck {

        @Override
        public String code() {
            return "PiiColumnEncryption";
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
            if (!isSqlServer(ctx)) {
                return findings;
            }
            List<String> names = new ArrayList<>();
            for (String name : param(params, "columns", DEFAULT_PII_COLUMNS).split(",")) {
                if (!name.isBlank()) {
                    names.add(name.trim().toLowerCase(Locale.ROOT));
                }
            }
            if (names.isEmpty()) {
                return findings;
            }
            Pattern definition = definition(names);
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                for (String statement : ctx.lowerCaseSql(changeSet).split(";")) {
                    String text = statement.strip();
                    if (!(text.startsWith("create table") || text.startsWith("alter table"))) {
                        continue;
                    }
                    Matcher column = definition.matcher(text);
                    while (column.find()) {
                        String body = definitionOf(text, column.end());
                        if (!body.contains("encrypted with") && !body.contains("masked with")) {
                            findings.add(Finding.of(code(), defaultSeverity(), CheckContext.keyOf(changeSet),
                                    "O changeset %s cria a coluna %s, que parece conter dado pessoal, sem ENCRYPTED WITH nem MASKED WITH."
                                            .formatted(changeSet.getId(), unquote(column.group(1)))));
                            break;
                        }
                    }
                }
            }
            return findings;
        }

        /** The rest of one column definition: up to the comma or closing parenthesis outside any nested parentheses. */
        private static String definitionOf(String text, int from) {
            int depth = 0;
            for (int i = from; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    if (depth == 0) {
                        return text.substring(from, i);
                    }
                    depth--;
                } else if (c == ',' && depth == 0) {
                    return text.substring(from, i);
                }
            }
            return text.substring(from);
        }
    }
}
