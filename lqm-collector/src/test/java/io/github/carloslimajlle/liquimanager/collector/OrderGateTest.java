package io.github.carloslimajlle.liquimanager.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.client.IngestPayload;
import io.github.carloslimajlle.liquimanager.client.PromotionGate;

/** The order of the environments: what runs in HML has to have run in DEV, in the same version. */
class OrderGateTest {

    private static final String FILE = "db/changelog/changes/002-create-pedido.yaml";

    private static IngestPayload.Changeset changeset(String id, String outcome, String checksum) {
        return new IngestPayload.Changeset(id, "carlos", FILE, checksum, null, null, null, outcome, null, null, null, null, null);
    }

    private static PromotionGate dev(PromotionGate.Key... keys) {
        return new PromotionGate(true, "DEV", List.of(keys));
    }

    @Test
    @DisplayName("what DEV ran in the same version passes; what DEV does not have is missing")
    void sameVersionAndMissing() {
        PromotionGate gate = dev(new PromotionGate.Key("002", "carlos", FILE, "9:aaa"));

        OrderGate.Verdict verdict = OrderGate.evaluate(gate, List.of(changeset("002", "NOT_EXECUTED", "9:aaa"), changeset("003", "NOT_EXECUTED", "9:ccc")),
                (cs, applied) -> false);

        assertThat(verdict.differing()).isEmpty();
        assertThat(verdict.missing()).containsExactly("003");
        assertThat(verdict.blocked()).isTrue();
        assertThat(verdict.why()).contains("not in DEV yet: 003");
    }

    @Test
    @DisplayName("a changeset DEV ran in another version is held back, and says which versions")
    void anotherVersion() {
        PromotionGate gate = dev(new PromotionGate.Key("002", "carlos", FILE, "9:aaa"));

        OrderGate.Verdict verdict = OrderGate.evaluate(gate, List.of(changeset("002", "NOT_EXECUTED", "9:bbb")), (cs, applied) -> false);

        assertThat(verdict.missing()).isEmpty();
        assertThat(verdict.differing()).hasSize(1);
        assertThat(verdict.differing().get(0)).contains("002", "DEV: 9:aaa", "here: 9:bbb");
        assertThat(verdict.why()).contains("not the version DEV ran").contains("validCheckSum");
    }

    @Test
    @DisplayName("validCheckSum for what DEV ran, an unknown checksum, and a changeset already run here are not differences")
    void notDifferences() {
        PromotionGate gate = dev(new PromotionGate.Key("002", "carlos", FILE, "9:aaa"), new PromotionGate.Key("004", "carlos", FILE, null));

        OrderGate.Verdict declared = OrderGate.evaluate(gate, List.of(changeset("002", "NOT_EXECUTED", "9:bbb")), (cs, applied) -> "9:aaa".equals(applied));
        OrderGate.Verdict unknown = OrderGate.evaluate(gate, List.of(changeset("004", "NOT_EXECUTED", "9:zzz")), (cs, applied) -> false);
        OrderGate.Verdict alreadyHere = OrderGate.evaluate(gate, List.of(changeset("002", "PREVIOUSLY_RUN", "9:bbb")), (cs, applied) -> false);

        assertThat(declared.blocked()).as("validCheckSum says they are the same").isFalse();
        assertThat(unknown.blocked()).as("no checksum known for DEV: when in doubt, not accused").isFalse();
        assertThat(alreadyHere.blocked()).as("only what is about to run is judged").isFalse();
    }
}
