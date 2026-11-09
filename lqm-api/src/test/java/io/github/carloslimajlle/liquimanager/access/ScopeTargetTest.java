package io.github.carloslimajlle.liquimanager.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ScopeTargetTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    void aWorkspaceBindingAlwaysApplies() {
        assertThat(ScopeTarget.nothing().covers("WORKSPACE", null)).isTrue();
    }

    @Test
    void eachScopeTypeLooksOnlyAtItsOwnKindOfId() {
        ScopeTarget target = new ScopeTarget(Set.of(A), Set.of(), Set.of(), Set.of());
        assertThat(target.covers("PROJECT", A)).isTrue();
        // the same id under another kind of scope is a different thing
        assertThat(target.covers("CONNECTION", A)).isFalse();
        assertThat(target.covers("PIPELINE", A)).isFalse();
        assertThat(target.covers("ENVIRONMENT", A)).isFalse();
        assertThat(target.covers("PROJECT", B)).isFalse();
    }

    @Test
    void aBindingWithoutAScopeIdOrWithAnUnknownTypeCoversNothing() {
        ScopeTarget target = new ScopeTarget(Set.of(A), Set.of(A), Set.of(A), Set.of(A));
        assertThat(target.covers("PROJECT", null)).isFalse();
        assertThat(target.covers("GALAXY", A)).isFalse();
    }

    @Test
    void aMissingIdIsNotAScope() {
        Set<UUID> withNull = new HashSet<>();
        withNull.add(null);
        withNull.add(A);
        ScopeTarget target = new ScopeTarget(withNull, Set.of(), Set.of(), Set.of());
        assertThat(target.projects()).containsExactly(A);
        assertThat(ScopeTarget.ofProject(null)).isEqualTo(ScopeTarget.nothing());
    }

    @Test
    void unionCollectsWhatEitherIsAbout() {
        ScopeTarget merged = ScopeTarget.ofProject(A).union(new ScopeTarget(Set.of(), Set.of(B), Set.of(), Set.of()));
        assertThat(merged.covers("PROJECT", A)).isTrue();
        assertThat(merged.covers("CONNECTION", B)).isTrue();
    }
}
