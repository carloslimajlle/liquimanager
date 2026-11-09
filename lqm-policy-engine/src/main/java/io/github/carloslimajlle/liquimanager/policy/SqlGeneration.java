package io.github.carloslimajlle.liquimanager.policy;

import java.util.ArrayList;
import java.util.List;

import liquibase.change.Change;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.SqlGeneratorFactory;
import liquibase.statement.SqlStatement;

/**
 * The SQL a changeset would run, for the dialect of a database, WITHOUT running it.
 *
 * <p>Lives in the engine because three places need exactly the same answer: the collector
 * before a deploy, and the API for the dry-run of a check. If they generated SQL differently,
 * a check could pass in the library's test and block in the pipeline.
 *
 * <p>When the SQL cannot be generated (a custom change, an unsupported statement) the result
 * is empty, and every check that reads SQL stays quiet.
 */
public final class SqlGeneration {

    private SqlGeneration() {
    }

    public static List<String> of(ChangeSet changeSet, Database database) {
        try {
            SqlGeneratorFactory factory = SqlGeneratorFactory.getInstance();
            List<String> out = new ArrayList<>();
            for (Change change : changeSet.getChanges()) {
                SqlStatement[] statements = change.generateStatements(database);
                for (Sql sql : factory.generateSql(statements, database)) {
                    out.add(sql.toSql());
                }
            }
            return out;
        } catch (Exception | LinkageError e) {
            return List.of();
        }
    }
}
