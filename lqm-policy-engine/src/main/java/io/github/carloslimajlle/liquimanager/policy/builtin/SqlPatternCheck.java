package io.github.carloslimajlle.liquimanager.policy.builtin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.github.carloslimajlle.liquimanager.policy.CheckContext;
import io.github.carloslimajlle.liquimanager.policy.CheckScope;
import io.github.carloslimajlle.liquimanager.policy.Finding;
import io.github.carloslimajlle.liquimanager.policy.PolicyCheck;
import liquibase.changelog.ChangeSet;

/**
 * Base class for checks that look for a pattern in the GENERATED SQL.
 *
 * <p>Looking at the generated SQL rather than the XML is what catches the same
 * operation however it was written: {@code <dropTable>}, {@code <sql>DROP
 * TABLE...</sql>} and a {@code <sqlFile>} all end up in the same final text. For
 * XML/YAML/JSON changesets the SQL is generated offline for the target dialect
 * (docs/08 § 4).
 */
abstract class SqlPatternCheck implements PolicyCheck {

    private final String code;
    private final int severity;
    private final Pattern pattern;
    /** Written in pt-BR: it is appended to the user-facing finding message. */
    private final String explanation;

    SqlPatternCheck(String code, int severity, String regex, String explanation) {
        this.code = code;
        this.severity = severity;
        this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        this.explanation = explanation;
    }

    @Override
    public final String code() {
        return code;
    }

    @Override
    public final CheckScope scope() {
        return CheckScope.CHANGELOG;
    }

    @Override
    public final int defaultSeverity() {
        return severity;
    }

    @Override
    public final List<Finding> evaluate(CheckContext ctx, Map<String, Object> params) {
        List<Finding> findings = new ArrayList<>();
        for (ChangeSet changeSet : ctx.changeSetsUnderReview()) {
            String sql = ctx.lowerCaseSql(changeSet);
            if (!sql.isBlank() && pattern.matcher(sql).find()) {
                findings.add(Finding.of(code, severity, CheckContext.keyOf(changeSet),
                        "O changeset %s %s".formatted(changeSet.getId(), explanation)));
            }
        }
        return findings;
    }
}
