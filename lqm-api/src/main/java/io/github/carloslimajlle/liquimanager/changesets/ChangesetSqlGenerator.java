package io.github.carloslimajlle.liquimanager.changesets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.carloslimajlle.liquimanager.policy.SqlGeneration;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.DirectoryResourceAccessor;

/**
 * The SQL of chosen changesets of one changelog file, for the dialect of a database, WITHOUT running anything: the file is parsed
 * from a temporary directory and each changeset is generated against an OFFLINE database. It is the same generation the collectors and
 * the policy dry-run use ({@link SqlGeneration}), so what a person reads here is what the checks analysed.
 *
 * <p>The file is parsed alone: a changeset that reads another file ({@code sqlFile}, an include) comes back with a warning instead
 * of SQL, because guessing would be worse than saying so.
 */
@Component
public class ChangesetSqlGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(ChangesetSqlGenerator.class);

    public static final Set<String> DIALECTS = Set.of("mssql", "postgresql", "oracle", "mysql");

    /** A changeset to generate: Liquibase identifies it by id and author inside the file. */
    public record Target(String id, String author) {
    }

    /** {@code statements} is empty when {@code warning} is set. */
    public record Generated(String id, String author, List<String> statements, String warning) {
    }

    /** The Liquibase dialect of a connection engine ({@code SQLSERVER} is {@code mssql}); null when it is not one we know. */
    public static String dialectOf(String engine) {
        if (engine == null) {
            return null;
        }
        String normalised = engine.toLowerCase(Locale.ROOT);
        return switch (normalised) {
            case "sqlserver", "mssql" -> "mssql";
            case "postgresql", "oracle", "mysql" -> normalised;
            default -> null;
        };
    }

    /**
     * Generates the targets found in {@code content}. A target that is not in the file, or whose SQL cannot be generated, is still in the
     * answer with a warning, in the order they were asked for.
     */
    public Map<Target, Generated> generate(String path, String content, String dialect, List<Target> targets) {
        Map<Target, Generated> out = new LinkedHashMap<>();
        if (!DIALECTS.contains(dialect)) {
            throw new IllegalArgumentException("dialect must be one of " + DIALECTS);
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("lqm-sql");
            Path file = dir.resolve(path).normalize();
            if (!file.startsWith(dir)) {
                throw new IllegalArgumentException("path escapes the file area");
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);

            DirectoryResourceAccessor accessor = new DirectoryResourceAccessor(dir);
            Database database = DatabaseFactory.getInstance().openDatabase("offline:" + dialect, null, null, null, null, null, null, accessor);
            DatabaseChangeLog changelog = ChangeLogParserFactory.getInstance().getParser(path, accessor)
                    .parse(path, new ChangeLogParameters(database), accessor);
            List<ChangeSet> changeSets = new ArrayList<>(changelog.getChangeSets());
            for (Target target : targets) {
                ChangeSet found = changeSets.stream()
                        .filter(cs -> cs.getId().equals(target.id()) && cs.getAuthor().equals(target.author()))
                        .findFirst().orElse(null);
                if (found == null) {
                    out.put(target, new Generated(target.id(), target.author(), List.of(), "NOT_IN_FILE"));
                    continue;
                }
                List<String> statements = SqlGeneration.of(found, database);
                out.put(target, statements.isEmpty()
                        ? new Generated(target.id(), target.author(), List.of(), "NO_SQL")
                        : new Generated(target.id(), target.author(), statements, null));
            }
        } catch (IOException | RuntimeException | LinkageError | liquibase.exception.LiquibaseException e) {
            // A file that does not parse is the repository's content, not a server failure: every target says so.
            LOG.info("could not generate SQL from {}: {}", path, e.toString());
            for (Target target : targets) {
                out.put(target, new Generated(target.id(), target.author(), List.of(), "UNPARSEABLE"));
            }
        } finally {
            deleteQuietly(dir);
        }
        return out;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // a leftover temp directory is not worth failing the request for
        }
    }
}
