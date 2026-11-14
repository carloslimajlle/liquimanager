package io.github.carloslimajlle.liquimanager.policy.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.policy.cdc.CdcRecapture.Plan;

/** The two-step script: a new instance first, the old one removed later, each with a rollback, named without a clock. */
class CdcRecaptureTest {

    private static final CdcTable CLIENTE = new CdcTable("dbo", "Cliente", List.of(new CdcTable.Instance("dbo_Cliente", null, true)));

    @Test
    @DisplayName("step 1 creates the next instance with the role and net changes of the current one; step 2 removes the old one")
    void twoSteps() {
        Plan plan = CdcRecapture.of(List.of(CLIENTE));

        assertThat(plan.tables()).containsExactly("dbo.Cliente");
        assertThat(plan.createSql()).contains("sys.sp_cdc_enable_table").contains("@capture_instance = N'dbo_Cliente_v2'")
                .contains("@source_schema = N'dbo'").contains("@source_name = N'Cliente'").contains("@role_name = NULL").contains("@supports_net_changes = 1")
                .doesNotContain("sp_cdc_disable_table");
        assertThat(plan.removeSql()).contains("sp_cdc_disable_table").contains("@capture_instance = N'dbo_Cliente'").doesNotContain("sp_cdc_enable_table");
    }

    @Test
    @DisplayName("step 1 stops, with a message, when the table already has the two instances SQL Server allows")
    void guardsTheLimit() {
        assertThat(CdcRecapture.of(List.of(CLIENTE)).createSql()).contains("THROW 51000").contains(">= 2").contains("cdc.change_tables")
                .contains("IF NOT EXISTS").contains("BEGIN");
    }

    @Test
    @DisplayName("each changeset carries an explicit rollback that undoes it: the new instance is disabled, the old one enabled again")
    void rollbacks() {
        Plan plan = CdcRecapture.of(List.of(new CdcTable("dbo", "Cliente", List.of(new CdcTable.Instance("dbo_Cliente", "cdc_reader", false)))));

        assertThat(plan.createChangesets()).contains("<changeSet id=\"cdc-recapture-dbo-cliente-dbo-cliente-v2\" author=\"lqm\" dbms=\"mssql\" labels=\"cdc\">")
                .contains("<rollback>").contains("sp_cdc_disable_table").contains("@capture_instance = N'dbo_Cliente_v2'");
        assertThat(plan.removeChangesets()).contains("<changeSet id=\"cdc-remove-dbo-cliente-dbo-cliente\"").contains("<rollback>")
                .contains("sp_cdc_enable_table").contains("@role_name = N'cdc_reader'").contains("@supports_net_changes = 0");
    }

    @Test
    @DisplayName("the next name counts up from the current one, and with two instances the oldest is the one to remove")
    void naming() {
        assertThat(CdcRecapture.nextName("dbo_Cliente")).isEqualTo("dbo_Cliente_v2");
        assertThat(CdcRecapture.nextName("dbo_Cliente_v2")).isEqualTo("dbo_Cliente_v3");
        assertThat(CdcRecapture.nextName("dbo_Cliente_v9")).isEqualTo("dbo_Cliente_v10");

        CdcTable two = new CdcTable("dbo", "Cliente", List.of(new CdcTable.Instance("dbo_Cliente", null, true), new CdcTable.Instance("dbo_Cliente_v2", null, true)));
        Plan plan = CdcRecapture.of(List.of(two));
        assertThat(plan.createSql()).contains("dbo_Cliente_v3");
        assertThat(plan.removeSql()).contains("@capture_instance = N'dbo_Cliente'").doesNotContain("dbo_Cliente_v2");
        assertThat(plan.notes()).anyMatch(note -> note.contains("2 instâncias"));
    }

    @Test
    @DisplayName("quotes in names are doubled, markup in the changeset is escaped, and a table without an instance is left out")
    void escaping() {
        CdcTable odd = new CdcTable("dbo", "O'Brien <x>", List.of(new CdcTable.Instance("dbo_OBrien", null, true)));
        Plan plan = CdcRecapture.of(List.of(odd, new CdcTable("dbo", "Nada", List.of())));

        assertThat(plan.tables()).containsExactly("dbo.O'Brien <x>");
        assertThat(plan.createSql()).contains("N'O''Brien <x>'");
        assertThat(plan.createChangesets()).contains("O''Brien &lt;x&gt;").doesNotContain("<x>");
        assertThat(CdcRecapture.of(List.of()).notes()).isEmpty();
    }

    @Test
    @DisplayName("the plan does not depend on the clock: the same input gives the same text")
    void deterministic() {
        assertThat(CdcRecapture.of(List.of(CLIENTE))).isEqualTo(CdcRecapture.of(List.of(CLIENTE)));
    }
}
