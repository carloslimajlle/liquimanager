package io.github.carloslimajlle.liquimanager.cli.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FlowFileTest {

    private static final String EXAMPLE = """
            version: 1
            variables:
              release: ${CI_COMMIT_TAG:-snapshot}
            stages:
              - name: checks
                run: checks run
                onFailure: stop
              - name: preview
                run: update-sql
                when: env in [HML, PRD]
                artifact: target/lqm/update.sql
              - name: update
                run: update
              - name: tag
                run: tag ${release}
            """;

    @Test
    @DisplayName("reads the example of the spec: variables, stages, conditions, artifact and the default of onFailure")
    void readsTheSpecExample() {
        FlowFile flow = FlowFile.parse(EXAMPLE, Map.of());

        assertThat(flow.stages()).extracting(FlowFile.Stage::name).containsExactly("checks", "preview", "update", "tag");
        assertThat(flow.stages().get(0).onFailure()).isEqualTo(FlowFile.OnFailure.STOP);
        assertThat(flow.stages().get(2).onFailure()).as("stop is the default").isEqualTo(FlowFile.OnFailure.STOP);
        assertThat(flow.stages().get(1).artifact()).isEqualTo("target/lqm/update.sql");
        assertThat(flow.stages().get(3).run()).as("the variable falls back to its default").isEqualTo("tag snapshot");
    }

    @Test
    @DisplayName("a variable comes from the environment when it is set there")
    void environmentWins() {
        assertThat(FlowFile.parse(EXAMPLE, Map.of("CI_COMMIT_TAG", "release-2.0.0")).stages().get(3).run())
                .isEqualTo("tag release-2.0.0");
    }

    @Test
    @DisplayName("a variable can use the ones declared above it; an undefined one without a default is an error")
    void variables() {
        FlowFile flow = FlowFile.parse("""
                version: 1
                variables:
                  base: v1
                  full: ${base}-final
                stages:
                  - { name: t, run: "tag ${full}" }
                """, Map.of());
        assertThat(flow.stages().get(0).run()).isEqualTo("tag v1-final");

        assertThatThrownBy(() -> FlowFile.parse("version: 1\nstages:\n  - { name: t, run: \"tag ${NOPE}\" }\n", Map.of()))
                .isInstanceOf(FlowFile.Invalid.class).hasMessageContaining("NOPE");
    }

    @Test
    @DisplayName("onFailure: continue is read")
    void continueOnFailure() {
        FlowFile flow = FlowFile.parse("version: 1\nstages:\n  - { name: a, run: status, onFailure: continue }\n", Map.of());

        assertThat(flow.stages().get(0).onFailure()).isEqualTo(FlowFile.OnFailure.CONTINUE);
    }

    @Test
    @DisplayName("conditions on the environment")
    void conditions() {
        FlowFile.Stage in = stage("env in [HML, PRD]");
        assertThat(FlowFile.applies(in, "PRD")).isTrue();
        assertThat(FlowFile.applies(in, "hml")).as("case does not matter").isTrue();
        assertThat(FlowFile.applies(in, "DEV")).isFalse();

        assertThat(FlowFile.applies(stage("env not in [PRD]"), "DEV")).isTrue();
        assertThat(FlowFile.applies(stage("env not in [PRD]"), "PRD")).isFalse();
        assertThat(FlowFile.applies(stage("env == PRD"), "PRD")).isTrue();
        assertThat(FlowFile.applies(stage("env != PRD"), "PRD")).isFalse();
        assertThat(FlowFile.applies(stage(null), "ANYTHING")).as("no condition: always").isTrue();
    }

    private static FlowFile.Stage stage(String when) {
        return new FlowFile.Stage("s", "status", when, FlowFile.OnFailure.STOP, null);
    }

    @Test
    @DisplayName("everything wrong with the file is found before anything runs, and says where")
    void invalidFiles() {
        assertThatThrownBy(() -> FlowFile.parse("not: [valid", Map.of())).isInstanceOf(FlowFile.Invalid.class).hasMessageContaining("YAML");
        assertThatThrownBy(() -> FlowFile.parse("- a\n- b\n", Map.of())).hasMessageContaining("mapping");
        assertThatThrownBy(() -> FlowFile.parse("stages:\n  - { name: a, run: status }\n", Map.of())).hasMessageContaining("version");
        assertThatThrownBy(() -> FlowFile.parse("version: 2\nstages:\n  - { name: a, run: status }\n", Map.of())).hasMessageContaining("version");
        assertThatThrownBy(() -> FlowFile.parse("version: 1\n", Map.of())).hasMessageContaining("at least one stage");
        assertThatThrownBy(() -> FlowFile.parse("version: 1\nstages:\n  - { run: status }\n", Map.of())).hasMessageContaining("no name");
        assertThatThrownBy(() -> FlowFile.parse("version: 1\nstages:\n  - { name: a }\n", Map.of())).hasMessageContaining("stage a has no run");
        assertThatThrownBy(() -> FlowFile.parse("version: 1\nstages:\n  - { name: a, run: x }\n  - { name: a, run: y }\n", Map.of()))
                .hasMessageContaining("two stages");
        assertThatThrownBy(() -> FlowFile.parse("version: 1\nstages:\n  - { name: a, run: x, when: 'env is PRD' }\n", Map.of()))
                .hasMessageContaining("stage a").hasMessageContaining("env is PRD");
        assertThatThrownBy(() -> FlowFile.parse("version: 1\nstages:\n  - { name: a, run: x, onFailure: explode }\n", Map.of()))
                .hasMessageContaining("onFailure");
    }

    @Test
    @DisplayName("YAML cannot instantiate arbitrary classes")
    void safeLoader() {
        assertThatThrownBy(() -> FlowFile.parse("version: 1\nstages: !!java.lang.ProcessBuilder [[ls]]\n", Map.of()))
                .isInstanceOf(FlowFile.Invalid.class);
    }
}
