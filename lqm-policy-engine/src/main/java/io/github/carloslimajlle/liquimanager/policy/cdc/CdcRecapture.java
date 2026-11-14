package io.github.carloslimajlle.liquimanager.policy.cdc;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SQL Server script that gives a table its capture instance back after a DDL, in the two steps the consumer needs.
 *
 * <ol>
 *   <li><b>Create</b> a new instance, with the columns the table has now, while the current one keeps capturing. SQL Server allows two
 *       per table, so the consumer (Debezium reads the new one once it exists) is never left without a source.</li>
 *   <li><b>Remove</b> the old instance, in a LATER release, once the consumer has moved on. Removing it with the consumer still on it
 *       is exactly the failure this avoids, so it is a separate script on purpose.</li>
 * </ol>
 *
 * <p>Both come as plain T-SQL and as Liquibase changesets with an explicit rollback, because the product asks that of every
 * changeset. The role and the net-changes setting are copied from the instance being replaced. The captured columns are NOT: the
 * new instance takes every column the table has, which is the point of creating it again.
 *
 * <p>Pure and deterministic: the names come from the instances it is given, never from a clock.
 */
public final class CdcRecapture {

    /**
     * @param tables           {@code schema.table} of every table in the plan
     * @param createSql        step 1 as T-SQL
     * @param removeSql        step 2 as T-SQL
     * @param createChangesets step 1 as Liquibase XML changesets, one per table
     * @param removeChangesets step 2 as Liquibase XML changesets, one per table
     * @param notes            what the person must know before using it (the two-instance limit, the order, the consumer)
     */
    public record Plan(List<String> tables, String createSql, String removeSql, String createChangesets, String removeChangesets,
                       List<String> notes) {
    }

    private static final Pattern VERSIONED = Pattern.compile("^(.*)_v(\\d+)$");

    private CdcRecapture() {
    }

    /** The plan for {@code tables}, each of which has at least one capture instance. Tables without one are left out. */
    public static Plan of(List<CdcTable> tables) {
        List<String> names = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        StringBuilder createSql = new StringBuilder();
        StringBuilder removeSql = new StringBuilder();
        StringBuilder createXml = new StringBuilder();
        StringBuilder removeXml = new StringBuilder();

        for (CdcTable table : tables) {
            if (table.instances().isEmpty()) {
                continue;
            }
            CdcTable.Instance current = table.instances().get(table.instances().size() - 1);
            CdcTable.Instance oldest = table.instances().get(0);
            String next = nextName(current.name());
            String label = table.schema() + "." + table.table();
            names.add(label);
            if (table.instances().size() >= 2) {
                notes.add(label + " já tem 2 instâncias de captura, o limite do SQL Server: conclua a recaptura anterior"
                        + " (remova " + oldest.name() + ") antes de criar " + next + ".");
            }

            String create = create(table, current, next);
            String remove = remove(table, oldest.name());
            createSql.append(create).append('\n');
            removeSql.append(remove).append('\n');
            createXml.append(changeset("cdc-recapture-" + slug(table) + "-" + slug(next), "Cria a instância de captura " + next + " de " + label
                    + " com as colunas atuais da tabela. Vai DEPOIS do changeset que altera a tabela.", create, disable(table, next)));
            removeXml.append(changeset("cdc-remove-" + slug(table) + "-" + slug(oldest.name()), "Remove a instância de captura antiga " + oldest.name()
                    + " de " + label + ". Só numa release posterior, com o consumidor já lendo " + next + ".", remove,
                    enable(table, oldest, oldest.name())));
        }
        if (!names.isEmpty()) {
            notes.add("Ordem: o passo 1 vai logo depois do changeset que altera a tabela; o passo 2, numa release posterior.");
            notes.add("Antes do passo 2, confirme que o consumidor (Debezium) já lê a instância nova. Remover a antiga com o consumidor"
                    + " ainda nela é o que faz o CDC parar.");
            notes.add("A instância nova captura todas as colunas da tabela. Se a antiga captura só um subconjunto, ajuste @captured_column_list.");
        }
        return new Plan(List.copyOf(names), createSql.toString().stripTrailing(), removeSql.toString().stripTrailing(),
                createXml.toString().stripTrailing(), removeXml.toString().stripTrailing(), List.copyOf(notes));
    }

    /** {@code dbo_cliente} to {@code dbo_cliente_v2}, {@code dbo_cliente_v2} to {@code dbo_cliente_v3}. */
    static String nextName(String current) {
        Matcher versioned = VERSIONED.matcher(current);
        if (versioned.matches()) {
            return versioned.group(1) + "_v" + (Integer.parseInt(versioned.group(2)) + 1);
        }
        return current + "_v2";
    }

    private static String create(CdcTable table, CdcTable.Instance current, String next) {
        String quoted = table.quoted();
        return "-- " + quoted + ": cria a instância de captura " + next + ", com as colunas que a tabela tem depois do DDL.\n"
                + "-- A instância atual (" + current.name() + ") continua capturando até ser removida no passo 2.\n"
                + "IF NOT EXISTS (SELECT 1 FROM cdc.change_tables WHERE capture_instance = N" + lit(next) + ")\n"
                + "BEGIN\n"
                + "    IF (SELECT COUNT(*) FROM cdc.change_tables WHERE source_object_id = OBJECT_ID(N" + lit(quoted) + ")) >= 2\n"
                + "        THROW 51000, N" + lit(quoted + " já tem 2 instâncias de captura (limite do SQL Server). Remova a mais antiga antes.") + ", 1;\n"
                + "    " + enableCall(table, current, next).replace("\n", "\n    ") + ";\n"
                + "END;\n";
    }

    private static String remove(CdcTable table, String instance) {
        return "-- " + table.quoted() + ": remove a instância de captura antiga " + instance + ". Só depois que o consumidor lê a nova.\n"
                + "IF EXISTS (SELECT 1 FROM cdc.change_tables WHERE capture_instance = N" + lit(instance) + ")\n"
                + "    " + disableCall(table, instance) + ";\n";
    }

    private static String enable(CdcTable table, CdcTable.Instance like, String instance) {
        return enableCall(table, like, instance) + ";";
    }

    private static String disable(CdcTable table, String instance) {
        return disableCall(table, instance) + ";";
    }

    private static String enableCall(CdcTable table, CdcTable.Instance like, String instance) {
        return "EXEC sys.sp_cdc_enable_table\n"
                + "    @source_schema = N" + lit(table.schema()) + ",\n"
                + "    @source_name = N" + lit(table.table()) + ",\n"
                + "    @capture_instance = N" + lit(instance) + ",\n"
                + "    @role_name = " + (like.role() == null || like.role().isBlank() ? "NULL" : "N" + lit(like.role())) + ",\n"
                + "    @supports_net_changes = " + (like.supportsNetChanges() ? "1" : "0");
    }

    private static String disableCall(CdcTable table, String instance) {
        return "EXEC sys.sp_cdc_disable_table @source_schema = N" + lit(table.schema()) + ", @source_name = N" + lit(table.table())
                + ", @capture_instance = N" + lit(instance);
    }

    private static String changeset(String id, String comment, String sql, String rollback) {
        return "<changeSet id=\"" + xml(id) + "\" author=\"lqm\" dbms=\"mssql\" labels=\"cdc\">\n"
                + "    <comment>" + xml(comment) + "</comment>\n"
                + "    <sql splitStatements=\"false\">\n" + xml(sql.stripTrailing()) + "\n    </sql>\n"
                + "    <rollback>\n"
                + "        <sql splitStatements=\"false\">\n" + xml(rollback) + "\n        </sql>\n"
                + "    </rollback>\n"
                + "</changeSet>\n";
    }

    /** A T-SQL string literal body, between its quotes: single quotes doubled. */
    private static String lit(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String slug(CdcTable table) {
        return slug(table.schema() + "-" + table.table());
    }

    private static String slug(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    /** Text and attribute values: the three characters XML reserves; the quote too, so it also serves for an attribute. */
    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
