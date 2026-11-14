package io.github.carloslimajlle.liquimanager.policy.cdc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the SQL of a changeset does to tables that SQL Server Change Data Capture tracks.
 *
 * <p>A capture instance takes the columns the table has on the day it is created. After a DDL it no longer matches the table: a new
 * column is never captured, and a retyped, dropped or renamed one leaves whoever reads the table's schema (Debezium, in the usual
 * setup) reading a shape the instance does not have any more. The way out is to create the instance again, which is what
 * {@link CdcRecapture} writes. SQL Server itself refuses some of these (renaming a column of a tracked table is error 4928), so
 * the changeset fails before the capture does.
 *
 * <p>Reads the SQL that Liquibase GENERATES, the same text the other SQL checks read, so a {@code <dropColumn>}, a raw
 * {@code <sql>} and a {@code sqlFile} all land here the same way. Pure and stateless: the tracked tables come in as a parameter.
 *
 * <p>Whatever it cannot read it ignores. A statement that is not recognised is not an accusation (ADR-004).
 */
public final class CdcImpact {

    /** What happened to a tracked table. */
    public enum Kind {
        /** A column was added: it is not captured until the instance is created again. Nothing breaks. */
        COLUMN_ADDED(false, true),
        /** A column changed type, size or nullability: the instance still describes the old one. */
        COLUMN_ALTERED(true, true),
        /** A column was dropped. */
        COLUMN_DROPPED(true, true),
        /** A column was renamed. SQL Server refuses it while the table is tracked (error 4928): the instance has to go first. */
        COLUMN_RENAMED(true, true),
        /** The table was renamed: the capture instance keeps the old name, which the consumer may still be looking for. */
        TABLE_RENAMED(true, true),
        /** The table was dropped: CDC has to be disabled for it, and there is nothing to create again. */
        TABLE_DROPPED(true, false);

        private final boolean breaking;
        private final boolean recapture;

        Kind(boolean breaking, boolean recapture) {
            this.breaking = breaking;
            this.recapture = recapture;
        }

        /** True when the change can stop the capture, false when it only leaves something out. */
        public boolean breaking() {
            return breaking;
        }

        /** True when creating the capture instance again is the way out. */
        public boolean needsRecapture() {
            return recapture;
        }
    }

    /**
     * @param table  {@code schema.table} as written in the SQL (schema defaults to {@code dbo})
     * @param kind   what happened to it
     * @param detail the column, or the new name, when there is one; null otherwise
     */
    public record Impact(String table, Kind kind, String detail) {
    }

    private static final String IDENT = "(?:\\[[^\\]]+\\]|\"[^\"]+\"|[A-Za-z_][\\w$#@]*)";
    private static final String QUALIFIED = IDENT + "(?:\\s*\\.\\s*" + IDENT + "){0,2}";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;

    private static final Pattern ALTER_COLUMN = Pattern.compile("alter\\s+table\\s+(" + QUALIFIED + ")\\s+alter\\s+column\\s+(" + IDENT + ")", FLAGS);
    private static final Pattern DROP_COLUMN = Pattern.compile("alter\\s+table\\s+(" + QUALIFIED + ")\\s+drop\\s+column\\s+(?:if\\s+exists\\s+)?(" + IDENT + "(?:\\s*,\\s*" + IDENT + ")*)", FLAGS);
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "alter\\s+table\\s+(" + QUALIFIED + ")\\s+add\\s+(?!constraint\\b|primary\\b|foreign\\b|unique\\b|check\\b|default\\b|index\\b)(" + IDENT + ")", FLAGS);
    private static final Pattern DROP_TABLE = Pattern.compile("drop\\s+table\\s+(?:if\\s+exists\\s+)?(" + QUALIFIED + ")", FLAGS);
    private static final Pattern RENAME = Pattern.compile(
            "(?:exec|execute)\\s+(?:sys\\.|dbo\\.)?sp_rename\\s+(?:@objname\\s*=\\s*)?N?'([^']+)'\\s*,\\s*(?:@newname\\s*=\\s*)?N?'([^']+)'(?:\\s*,\\s*(?:@objtype\\s*=\\s*)?N?'([^']*)')?", FLAGS);

    private CdcImpact() {
    }

    /**
     * The impacts of {@code statements} on the {@code tracked} tables, in the order they happen, without repeating the same one.
     *
     * @param statements the SQL Server statements of one changeset
     * @param tracked    the tables CDC tracks where the changeset is going
     */
    public static List<Impact> of(List<String> statements, List<CdcTable> tracked) {
        Map<String, CdcTable> byKey = new LinkedHashMap<>();
        for (CdcTable table : tracked) {
            byKey.put(table.key(), table);
        }
        List<Impact> found = new ArrayList<>();
        if (byKey.isEmpty()) {
            return found;
        }
        for (String statement : statements) {
            collect(statement, byKey, found);
        }
        return found.stream().distinct().toList();
    }

    private static void collect(String statement, Map<String, CdcTable> tracked, List<Impact> out) {
        Matcher alter = ALTER_COLUMN.matcher(statement);
        while (alter.find()) {
            add(out, tracked, alter.group(1), Kind.COLUMN_ALTERED, name(alter.group(2)));
        }
        Matcher drop = DROP_COLUMN.matcher(statement);
        while (drop.find()) {
            for (String column : drop.group(2).split(",")) {
                add(out, tracked, drop.group(1), Kind.COLUMN_DROPPED, name(column));
            }
        }
        Matcher addColumn = ADD_COLUMN.matcher(statement);
        while (addColumn.find()) {
            add(out, tracked, addColumn.group(1), Kind.COLUMN_ADDED, name(addColumn.group(2)));
        }
        Matcher dropTable = DROP_TABLE.matcher(statement);
        while (dropTable.find()) {
            add(out, tracked, dropTable.group(1), Kind.TABLE_DROPPED, null);
        }
        Matcher rename = RENAME.matcher(statement);
        while (rename.find()) {
            List<String> parts = parts(rename.group(1));
            boolean column = "column".equalsIgnoreCase(rename.group(3) == null ? "" : rename.group(3).trim());
            if (column && parts.size() >= 2) {
                // [schema.]table.column: Liquibase leaves the schema out when the changeset does not name one.
                add(out, tracked, String.join(".", parts.subList(Math.max(0, parts.size() - 3), parts.size() - 1)), Kind.COLUMN_RENAMED, parts.get(parts.size() - 1) + " -> " + rename.group(2));
            } else if (!column && rename.group(3) == null && parts.size() >= 1 && parts.size() <= 3) {
                add(out, tracked, String.join(".", parts), Kind.TABLE_RENAMED, rename.group(2));
            }
        }
    }

    private static void add(List<Impact> out, Map<String, CdcTable> tracked, String qualified, Kind kind, String detail) {
        List<String> parts = parts(qualified);
        if (parts.isEmpty()) {
            return;
        }
        String table = parts.get(parts.size() - 1);
        String schema = parts.size() >= 2 ? parts.get(parts.size() - 2) : "dbo";
        CdcTable match = tracked.get(CdcTable.key(schema, table));
        if (match != null) {
            out.add(new Impact(match.schema() + "." + match.table(), kind, detail));
        }
    }

    /** {@code [dbo].[cliente]} to {@code [dbo, cliente]}: brackets and quotes come off, dots inside them stay. */
    static List<String> parts(String qualified) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char closing = 0;
        for (char c : qualified.trim().toCharArray()) {
            if (closing != 0) {
                if (c == closing) {
                    closing = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '[') {
                closing = ']';
            } else if (c == '"') {
                closing = '"';
            } else if (c == '.') {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else if (!Character.isWhitespace(c)) {
                current.append(c);
            }
        }
        parts.add(current.toString().trim());
        parts.removeIf(String::isEmpty);
        return parts;
    }

    private static String name(String identifier) {
        List<String> parts = parts(identifier);
        return parts.isEmpty() ? identifier.trim() : parts.get(parts.size() - 1);
    }
}
