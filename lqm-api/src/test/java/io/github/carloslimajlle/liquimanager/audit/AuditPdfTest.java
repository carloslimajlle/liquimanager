package io.github.carloslimajlle.liquimanager.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The audit PDF as a reader sees it: what is on the pages, how many, and that odd text does not break it. */
class AuditPdfTest {

    private static final String HASH = "a".repeat(64);

    private static AuditPdf.Header header(boolean intact, Long brokenAt, int total, boolean truncated, String last) {
        return new AuditPdf.Header("Workspace de Teste", "autor dora", "admin.lqm", Instant.parse("2026-11-02T15:00:00Z"),
                intact, 120, brokenAt, total, truncated, last);
    }

    private static AuditPdf.Row row(long seq, String actor, String action, String target) {
        return new AuditPdf.Row(seq, Instant.parse("2026-11-02T12:00:00Z").plusSeconds(seq), actor, action, target,
                String.format("%02x", seq % 256).repeat(32));
    }

    private static String textOf(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static int pagesOf(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        }
    }

    @Test
    @DisplayName("the first page says who, when, for what, whether the chain verifies, and the hash that seals it")
    void firstPage() throws IOException {
        byte[] pdf = AuditPdf.render(header(true, null, 2, false, HASH),
                List.of(row(1, "admin.lqm", "access.grant", "role-binding:1"), row(2, "dba.demo", "pipeline.promote.approve", "promotion:2")));

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        String text = textOf(pdf);
        assertThat(text).contains("Trilha de auditoria", "Workspace de Teste", "autor dora", "admin.lqm", "02/11/2026 09:00:01")
                .contains("ÍNTEGRA", "120 eventos verificados")
                .contains(HASH)
                .contains("access.grant", "pipeline.promote.approve", "promotion:2")
                .contains("Página 1 de 1")
                .contains("Não é uma assinatura digital");
        assertThat(pagesOf(pdf)).isEqualTo(1);
    }

    @Test
    @DisplayName("a chain that does not verify is said loudly, with the event where it broke")
    void brokenChain() throws IOException {
        String text = textOf(AuditPdf.render(header(false, 37L, 1, false, HASH), List.of(row(1, "a", "x.y", "z"))));
        assertThat(text).contains("ADULTERADA").contains("37").doesNotContain("ÍNTEGRA");
    }

    @Test
    @DisplayName("hundreds of events run over several pages, every one is there, the footer counts them and no page is left empty")
    void manyEvents() throws IOException {
        List<AuditPdf.Row> rows = new ArrayList<>();
        IntStream.rangeClosed(1, 300).forEach(i -> rows.add(row(i, "ator" + i, "acao." + i, "alvo:" + i)));
        byte[] pdf = AuditPdf.render(header(true, null, 300, false, HASH), rows);

        int pages = pagesOf(pdf);
        assertThat(pages).isGreaterThan(5);
        String text = textOf(pdf);
        assertThat(text).contains("Página 1 de " + pages).contains("Página " + pages + " de " + pages)
                .contains("ator1 ").contains("ator150 ").contains("ator300 ");
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(pages);
            stripper.setEndPage(pages);
            assertThat(stripper.getText(document)).as("the last page holds events, not just a header").contains("ator");
        }
    }

    @Test
    @DisplayName("no events is a page that says so")
    void empty() throws IOException {
        String text = textOf(AuditPdf.render(header(true, null, 0, false, null), List.of()));
        assertThat(text).contains("Nenhum evento corresponde ao filtro");
    }

    @Test
    @DisplayName("the export limit being reached is said")
    void truncated() throws IOException {
        assertThat(textOf(AuditPdf.render(header(true, null, 50_000, true, HASH), List.of(row(1, "a", "b", "c")))))
                .contains("limite do export");
    }

    @Test
    @DisplayName("characters Helvetica cannot set, and control characters, do not break the export")
    void oddText() throws IOException {
        byte[] pdf = AuditPdf.render(header(true, null, 1, false, HASH),
                List.of(row(1, "ana → bia 😀", "x.y\nz\tw", "alvo:ação ünïcode")));

        String text = textOf(pdf);
        assertThat(text).contains("ana ? bia ?").contains("x.y z w").contains("ação ünïcode");
    }

    @Test
    @DisplayName("a cell wider than its column is cut with an ellipsis instead of running into the next one")
    void longCells() throws IOException {
        String long_ = "a-very-long-target-name-".repeat(20);
        String text = textOf(AuditPdf.render(header(true, null, 1, false, HASH), List.of(row(1, "a", "b", long_))));
        assertThat(text).contains("...").doesNotContain(long_);
    }
}
