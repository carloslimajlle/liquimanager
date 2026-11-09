package io.github.carloslimajlle.liquimanager.policy.builtin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import io.github.carloslimajlle.liquimanager.policy.CheckContext;
import io.github.carloslimajlle.liquimanager.policy.CheckScope;
import io.github.carloslimajlle.liquimanager.policy.EffectiveCheck;
import io.github.carloslimajlle.liquimanager.policy.Finding;
import io.github.carloslimajlle.liquimanager.policy.PolicyCheck;
import liquibase.change.Change;
import liquibase.changelog.ChangeSet;
import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

/**
 * Checks that are DATA, not code: a rule a person wrote in the Policy Library (docs/08 § 3).
 *
 * <p>Three kinds are interpreted here: REGEX, SQL_PATTERN and SPEL. The last one uses only
 * spring-expression (D-21), through a {@code SimpleEvaluationContext}: property reads on the
 * variables below and operators, with no method calls, no types, no constructors, no beans.
 *
 * <p>Same rules as every check. Stateless. And when in doubt, silent: an invalid regex, a
 * parameter that does not parse, SQL that could not be generated — none of these may
 * produce a finding, because a false positive blocks a deployment (ADR-004).
 */
public final class CustomChecks {

    private CustomChecks() {
    }

    /** The check that interprets this definition, or null when the engine cannot run it. */
    public static PolicyCheck create(EffectiveCheck definition) {
        return switch (definition.kind()) {
            case EffectiveCheck.REGEX -> new RegexRule(definition);
            case EffectiveCheck.SQL_PATTERN -> new SqlPatternRule(definition);
            case EffectiveCheck.SPEL -> new SpelRule(definition);
            default -> null;
        };
    }

    /** Why this SPEL expression cannot be used, or null when it parses. Used to refuse it at save time. */
    public static String spelProblem(String expression) {
        if (expression == null || expression.isBlank()) {
            return "the expression is empty";
        }
        if (expression.length() > SpelRule.MAX_LENGTH) {
            return "the expression is longer than " + SpelRule.MAX_LENGTH + " characters";
        }
        try {
            new SpelExpressionParser().parseExpression(expression);
            return null;
        } catch (RuntimeException e) {
            return "the expression does not parse: " + e.getMessage();
        }
    }

    // ----------------------------------------------------------------- shared

    private abstract static class Rule implements PolicyCheck {
        final EffectiveCheck definition;

        Rule(EffectiveCheck definition) {
            this.definition = definition;
        }

        @Override
        public String code() {
            return definition.code();
        }

        @Override
        public CheckScope scope() {
            return CheckScope.CHANGELOG;
        }

        @Override
        public int defaultSeverity() {
            return definition.severity();
        }

        String message(String fallback, ChangeSet changeSet, CheckContext ctx, Map<String, String> extra) {
            String template = definition.message() == null || definition.message().isBlank() ? fallback
                    : definition.message();
            String text = template
                    .replace("<CHANGESET_ID>", String.valueOf(changeSet.getId()))
                    .replace("<AUTHOR>", String.valueOf(changeSet.getAuthor()))
                    .replace("<ENV>", String.valueOf(ctx.environmentCode()));
            for (Map.Entry<String, String> variable : extra.entrySet()) {
                text = text.replace("<" + variable.getKey() + ">", variable.getValue());
            }
            return text;
        }

        Finding finding(ChangeSet changeSet, String message) {
            return Finding.of(definition.code(), definition.severity(), CheckContext.keyOf(changeSet), message);
        }
    }

    // ------------------------------------------------------------------ REGEX

    /** {@code pattern}, {@code appliesTo} (SQL | COMMENT | ID), {@code mustMatch} (false: forbid; true: require). */
    private static final class RegexRule extends Rule {

        RegexRule(EffectiveCheck definition) {
            super(definition);
        }

        @Override
        public List<Finding> evaluate(CheckContext ctx, Map<String, Object> params) {
            String regex = param(params, "pattern", null);
            if (regex == null) {
                return List.of();
            }
            Pattern pattern;
            try {
                pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            } catch (PatternSyntaxException e) {
                return List.of();
            }
            String appliesTo = param(params, "appliesTo", "SQL").toUpperCase(Locale.ROOT);
            boolean mustMatch = Boolean.parseBoolean(param(params, "mustMatch", "false"));

            List<Finding> findings = new ArrayList<>();
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                String subject = subject(ctx, changeSet, appliesTo);
                if (subject == null || (subject.isBlank() && "SQL".equals(appliesTo))) {
                    // Nothing to judge (SQL that could not be generated): stay quiet.
                    continue;
                }
                boolean found = pattern.matcher(subject).find();
                if (found != mustMatch) {
                    String fallback = mustMatch
                            ? "O changeset <CHANGESET_ID> nao contem o padrao exigido."
                            : "O changeset <CHANGESET_ID> contem um padrao proibido.";
                    findings.add(finding(changeSet, message(fallback, changeSet, ctx, Map.of())));
                }
            }
            return findings;
        }

        private static String subject(CheckContext ctx, ChangeSet changeSet, String appliesTo) {
            return switch (appliesTo) {
                case "SQL" -> String.join(";\n", ctx.generatedSql().apply(changeSet));
                case "COMMENT" -> changeSet.getComments() == null ? "" : changeSet.getComments();
                case "ID" -> changeSet.getId();
                default -> null;
            };
        }
    }

    // ------------------------------------------------------------------- SPEL

    /**
     * {@code expression}, true when the changeset BREAKS the rule (like REGEX with {@code mustMatch=false}). Evaluated once
     * per change of the changeset (once, with {@code change} null, when it has none) and at most one finding per changeset.
     *
     * <p>Variables, read as plain names: {@code changeSet} (id, author, comments, labels, contexts, filePath, runOnChange,
     * runAlways, failOnError), {@code change} (type, such as createTable), {@code sql} (what the changeset generates, in
     * lower case) and {@code env}. An expression that does not parse, reads a name that is not there or does not yield a
     * boolean stays silent.
     */
    private static final class SpelRule extends Rule {

        static final int MAX_LENGTH = 500;
        private static final SpelExpressionParser PARSER = new SpelExpressionParser();
        private static final PropertyAccessor MAP_READER = new MapReader();

        SpelRule(EffectiveCheck definition) {
            super(definition);
        }

        @Override
        public List<Finding> evaluate(CheckContext ctx, Map<String, Object> params) {
            String text = param(params, "expression", null);
            if (text == null || spelProblem(text) != null) {
                return List.of();
            }
            Expression expression = PARSER.parseExpression(text);
            EvaluationContext context = SimpleEvaluationContext.forPropertyAccessors(MAP_READER).build();

            List<Finding> findings = new ArrayList<>();
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                Map<String, Object> view = new HashMap<>();
                view.put("changeSet", describe(changeSet));
                view.put("sql", String.join(";\n", ctx.generatedSql().apply(changeSet)).toLowerCase(Locale.ROOT));
                view.put("env", ctx.environmentCode());
                List<Change> changes = changeSet.getChanges();
                for (int i = 0; i < Math.max(1, changes.size()); i++) {
                    view.put("change", changes.isEmpty() ? null : Map.of("type", changes.get(i).getSerializedObjectName()));
                    if (Boolean.TRUE.equals(holds(expression, context, view))) {
                        findings.add(finding(changeSet, message("O changeset <CHANGESET_ID> viola a regra "
                                + definition.code() + ".", changeSet, ctx, Map.of())));
                        break;
                    }
                }
            }
            return findings;
        }

        private static Object holds(Expression expression, EvaluationContext context, Map<String, Object> view) {
            try {
                return expression.getValue(context, view);
            } catch (RuntimeException e) {
                return null; // a name that is not there, a null in the way: no accusation
            }
        }

        private static Map<String, Object> describe(ChangeSet changeSet) {
            Map<String, Object> out = new HashMap<>();
            out.put("id", changeSet.getId());
            out.put("author", changeSet.getAuthor());
            out.put("comments", changeSet.getComments());
            out.put("labels", changeSet.getLabels() == null ? List.of() : changeSet.getLabels().getLabels());
            out.put("contexts", changeSet.getContextFilter() == null ? null : changeSet.getContextFilter().toString());
            out.put("filePath", changeSet.getFilePath());
            out.put("runOnChange", changeSet.isRunOnChange());
            out.put("runAlways", changeSet.isAlwaysRun());
            out.put("failOnError", changeSet.getFailOnError());
            return out;
        }

        /** Lets {@code changeSet.comments} read a key of a map: the only thing the expression can reach. */
        private static final class MapReader implements PropertyAccessor {

            @Override
            public Class<?>[] getSpecificTargetClasses() {
                return new Class<?>[] {Map.class};
            }

            @Override
            public boolean canRead(EvaluationContext context, Object target, String name) {
                return target instanceof Map<?, ?> map && map.containsKey(name);
            }

            @Override
            public TypedValue read(EvaluationContext context, Object target, String name) throws AccessException {
                return new TypedValue(((Map<?, ?>) target).get(name));
            }

            @Override
            public boolean canWrite(EvaluationContext context, Object target, String name) {
                return false;
            }

            @Override
            public void write(EvaluationContext context, Object target, String name, Object newValue) throws AccessException {
                throw new AccessException("read only");
            }
        }
    }

    // ------------------------------------------------------------ SQL_PATTERN

    /**
     * {@code statementType} (CREATE_TABLE, ALTER_TABLE, DROP, GRANT, ...; ALL), {@code operator}
     * (STARTS_WITH, CONTAINS, ENDS_WITH, REGEXP, ALL) applied to {@code tables}, {@code columns},
     * {@code requiredConstraints} (NOT_NULL, UNIQUE, PRIMARYKEY, FOREIGNKEY).
     *
     * <p>Without required constraints, a statement that matches IS the finding ("no DROP on
     * tb_x"). With them, a match is a finding only when the changeset's SQL lacks one of the
     * constraints ("tables tb_pessoa* with a cpf column need a foreign key").
     */
    private static final class SqlPatternRule extends Rule {

        private static final Map<String, Pattern> STATEMENTS = Map.ofEntries(
                Map.entry("CREATE_TABLE", Pattern.compile("^\\s*create\\s+(?:global\\s+temporary\\s+)?table\\s+")),
                Map.entry("ALTER_TABLE", Pattern.compile("^\\s*alter\\s+table\\s+")),
                Map.entry("DROP", Pattern.compile("^\\s*drop\\s+")),
                Map.entry("GRANT", Pattern.compile("^\\s*grant\\s+")),
                Map.entry("INSERT", Pattern.compile("^\\s*insert\\s+into\\s+")),
                Map.entry("UPDATE", Pattern.compile("^\\s*update\\s+")),
                Map.entry("DELETE", Pattern.compile("^\\s*delete\\s+from\\s+")),
                Map.entry("CREATE_INDEX", Pattern.compile("^\\s*create\\s+(?:unique\\s+)?(?:clustered\\s+|nonclustered\\s+)?index\\s+")),
                Map.entry("CREATE_VIEW", Pattern.compile("^\\s*create\\s+(?:or\\s+replace\\s+)?view\\s+")),
                Map.entry("TRUNCATE", Pattern.compile("^\\s*truncate\\s+table\\s+")));

        /** The object a statement acts on: the identifier right after its leading keywords. */
        private static final Pattern TABLE = Pattern.compile(
                "^\\s*(?:create\\s+(?:global\\s+temporary\\s+)?table|alter\\s+table|drop\\s+table(?:\\s+if\\s+exists)?|"
                        + "insert\\s+into|update|delete\\s+from|truncate\\s+table|create\\s+(?:unique\\s+)?(?:clustered\\s+|nonclustered\\s+)?index\\s+\\S+\\s+on|"
                        + "grant\\s+.+?\\s+on)\\s+([\\w.\\[\\]\"`]+)");

        private static final Map<String, Pattern> CONSTRAINTS = Map.of(
                "NOT_NULL", Pattern.compile("not\\s+null"),
                "UNIQUE", Pattern.compile("\\bunique\\b"),
                "PRIMARYKEY", Pattern.compile("primary\\s+key"),
                "FOREIGNKEY", Pattern.compile("foreign\\s+key|\\breferences\\b"));

        SqlPatternRule(EffectiveCheck definition) {
            super(definition);
        }

        @Override
        public List<Finding> evaluate(CheckContext ctx, Map<String, Object> params) {
            String type = param(params, "statementType", "ALL").toUpperCase(Locale.ROOT);
            Pattern statementPattern = "ALL".equals(type) ? null : STATEMENTS.get(type);
            if (statementPattern == null && !"ALL".equals(type)) {
                return List.of();
            }
            String operator = param(params, "operator", "ALL").toUpperCase(Locale.ROOT);
            List<String> tables = list(params.get("tables"));
            List<String> columns = list(params.get("columns"));
            List<String> required = list(params.get("requiredConstraints"));

            List<Finding> findings = new ArrayList<>();
            for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
                List<String> statements = ctx.generatedSql().apply(changeSet).stream()
                        .map(SqlPatternRule::normalise).toList();
                String whole = String.join(";\n", statements);
                if (whole.isBlank()) {
                    continue;
                }
                for (String statement : statements) {
                    if (statementPattern != null && !statementPattern.matcher(statement).find()) {
                        continue;
                    }
                    Matcher tableMatch = TABLE.matcher(statement);
                    String table = tableMatch.find() ? tableMatch.group(1) : "";
                    if (!tableMatches(table, operator, tables)) {
                        continue;
                    }
                    String column = columnIn(statement, columns);
                    if (!columns.isEmpty() && column == null) {
                        continue;
                    }
                    String missing = missingConstraint(whole, required);
                    if (!required.isEmpty() && missing == null) {
                        continue;
                    }
                    Map<String, String> extra = Map.of(
                            "TABLE_NAME", table, "COLUMN_NAME", column == null ? "" : column,
                            "OBJECT", table, "CONSTRAINT", missing == null ? "" : missing);
                    findings.add(finding(changeSet, message(
                            required.isEmpty() ? "O changeset <CHANGESET_ID> faz uma operacao vigiada em <TABLE_NAME>."
                                    : "O changeset <CHANGESET_ID> nao declara <CONSTRAINT> em <TABLE_NAME>.",
                            changeSet, ctx, extra)));
                    break; // one finding per changeset is enough to act on
                }
            }
            return findings;
        }

        private static String normalise(String sql) {
            return sql.toLowerCase(Locale.ROOT).replaceAll("[\\[\\]\"`]", "").trim();
        }

        private static boolean tableMatches(String table, String operator, List<String> tables) {
            if ("ALL".equals(operator) || tables.isEmpty()) {
                return true;
            }
            String name = normalise(table);
            String bare = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
            for (String wanted : tables) {
                String target = wanted.toLowerCase(Locale.ROOT).replace("*", "");
                boolean hit = switch (operator) {
                    case "STARTS_WITH" -> bare.startsWith(target) || name.startsWith(target);
                    case "ENDS_WITH" -> bare.endsWith(target);
                    case "CONTAINS" -> name.contains(target);
                    case "REGEXP" -> regexMatches(wanted, name) || regexMatches(wanted, bare);
                    default -> false;
                };
                if (hit) {
                    return true;
                }
            }
            return false;
        }

        private static boolean regexMatches(String regex, String text) {
            try {
                return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text).find();
            } catch (PatternSyntaxException e) {
                return false;
            }
        }

        private static String columnIn(String statement, List<String> columns) {
            for (String column : columns) {
                if (Pattern.compile("\\b" + Pattern.quote(column.toLowerCase(Locale.ROOT)) + "\\b").matcher(statement).find()) {
                    return column;
                }
            }
            return null;
        }

        private static String missingConstraint(String sql, List<String> required) {
            for (String name : required) {
                Pattern pattern = CONSTRAINTS.get(name.toUpperCase(Locale.ROOT));
                if (pattern != null && !pattern.matcher(sql).find()) {
                    return name.toUpperCase(Locale.ROOT);
                }
            }
            return null;
        }

        private static List<String> list(Object value) {
            if (value == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            if (value instanceof Iterable<?> items) {
                items.forEach(item -> out.add(String.valueOf(item).trim()));
            } else {
                for (String part : String.valueOf(value).split(",")) {
                    out.add(part.trim());
                }
            }
            out.removeIf(String::isBlank);
            return out;
        }
    }
}
