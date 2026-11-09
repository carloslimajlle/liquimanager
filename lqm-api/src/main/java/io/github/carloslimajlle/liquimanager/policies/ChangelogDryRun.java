package io.github.carloslimajlle.liquimanager.policies;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.policy.CheckContext;
import io.github.carloslimajlle.liquimanager.policy.EffectiveCheck;
import io.github.carloslimajlle.liquimanager.policy.EffectivePackage;
import io.github.carloslimajlle.liquimanager.policy.EvaluationResult;
import io.github.carloslimajlle.liquimanager.policy.PolicyEngine;
import io.github.carloslimajlle.liquimanager.policy.SqlGeneration;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.DirectoryResourceAccessor;

/**
 * Runs ONE check over a changelog somebody pasted, the dry-run of the Policy Library
 * (docs/telas/07). It uses the same engine and the same SQL generation as the collectors, so
 * the answer is what {@code lqm checks run} would say for the same file — that equality is
 * the whole point of the feature.
 *
 * <p>The changelog is parsed from a temporary file and generated for an OFFLINE database of the
 * chosen dialect: nothing here touches a real database.
 */
@Component
public class ChangelogDryRun {

    private static final Set<String> DIALECTS = Set.of("mssql", "postgresql", "oracle", "mysql");

    public record Result(String gate, EvaluationResult evaluation, int changesets) {
    }

    private static String extension(String format) {
        return switch (format == null ? "" : format.toUpperCase(Locale.ROOT)) {
            case "XML" -> "xml";
            case "YAML", "YML" -> "yaml";
            case "JSON" -> "json";
            case "SQL" -> "sql";
            default -> throw LqmException.invalid("format must be XML, YAML, JSON or SQL");
        };
    }

    public Result run(String content, String format, String dialect, String environment, boolean production,
                      EffectiveCheck check) {
        String chosen = dialect == null || dialect.isBlank() ? "mssql" : dialect.toLowerCase(Locale.ROOT);
        if (!DIALECTS.contains(chosen)) {
            throw LqmException.invalid("dialect must be one of " + String.join(", ", DIALECTS));
        }
        if (content == null || content.isBlank()) {
            throw LqmException.invalid("changelogContent is required");
        }
        String ext = extension(format);

        Path dir = null;
        try {
            dir = Files.createTempDirectory("lqm-dryrun");
            String name = "changelog." + ext;
            Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);

            DirectoryResourceAccessor accessor = new DirectoryResourceAccessor(dir);
            Database database = DatabaseFactory.getInstance().openDatabase(
                    "offline:" + chosen, null, null, null, null, null, null, accessor);
            DatabaseChangeLog changelog = ChangeLogParserFactory.getInstance().getParser(name, accessor)
                    .parse(name, new ChangeLogParameters(database), accessor);

            List<ChangeSet> changeSets = new ArrayList<>(changelog.getChangeSets());
            CheckContext ctx = new CheckContext(changelog, changeSets, database,
                    cs -> SqlGeneration.of(cs, database), Optional.empty(),
                    environment == null || environment.isBlank() ? "DEV" : environment.toUpperCase(Locale.ROOT),
                    production);
            EvaluationResult result = PolicyEngine.withBuiltins().evaluate(ctx,
                    new EffectivePackage("dry-run", check.severity(), List.of(check)), List.of(), Instant.now());
            return new Result(result.gate().name(), result, changeSets.size());
        } catch (LqmException e) {
            throw e;
        } catch (Exception e) {
            // A changelog that does not parse is the user's input being wrong, not the server failing.
            throw LqmException.invalid("could not read the changelog: " + rootMessage(e));
        } finally {
            deleteQuietly(dir);
        }
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
            // a leftover temp directory is not worth failing the request for
        }
    }
}
