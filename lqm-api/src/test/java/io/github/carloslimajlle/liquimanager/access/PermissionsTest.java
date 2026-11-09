package io.github.carloslimajlle.liquimanager.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PermissionsTest {

    @ParameterizedTest
    @CsvSource({
            "deploy:*, deploy:prd, true",
            "rollback:*, rollback:approve, true",
            "rollback:prd, rollback:approve, false",
            "assignment:view, assignment:view, true",
            "assignment:view, assignment:edit, false",
            "deploy:*, promote:prd, false",
            "DEPLOY:PRD, deploy:prd, true",
    })
    void covers(String held, String required, boolean expected) {
        assertThat(Permissions.covers(held, required)).isEqualTo(expected);
    }

    @Test
    void aGrantListIsMatchedByEnvironment() {
        assertThat(Permissions.covers("deploy:dev,tst", "deploy:tst")).isTrue();
        assertThat(Permissions.covers("deploy:dev,tst", "deploy:prd")).isFalse();
        assertThat(Permissions.covers("promote:hml,prd", "promote:prd")).isTrue();
    }

    @Test
    void aDenialBeatsAnyNumberOfGrants() {
        assertThat(Permissions.allowed(List.of("assignment:view", "deploy:*"), List.of("deploy:prd"), "deploy:prd")).isFalse();
        assertThat(Permissions.allowed(List.of("assignment:view", "deploy:*"), List.of("deploy:prd"), "deploy:dev")).isTrue();
        assertThat(Permissions.allowed(List.of("deploy:*"), List.of("deploy:*"), "deploy:dev")).isFalse();
    }

    @Test
    void nothingGrantedMeansNothingAllowed() {
        assertThat(Permissions.allowed(List.of(), List.of(), "assignment:view")).isFalse();
    }

    @Test
    void effectiveDropsWhatTheBindingDeniesItself() {
        List<String> effective = Permissions.effective(
                List.of("deploy:*", "exception:approve"), List.of("drift:run"), List.of("exception:approve"));
        assertThat(effective).containsExactlyInAnyOrder("deploy:*", "drift:run");
    }

    @Test
    void sod3FindsDeployToProductionPlusApproval() {
        assertThat(Permissions.violatesSod3(List.of("deploy:*", "exception:approve"))).isTrue();
        assertThat(Permissions.violatesSod3(List.of("deploy:prd", "exception:approve"))).isTrue();
        assertThat(Permissions.violatesSod3(List.of("deploy:dev,tst", "exception:approve"))).isFalse();
        assertThat(Permissions.violatesSod3(List.of("deploy:*"))).isFalse();
        assertThat(Permissions.violatesSod3(List.of("exception:approve"))).isFalse();
    }

    @Test
    void wellFormed() {
        assertThat(Permissions.isWellFormed("deploy:dev,tst")).isTrue();
        assertThat(Permissions.isWellFormed("assignment:view")).isTrue();
        assertThat(Permissions.isWellFormed("nocolon")).isFalse();
        assertThat(Permissions.isWellFormed("Deploy:PRD")).isFalse();
        assertThat(Permissions.isWellFormed("deploy: prd")).isFalse();
    }
}
