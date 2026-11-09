package io.github.carloslimajlle.liquimanager.changesets;

import java.util.ArrayList;
import java.util.List;

/**
 * The lines that differ between two versions of a changeset's block, for a person to read. Comments are dropped first: they are not part
 * of the checksum (Liquibase hashes the parsed changes), so a line that only differs in a comment is not what made it change.
 *
 * <p>Plain line diff (longest common subsequence). Blocks are tens of lines, so the quadratic table is fine, and the output stays
 * capped.
 */
final class BlockDiff {

    /** {@code op} is {@code -} for a line only in the applied version and {@code +} for one only in the current one. */
    record Line(String op, String text) {
    }

    private static final int MAX_LINES = 60;

    private BlockDiff() {
    }

    static List<Line> diff(String applied, String current) {
        List<String> a = significant(applied);
        List<String> b = significant(current);
        int[][] common = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                common[i][j] = a.get(i).equals(b.get(j)) ? common[i + 1][j + 1] + 1 : Math.max(common[i + 1][j], common[i][j + 1]);
            }
        }
        List<Line> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() || j < b.size()) {
            if (i < a.size() && j < b.size() && a.get(i).equals(b.get(j))) {
                i++;
                j++;
            } else if (i < a.size() && (j == b.size() || common[i + 1][j] >= common[i][j + 1])) {
                // What left comes before what took its place: it reads as "was this, now that".
                out.add(new Line("-", a.get(i++)));
            } else {
                out.add(new Line("+", b.get(j++)));
            }
            if (out.size() >= MAX_LINES) {
                break;
            }
        }
        return out;
    }

    /** The lines that count: no blanks, no comments, no trailing spaces. */
    static List<String> significant(String block) {
        List<String> lines = new ArrayList<>();
        if (block == null) {
            return lines;
        }
        for (String raw : block.split("\n", -1)) {
            String line = raw.stripTrailing();
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("--") && !trimmed.startsWith("--changeset")
                    && !trimmed.startsWith("--rollback") || trimmed.startsWith("<!--") || trimmed.startsWith("comment:")
                    || trimmed.startsWith("<comment")) {
                continue;
            }
            lines.add(line.replace("\r", ""));
        }
        return lines;
    }
}
