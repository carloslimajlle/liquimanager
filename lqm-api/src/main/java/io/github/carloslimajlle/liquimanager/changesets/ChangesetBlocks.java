package io.github.carloslimajlle.liquimanager.changesets;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds where one changeset lives inside a changelog file, so the screen can show just it (and its rollback) instead of a whole file.
 *
 * <p>Liquibase identifies a changeset by (id, author, file). Inside a file that is the pair, and the shape depends on the format: an
 * XML {@code <changeSet>} element, a YAML {@code - changeSet:} entry, or a formatted-SQL {@code --changeset author:id} line. Anything
 * that does not match (JSON, a file written another way) simply has no block: the caller then shows the whole file. A wrong guess
 * would show somebody the wrong SQL before an approval, so this stays strict and answers "not found" when unsure.
 *
 * <p>Lines are 1-based and inclusive.
 */
final class ChangesetBlocks {

    /** Where a block starts and ends. */
    record Range(int start, int end) {
    }

    private static final Pattern XML_OPEN = Pattern.compile("<changeSet\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern XML_CLOSE = Pattern.compile("</changeSet\\s*>", Pattern.CASE_INSENSITIVE);

    private ChangesetBlocks() {
    }

    static java.util.Optional<Range> find(String text, String path, String id, String author) {
        if (text == null || id == null || author == null) {
            return java.util.Optional.empty();
        }
        String lower = path == null ? "" : path.toLowerCase();
        if (lower.endsWith(".xml")) {
            return xml(text, id, author);
        }
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            return yaml(text, id, author);
        }
        if (lower.endsWith(".sql")) {
            return sql(text, id, author);
        }
        return java.util.Optional.empty();
    }

    // ------------------------------------------------------------------- XML

    private static java.util.Optional<Range> xml(String text, String id, String author) {
        Matcher open = XML_OPEN.matcher(text);
        while (open.find()) {
            String tag = open.group();
            if (!attribute(tag, "id", id) || !attribute(tag, "author", author)) {
                continue;
            }
            int endIndex;
            if (tag.endsWith("/>")) {
                endIndex = open.end();
            } else {
                Matcher close = XML_CLOSE.matcher(text);
                if (!close.find(open.end())) {
                    return java.util.Optional.empty();
                }
                endIndex = close.end();
            }
            return java.util.Optional.of(new Range(lineOf(text, open.start()), lineOf(text, endIndex - 1)));
        }
        return java.util.Optional.empty();
    }

    private static boolean attribute(String tag, String name, String value) {
        Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*([\"'])(.*?)\\1", Pattern.DOTALL).matcher(tag);
        return m.find() && m.group(2).equals(value);
    }

    // ------------------------------------------------------------------ YAML

    private static java.util.Optional<Range> yaml(String text, String id, String author) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = stripCr(lines[i]);
            Matcher entry = Pattern.compile("^(\\s*)-\\s+changeSet\\s*:\\s*$").matcher(line);
            if (!entry.matches()) {
                continue;
            }
            int dash = entry.group(1).length();
            int end = i;
            for (int j = i + 1; j < lines.length; j++) {
                String next = stripCr(lines[j]);
                if (next.isBlank() || next.trim().startsWith("#")) {
                    continue;
                }
                if (indent(next) <= dash) {
                    break;
                }
                end = j;
            }
            boolean sameId = false;
            boolean sameAuthor = false;
            for (int j = i + 1; j <= end; j++) {
                String field = stripCr(lines[j]).trim();
                if (field.startsWith("id:")) {
                    sameId |= unquote(field.substring(3)).equals(id);
                } else if (field.startsWith("author:")) {
                    sameAuthor |= unquote(field.substring(7)).equals(author);
                }
            }
            if (sameId && sameAuthor) {
                return java.util.Optional.of(new Range(i + 1, end + 1));
            }
        }
        return java.util.Optional.empty();
    }

    private static int indent(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    private static String unquote(String raw) {
        String v = raw.trim();
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    // ------------------------------------------------------------ formatted SQL

    private static java.util.Optional<Range> sql(String text, String id, String author) {
        String[] lines = text.split("\n", -1);
        Pattern header = Pattern.compile("^\\s*--\\s*changeset\\s+([^:\\s]+):(\\S+).*$", Pattern.CASE_INSENSITIVE);
        OptionalInt start = OptionalInt.empty();
        for (int i = 0; i < lines.length; i++) {
            Matcher m = header.matcher(stripCr(lines[i]));
            if (!m.matches()) {
                continue;
            }
            if (start.isPresent()) {
                return java.util.Optional.of(new Range(start.getAsInt() + 1, trimTrailingBlank(lines, i - 1) + 1));
            }
            if (m.group(1).equals(author) && m.group(2).equals(id)) {
                start = OptionalInt.of(i);
            }
        }
        return start.isPresent()
                ? java.util.Optional.of(new Range(start.getAsInt() + 1, trimTrailingBlank(lines, lines.length - 1) + 1))
                : java.util.Optional.empty();
    }

    private static int trimTrailingBlank(String[] lines, int last) {
        int i = last;
        while (i > 0 && stripCr(lines[i]).isBlank()) {
            i--;
        }
        return i;
    }

    // ---------------------------------------------------------------- helpers

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
