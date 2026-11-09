package io.github.carloslimajlle.liquimanager.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Invariants of the check catalogue.
 *
 * <p>They live in their own class, rather than alongside PolicyEngineTest, because
 * top-level tests sitting next to {@code @Nested} classes never run in the build:
 * surefire reports "Tests run: 0" for the outer class. Separated, they always
 * run — and these are exactly the ones guarding against a duplicate registration.
 */
class BuiltinChecksTest {

    @Test
    @DisplayName("no two checks share the same code")
    void codesAreUnique() {
        List<String> codes = BuiltinChecks.all().stream().map(PolicyCheck::code).toList();

        assertThat(codes).doesNotHaveDuplicates();
        assertThat(PolicyEngine.withBuiltins().implementedCodes()).hasSameSizeAs(codes);
    }

    @Test
    @DisplayName("every default severity sits in the 0..4 range")
    void severitiesAreValid() {
        BuiltinChecks.all().forEach(check -> Severity.validate(check.defaultSeverity()));
    }

    @Test
    @DisplayName("every check declares a code and a scope")
    void metadataIsPresent() {
        assertThat(BuiltinChecks.all()).isNotEmpty().allSatisfy(check -> {
            assertThat(check.code()).isNotBlank();
            assertThat(check.scope()).isNotNull();
        });
    }

    @Test
    @DisplayName("each check code matches its class name")
    void codeMatchesClassName() {
        // Guards against the silent mistake of copying a check and forgetting to
        // change code(): two checks with swapped codes would slip into the seed.
        assertThat(BuiltinChecks.all()).allSatisfy(check ->
                assertThat(check.code()).isEqualTo(check.getClass().getSimpleName()));
    }
}
