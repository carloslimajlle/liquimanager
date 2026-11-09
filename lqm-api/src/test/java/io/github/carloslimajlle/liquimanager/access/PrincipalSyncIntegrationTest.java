package io.github.carloslimajlle.liquimanager.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;

/** The people and groups of a signed-in token become principals, without gaining any permission by it. */
class PrincipalSyncIntegrationTest extends AbstractDatabaseTest {

    @Autowired
    PrincipalSync sync;

    private static JwtAuthenticationToken token(String username, String name, String email, List<String> groups) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(username)
                .claim("preferred_username", username).claim("name", name).claim("email", email).claim("groups", groups)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        return new JwtAuthenticationToken(jwt);
    }

    private List<Map<String, Object>> principal(String externalId) {
        return jdbc.sql("select type, display_name, email from principal where external_id = :id")
                .param("id", externalId).query().listOfRows();
    }

    private long syncEvents(String externalId) {
        return jdbc.sql("select count(*) from audit_event where action = 'access.principal.sync' and target_id = :id")
                .param("id", externalId).query(Long.class).single();
    }

    @Test
    @DisplayName("the caller and each group become principals, and only their creation is audited")
    void registersUserAndGroups() {
        String user = unique("pessoa");
        String group = unique("grupo");
        sync.sync(token(user, "Pessoa Teste", user + "@example.com", List.of(group)), "default");

        assertThat(principal(user)).singleElement().satisfies(row -> {
            assertThat(row.get("type")).isEqualTo("USER");
            assertThat(row.get("display_name")).isEqualTo("Pessoa Teste");
            assertThat(row.get("email")).isEqualTo(user + "@example.com");
        });
        assertThat(principal(group)).singleElement().satisfies(row -> assertThat(row.get("type")).isEqualTo("GROUP"));
        assertThat(syncEvents(user)).isEqualTo(1);
        assertThat(syncEvents(group)).isEqualTo(1);
    }

    @Test
    @DisplayName("signing in grants nothing: a new group has no permission until somebody binds it")
    void grantsNothing() {
        String user = unique("pessoa");
        String group = unique("grupo");
        JwtAuthenticationToken who = token(user, "Pessoa", null, List.of(group));
        sync.sync(who, "default");

        PermissionService.Identity identity = PermissionService.identityOf(who);
        assertThat(identity.groups()).containsExactly(group);
        assertThat(jdbc.sql("select count(*) from role_binding b join principal p on p.id = b.principal_id where p.external_id in (:ids)")
                .param("ids", List.of(user, group)).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("a second sign-in does not duplicate or re-audit, and a group an administrator registered keeps its type")
    void idempotentAndRespectsWhatWasRegistered() {
        String user = unique("pessoa");
        String group = unique("grupo");
        jdbc.sql("insert into principal (id, type, external_id, display_name) values (gen_random_uuid(), 'USER', :ext, 'Cadastrado a mao')")
                .param("ext", group).update();

        sync.sync(token(user, "Um", null, List.of(group)), "default");
        // a different set of groups is a different caller to the throttle, so this sync runs again for the same user
        sync.sync(token(user, "Um Renomeado", null, List.of(group, unique("outro"))), "default");

        assertThat(principal(user)).hasSize(1);
        assertThat(principal(user).get(0).get("display_name")).isEqualTo("Um Renomeado");
        assertThat(syncEvents(user)).isEqualTo(1);
        assertThat(principal(group)).singleElement().satisfies(row -> assertThat(row.get("type")).isEqualTo("USER"));
        assertThat(syncEvents(group)).isZero();
    }

    @Test
    @DisplayName("the development caller and an unauthenticated request register nothing")
    void ignoresTheDevelopmentChain() {
        String user = unique("dev");
        sync.sync(new UsernamePasswordAuthenticationToken(user, null, List.of()), "default");
        sync.sync(null, "default");
        assertThat(principal(user)).isEmpty();
    }

    @Test
    @DisplayName("an unknown workspace registers nothing and does not fail")
    void unknownWorkspace() {
        String user = unique("pessoa");
        sync.sync(token(user, "Pessoa", null, List.of()), "does-not-exist-" + user);
        assertThat(principal(user)).isEmpty();
    }
}
