package io.github.carloslimajlle.liquimanager.audit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * The audit trail as a PDF a person can file (RF-ADM-04): the events in order, whether the hash chain still verifies, and the
 * hash of the last event, which seals everything before it (each event's hash covers the previous one).
 *
 * <p>It is pure: it is given the rows and returns the bytes, so it can be tested without a database. The seal the spec asks for
 * ("hash do arquivo registrado no próprio log") is not inside the PDF, because a file cannot carry its own hash: the controller
 * records the SHA-256 of these bytes in an {@code audit.export} event and returns it in a header. Nothing here is a digital
 * signature with a certificate, and the first page says so.
 *
 * <p>The text is set in Helvetica, whose encoding (WinAnsi) covers Portuguese; a character outside it, which a name in an event
 * could carry, becomes a question mark instead of failing the whole export.
 */
final class AuditPdf {

    /** What the first page says about the export itself. */
    record Header(String workspace, String filters, String generatedBy, Instant generatedAt, boolean chainIntact, long chainChecked,
                  Long brokenAt, int total, boolean truncated, String lastHash) {
    }

    /** One event as a row. */
    record Row(long seq, Instant at, String actor, String action, String target, String hash) {
    }

    private static final PDRectangle PAGE = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
    private static final float MARGIN = 36;
    private static final float ROW = 12.5f;
    private static final float FONT = 8;
    private static final ZoneId DISPLAY = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(DISPLAY);

    private static final String[] TITLES = {"Seq", "Quando (São Paulo)", "Autor", "Ação", "Alvo", "Hash"};
    private static final float[] WIDTHS = {42, 100, 120, 170, 250, 100};

    private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private AuditPdf() {
    }

    static byte[] render(Header header, List<Row> rows) throws IOException {
        return new AuditPdf().build(header, rows);
    }

    private byte[] build(Header header, List<Row> rows) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            List<PDPage> pages = new ArrayList<>();
            PDPageContentStream stream = newPage(document, pages);
            float y = tableHeader(stream, firstPageHeader(stream, header));
            for (Row row : rows) {
                if (y < MARGIN + 30) {
                    stream.close();
                    stream = newPage(document, pages);
                    y = tableHeader(stream, PAGE.getHeight() - MARGIN);
                }
                y = row(stream, y, row);
            }
            if (rows.isEmpty()) {
                text(stream, regular, FONT + 1, MARGIN, y - 2, "Nenhum evento corresponde ao filtro.");
            }
            stream.close();

            footers(document, pages);
            PDDocumentInformation info = document.getDocumentInformation();
            info.setTitle("Trilha de auditoria - LiquiManager");
            info.setCreator("LiquiManager");
            info.setSubject("Workspace " + safe(header.workspace()));
            document.save(out);
            return out.toByteArray();
        }
    }

    private static PDPageContentStream newPage(PDDocument document, List<PDPage> pages) throws IOException {
        PDPage page = new PDPage(PAGE);
        document.addPage(page);
        pages.add(page);
        return new PDPageContentStream(document, page);
    }

    // ------------------------------------------------------------------ pieces

    /** The title, who and when, the verdict on the chain and the seal. Returns the y where the table may start. */
    private float firstPageHeader(PDPageContentStream stream, Header h) throws IOException {
        float y = PAGE.getHeight() - MARGIN;
        text(stream, bold, 16, MARGIN, y - 12, "Trilha de auditoria");
        y -= 34;
        text(stream, regular, 9, MARGIN, y, "Workspace: " + h.workspace() + "    Filtros: " + (h.filters().isBlank() ? "nenhum" : h.filters()));
        y -= 13;
        text(stream, regular, 9, MARGIN, y, "Gerado em " + WHEN.format(h.generatedAt()) + " por " + h.generatedBy()
                + "    Eventos neste arquivo: " + h.total() + (h.truncated() ? " (o limite do export foi atingido: há mais eventos)" : ""));
        y -= 13;
        if (h.chainIntact()) {
            text(stream, bold, 9, MARGIN, y, "Cadeia de hashes: ÍNTEGRA. " + h.chainChecked() + " eventos verificados no momento da geração.");
        } else {
            text(stream, bold, 9, MARGIN, y, "ATENÇÃO: CADEIA DE HASHES ADULTERADA a partir do evento " + h.brokenAt() + ".");
        }
        y -= 13;
        text(stream, regular, 8, MARGIN, y, "Hash do último evento listado (sela os anteriores, cada hash cobre o anterior): "
                + (h.lastHash() == null ? "-" : h.lastHash()));
        y -= 12;
        text(stream, regular, 8, MARGIN, y, "O SHA-256 deste arquivo fica registrado no próprio log (evento audit.export). Não é uma assinatura digital com "
                + "certificado: confira o arquivo com o hash do evento.");
        return y - 16;
    }

    private float tableHeader(PDPageContentStream stream, float y) throws IOException {
        float x = MARGIN;
        for (int i = 0; i < TITLES.length; i++) {
            text(stream, bold, FONT, x, y, TITLES[i]);
            x += WIDTHS[i];
        }
        stream.setLineWidth(0.5f);
        stream.moveTo(MARGIN, y - 3);
        stream.lineTo(PAGE.getWidth() - MARGIN, y - 3);
        stream.stroke();
        return y - ROW - 2;
    }

    private float row(PDPageContentStream stream, float y, Row row) throws IOException {
        String[] cells = {
            String.valueOf(row.seq()), WHEN.format(row.at()), row.actor(), row.action(), row.target(),
            row.hash() == null ? "" : row.hash().substring(0, Math.min(16, row.hash().length()))};
        float x = MARGIN;
        for (int i = 0; i < cells.length; i++) {
            text(stream, regular, FONT, x, y, fit(cells[i], WIDTHS[i] - 6));
            x += WIDTHS[i];
        }
        return y - ROW;
    }

    /** "Pagina x de y" and the document name on every page, drawn at the end, when the count is known. */
    private void footers(PDDocument document, List<PDPage> pages) throws IOException {
        for (int i = 0; i < pages.size(); i++) {
            try (PDPageContentStream stream = new PDPageContentStream(document, pages.get(i), PDPageContentStream.AppendMode.APPEND, true)) {
                text(stream, regular, 8, MARGIN, 20, "LiquiManager · trilha de auditoria");
                text(stream, regular, 8, PAGE.getWidth() - MARGIN - 80, 20, "Página " + (i + 1) + " de " + pages.size());
            }
        }
    }

    // ------------------------------------------------------------------- text

    private void text(PDPageContentStream stream, PDType1Font font, float size, float x, float y, String value) throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.newLineAtOffset(x, y);
        stream.showText(safe(value));
        stream.endText();
    }

    /** The text cut, with "..." at the end, to fit {@code width} points. */
    private String fit(String value, float width) throws IOException {
        String text = safe(value);
        if (regular.getStringWidth(text) / 1000 * FONT <= width) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && regular.getStringWidth(cut + "...") / 1000 * FONT > width) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }

    /** What Helvetica can set: anything else (and control characters) becomes a question mark or a space. */
    private String safe(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            String one = new String(Character.toChars(codePoint));
            if (Character.isISOControl(codePoint)) {
                out.append(' ');
                return;
            }
            try {
                regular.encode(one);
                out.append(one);
            } catch (IOException | IllegalArgumentException unsupported) {
                out.append('?');
            }
        });
        return out.toString();
    }
}
