package io.github.carloslimajlle.liquimanager.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps the implementation and the specification honest with each other.
 *
 * <p>docs/08 is the catalogue; the database seed is generated from it, and the engine
 * implements it. Without this test the three drift silently: a check implemented under a
 * misspelt code would never be found by a package, and a phase-1 check forgotten here
 * would sit enabled in the seed and do nothing — quietly, in every pipeline.
 */
class CatalogueConsistencyTest {

    /** | `Code` [/ `Other`] | description | severity | P | phase | */
    private static final Pattern ROW = Pattern.compile(
            "^\\|\\s*`([^`]+)`(?:\\s*/\\s*`([^`]+)`)?\\s*\\|(.*?)\\|\\s*(\\d)\\s*\\|\\s*(P?)\\s*\\|\\s*(\\d)\\s*\\|\\s*$");

    private static final Path CATALOGUE = Path.of("..", "docs", "08-motor-de-politicas.md");

    private record Entry(String code, int severity, int phase) {
    }

    private static Set<Entry> catalogue() throws IOException {
        Set<Entry> entries = new TreeSet<>(java.util.Comparator.comparing(Entry::code));
        for (String line : Files.readAllLines(CATALOGUE, StandardCharsets.UTF_8)) {
            Matcher row = ROW.matcher(line);
            if (row.matches()) {
                int severity = Integer.parseInt(row.group(4));
                int phase = Integer.parseInt(row.group(6));
                entries.add(new Entry(row.group(1), severity, phase));
                if (row.group(2) != null) {
                    entries.add(new Entry(row.group(2), severity, phase));
                }
            }
        }
        return entries;
    }

    @Test
    @DisplayName("the catalogue was found and parsed: 51 configurable checks")
    void catalogueIsParsed() throws IOException {
        assertThat(catalogue()).as("is docs/08 reachable from the module directory?").hasSize(51);
    }

    @Test
    @DisplayName("every check the engine implements exists in the catalogue")
    void everyImplementationIsInTheCatalogue() throws IOException {
        Set<String> catalogued = catalogue().stream().map(Entry::code).collect(Collectors.toSet());
        Set<String> implemented = BuiltinChecks.all().stream().map(PolicyCheck::code).collect(Collectors.toSet());

        assertThat(implemented).as("a code that is not in docs/08 can never be found by a package")
                .isSubsetOf(catalogued);
    }

    @Test
    @DisplayName("every phase-1 check in the catalogue is implemented: the seed enables them all")
    void everyPhaseOneCheckIsImplemented() throws IOException {
        Set<String> phaseOne = catalogue().stream().filter(e -> e.phase() == 1).map(Entry::code)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> implemented = BuiltinChecks.all().stream().map(PolicyCheck::code).collect(Collectors.toSet());

        assertThat(implemented).as("phase-1 checks the seed enables but the engine does not implement")
                .containsAll(phaseOne);
    }

    @Test
    @DisplayName("an implementation's default severity matches the catalogue's")
    void severitiesMatch() throws IOException {
        for (Entry entry : catalogue()) {
            BuiltinChecks.all().stream().filter(c -> c.code().equals(entry.code())).findFirst()
                    .ifPresent(check -> assertThat(check.defaultSeverity())
                            .as("default severity of %s: code says %d, docs/08 says %d", entry.code(),
                                    check.defaultSeverity(), entry.severity())
                            .isEqualTo(entry.severity()));
        }
    }
}
