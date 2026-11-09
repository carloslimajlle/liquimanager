package io.github.carloslimajlle.liquimanager.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;

class AuditServiceTest extends AbstractDatabaseTest {

    @Autowired
    AuditService audit;

    private void write(String action) {
        audit.record(WORKSPACE, "ana", action, "policy-check", UUID.randomUUID().toString(),
                Map.of("severity", 2, "name", "a"), Map.of("name", "b", "severity", 3));
    }

    @Test
    @DisplayName("a chain of events verifies, including JSON whose keys came in any order")
    void chainVerifies() {
        write("policy.update");
        write("policy.create");

        AuditService.Verification result = audit.verify();

        assertThat(result.intact()).isTrue();
        assertThat(result.checked()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("nested documents verify whatever order their keys were written in")
    void nestedDocumentsVerify() {
        java.util.LinkedHashMap<String, Object> inner = new java.util.LinkedHashMap<>();
        inner.put("zeta", 1);
        inner.put("alpha", java.util.List.of(Map.of("y", 2, "x", 1), Map.of("b", "q")));
        java.util.LinkedHashMap<String, Object> outer = new java.util.LinkedHashMap<>();
        outer.put("name", "long name that sorts after the short ones");
        outer.put("id", 7);
        outer.put("nested", inner);
        outer.put("nothing", null);

        audit.record(WORKSPACE, "ana", "policy.nested", "policy-check", UUID.randomUUID().toString(), null, outer);

        AuditService.Verification result = audit.verify();
        assertThat(result.intact()).as("broken at %s", result.brokenAt()).isTrue();
    }

    @Test
    @DisplayName("changing a past event breaks the chain at that event")
    void tamperingIsDetected() {
        write("policy.tamper-target");
        write("policy.after-target");
        long target = jdbc.sql("select seq from audit_event where action = 'policy.tamper-target' order by seq desc limit 1")
                .query(Long.class).single();

        jdbc.sql("update audit_event set actor = 'mallory' where seq = :seq").param("seq", target).update();
        try {
            AuditService.Verification result = audit.verify();
            assertThat(result.intact()).isFalse();
            assertThat(result.brokenAt()).isEqualTo(target);
        } finally {
            jdbc.sql("update audit_event set actor = 'ana' where seq = :seq").param("seq", target).update();
        }
        assertThat(audit.verify().intact()).as("restored").isTrue();
    }

    @Test
    @DisplayName("removing an event from the middle is detected too")
    void deletionIsDetected() {
        write("policy.keep-1");
        write("policy.delete-me");
        write("policy.keep-2");
        var removed = jdbc.sql("""
                select seq, id, workspace_id, at, actor, actor_type, action, target_type, target_id,
                       cast(before as text) as before, cast(after as text) as after, prev_hash, hash
                  from audit_event where action = 'policy.delete-me' order by seq desc limit 1
                """).query().singleRow();
        long seq = ((Number) removed.get("seq")).longValue();

        jdbc.sql("delete from audit_event where seq = :seq").param("seq", seq).update();
        try {
            AuditService.Verification result = audit.verify();
            assertThat(result.intact()).isFalse();
            assertThat(result.brokenAt()).isGreaterThan(seq);
        } finally {
            jdbc.sql("""
                    insert into audit_event (seq, id, workspace_id, at, actor, actor_type, action, target_type, target_id,
                                             before, after, prev_hash, hash)
                    values (:seq, :id, :ws, :at, :actor, :atype, :action, :tt, :tid,
                            cast(:before as jsonb), cast(:after as jsonb), :prev, :hash)
                    """)
                    .param("seq", seq).param("id", removed.get("id")).param("ws", removed.get("workspace_id"))
                    .param("at", removed.get("at")).param("actor", removed.get("actor"))
                    .param("atype", removed.get("actor_type")).param("action", removed.get("action"))
                    .param("tt", removed.get("target_type")).param("tid", removed.get("target_id"))
                    .param("before", removed.get("before")).param("after", removed.get("after"))
                    .param("prev", removed.get("prev_hash")).param("hash", removed.get("hash")).update();
        }
        assertThat(audit.verify().intact()).as("restored").isTrue();
    }
}
