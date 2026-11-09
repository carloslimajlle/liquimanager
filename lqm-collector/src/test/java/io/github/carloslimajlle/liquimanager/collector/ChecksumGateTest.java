package io.github.carloslimajlle.liquimanager.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.client.ChecksumChange;
import io.github.carloslimajlle.liquimanager.client.IngestPayload;
import io.github.carloslimajlle.liquimanager.policy.Finding;
import io.github.carloslimajlle.liquimanager.policy.Severity;

/** ChecksumChanged as a person reads it: what happened, the lines that changed, what to do; and how it shows in the merge request. */
class ChecksumGateTest {

    private static ChecksumChange change(boolean known, boolean equivalent, List<ChecksumChange.Line> diff) {
        return new ChecksumChange("003-create-produto", "carlos", "db/changelog/changes/003-create-produto.yaml", "9:1822", "9:ec53",
                "4d64122c", "poc-dev", "DEV", known, equivalent, diff, null, null, null, null);
    }

    @Test
    @DisplayName("an id applied under another author or file is told as a new changeset to Liquibase, and blocks at the severity of the check")
    void identityChanged() {
        ChecksumChange renamed = new ChecksumChange("002-create-pedido", "sistema", "db/changelog/changes/002-create-pedido.yaml", "9:aaa",
                "9:aaa", "4d64122c", "poc-dev", "DEV", false, null, null, true, "carlos", "db/changelog/changes/002-create-pedido.yaml", null);

        Finding finding = ChecksumGate.finding(renamed, Severity.BLOCKER);

        assertThat(finding.severity()).isEqualTo(Severity.BLOCKER);
        assertThat(finding.message()).contains("author carlos", "author sistema", "changeset novo", "roda de novo");
    }

    @Test
    @DisplayName("a changeset the environment ran and the changelog dropped is only a warning, even at a blocking severity")
    void removed() {
        ChecksumChange gone = new ChecksumChange("002-create-pedido", "carlos", "db/changelog/changes/002-create-pedido.yaml", "9:aaa",
                "9:aaa", "4d64122c", "poc-dev", "DEV", false, null, null, null, null, null, true);

        Finding finding = ChecksumGate.finding(gone, Severity.BLOCKER);

        assertThat(finding.severity()).isEqualTo(Severity.MAJOR);
        assertThat(finding.message()).contains("não está mais no changelog", "squash").doesNotContain("recusar o deploy");
    }

    @Test
    @DisplayName("a structural change says what happened, shows the lines and blocks at the severity of the check")
    void structural() {
        Finding finding = ChecksumGate.finding(change(true, false, List.of(
                new ChecksumChange.Line("-", "name: estoque"), new ChecksumChange.Line("+", "name: quantidade_estoque"))), Severity.BLOCKER);

        assertThat(finding.checkCode()).isEqualTo("ChecksumChanged");
        assertThat(finding.severity()).isEqualTo(Severity.BLOCKER);
        assertThat(finding.changesetKey()).isEqualTo("003-create-produto::carlos::db/changelog/changes/003-create-produto.yaml");
        assertThat(finding.message()).contains("já foi aplicado em DEV (poc-dev)", "9:1822", "9:ec53", "recusar o deploy")
                .contains("\n- name: estoque").contains("\n+ name: quantidade_estoque").contains("validCheckSum");
    }

    @Test
    @DisplayName("no line that counts changed: it looks equivalent, only warns, and points at validCheckSum")
    void equivalent() {
        Finding finding = ChecksumGate.finding(change(true, true, List.of()), Severity.BLOCKER);

        assertThat(finding.severity()).isEqualTo(Severity.MAJOR);
        assertThat(finding.message()).contains("parece equivalente").contains("validCheckSum").doesNotContain("\n- ").doesNotContain("\n+ ");
    }

    @Test
    @DisplayName("without the repository it still says it changed, without lines; a long diff is cut")
    void unknownAndLong() {
        Finding blind = ChecksumGate.finding(change(false, false, List.of()), Severity.BLOCKER);
        assertThat(blind.severity()).isEqualTo(Severity.BLOCKER);
        assertThat(blind.message()).contains("foi alterado depois").doesNotContain("\n- ");

        List<ChecksumChange.Line> many = java.util.stream.IntStream.range(0, 20).mapToObj(i -> new ChecksumChange.Line("+", "line " + i)).toList();
        assertThat(ChecksumGate.finding(change(true, false, many), Severity.BLOCKER).message()).contains("… e mais 8 linhas");
    }

    @Test
    @DisplayName("in the merge request comment the lines stay lines, and the diff reads as code")
    void inTheComment() {
        Finding finding = ChecksumGate.finding(change(true, false, List.of(
                new ChecksumChange.Line("-", "name: estoque"), new ChecksumChange.Line("+", "name: <novo>"))), Severity.BLOCKER);
        IngestPayload.Policy policy = new IngestPayload.Policy("BLOCKED", List.of(new IngestPayload.Finding(
                finding.checkCode(), finding.severity(), "BLOCKED", finding.changesetKey(), finding.message())));

        String body = MergeRequestComment.body("DEV", policy);

        assertThat(body).contains("<br><code>- name: estoque</code>").contains("<code>+ name: &lt;novo&gt;</code>").contains("`003-create-produto`");
        assertThat(body.lines().filter(line -> line.startsWith("| ")).count()).as("one table row, not one per line").isEqualTo(2);
    }
}
