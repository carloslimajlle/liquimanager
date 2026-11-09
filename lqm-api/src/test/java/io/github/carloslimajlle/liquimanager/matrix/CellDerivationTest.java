package io.github.carloslimajlle.liquimanager.matrix;

import static io.github.carloslimajlle.liquimanager.matrix.CellDerivation.EXECUTED;
import static io.github.carloslimajlle.liquimanager.matrix.CellDerivation.FAILED;
import static io.github.carloslimajlle.liquimanager.matrix.CellDerivation.NOT_REACHED;
import static io.github.carloslimajlle.liquimanager.matrix.CellDerivation.NOT_TARGETED;
import static io.github.carloslimajlle.liquimanager.matrix.CellDerivation.OUT_OF_PROCESS;
import static io.github.carloslimajlle.liquimanager.matrix.CellDerivation.PENDING;
import static io.github.carloslimajlle.liquimanager.matrix.CellDerivation.ROLLED_BACK;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure logic: no database, no Spring. Four stages here stand for DEV, TST, HML, PRD. */
class CellDerivationTest {

    private static List<String> derive(String... stored) {
        return CellDerivation.derive(Arrays.asList(stored));
    }

    @Test
    @DisplayName("a changeset that ran everywhere is in sync")
    void runEverywhere() {
        List<String> cells = derive(EXECUTED, EXECUTED, EXECUTED, EXECUTED);

        assertThat(cells).containsExactly(EXECUTED, EXECUTED, EXECUTED, EXECUTED);
        assertThat(CellDerivation.inSync(cells)).isTrue();
        assertThat(CellDerivation.awaitingPromotion(cells)).isFalse();
    }

    @Test
    @DisplayName("only the NEXT stop is pending; everything beyond it has not been reached")
    void onlyTheNextStopIsPending() {
        List<String> cells = derive(EXECUTED, EXECUTED, null, null);

        assertThat(cells).containsExactly(EXECUTED, EXECUTED, PENDING, NOT_REACHED);
        assertThat(CellDerivation.awaitingPromotion(cells)).isTrue();
        assertThat(CellDerivation.mostAdvancedPending(cells)).isEqualTo(2);
    }

    @Test
    @DisplayName("a changeset nothing has run yet is pending at the first stage only")
    void nothingRunYet() {
        assertThat(derive(null, null, null, null)).containsExactly(PENDING, NOT_REACHED, NOT_REACHED, NOT_REACHED);
    }

    @Test
    @DisplayName("a failure blocks everything after it: nothing downstream can be pending")
    void failureBlocksWhatFollows() {
        List<String> cells = derive(EXECUTED, FAILED, null, null);

        assertThat(cells).containsExactly(EXECUTED, FAILED, NOT_REACHED, NOT_REACHED);
        assertThat(CellDerivation.awaitingPromotion(cells)).isFalse();
        assertThat(CellDerivation.inSync(cells)).isFalse();
    }

    @Test
    @DisplayName("a rollback blocks what follows, like a failure")
    void rollbackBlocksWhatFollows() {
        assertThat(derive(EXECUTED, ROLLED_BACK, null, null))
                .containsExactly(EXECUTED, ROLLED_BACK, NOT_REACHED, NOT_REACHED);
    }

    @Test
    @DisplayName("an environment the changeset was never meant for does not hold it up (context hml,prd)")
    void notTargetedIsPassedThrough() {
        List<String> cells = derive(NOT_TARGETED, NOT_TARGETED, EXECUTED, null);

        assertThat(cells).containsExactly(NOT_TARGETED, NOT_TARGETED, EXECUTED, PENDING);
    }

    @Test
    @DisplayName("not-targeted stages in the middle neither break nor create readiness")
    void notTargetedInTheMiddle() {
        assertThat(derive(EXECUTED, NOT_TARGETED, null, null))
                .containsExactly(EXECUTED, NOT_TARGETED, PENDING, NOT_REACHED);
        assertThat(derive(null, NOT_TARGETED, null, null))
                .as("the first stage is pending, so the one after the gap has not been reached")
                .containsExactly(PENDING, NOT_TARGETED, NOT_REACHED, NOT_REACHED);
    }

    @Test
    @DisplayName("a change applied outside the pipeline counts as applied, but is not 'in sync'")
    void outOfProcess() {
        List<String> cells = derive(EXECUTED, OUT_OF_PROCESS, null, null);

        assertThat(cells).containsExactly(EXECUTED, OUT_OF_PROCESS, PENDING, NOT_REACHED);
        assertThat(CellDerivation.inSync(cells)).isFalse();
    }

    @Test
    @DisplayName("a stored PENDING (seen but not run) is treated as nothing known")
    void storedPendingIsNothingKnown() {
        assertThat(derive(EXECUTED, PENDING, PENDING, null))
                .containsExactly(EXECUTED, PENDING, NOT_REACHED, NOT_REACHED);
    }

    @Test
    @DisplayName("a changeset filtered out of every environment is in sync, vacuously")
    void notTargetedEverywhere() {
        assertThat(CellDerivation.inSync(derive(NOT_TARGETED, NOT_TARGETED))).isTrue();
    }

    @Test
    @DisplayName("the furthest pending stage is the one reported, and none is -1")
    void mostAdvancedPending() {
        assertThat(CellDerivation.mostAdvancedPending(derive(EXECUTED, EXECUTED, EXECUTED, EXECUTED))).isEqualTo(-1);
        assertThat(CellDerivation.mostAdvancedPending(derive(null, null))).isEqualTo(0);
    }

    @Test
    @DisplayName("an unknown stored status is shown as-is and blocks what follows")
    void unknownStatus() {
        assertThat(derive("WEIRD", null)).containsExactly("WEIRD", NOT_REACHED);
    }
}
