package io.github.carloslimajlle.liquimanager.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.github.carloslimajlle.liquimanager.common.LqmException;

/**
 * Against a real Vault (deploy/vault: {@code docker compose --profile vault up -d vault} and {@code setup.sh}). Needs
 * {@code LQM_VAULT_TEST_URL}, {@code LQM_VAULT_TEST_ROLE_ID} and {@code LQM_VAULT_TEST_SECRET_ID}; without them it is skipped.
 */
@EnabledIfEnvironmentVariable(named = "LQM_VAULT_TEST_URL", matches = ".+")
class VaultIntegrationTest {

    private static final String URL = System.getenv("LQM_VAULT_TEST_URL");
    private static final String ROLE = System.getenv("LQM_VAULT_TEST_ROLE_ID");
    private static final String SECRET = System.getenv("LQM_VAULT_TEST_SECRET_ID");

    private static SecretResolver appRole(String secretId) {
        return new SecretResolver(Duration.ofMillis(1), URL, "", "", ROLE, secretId, "approle", "", "", "", "");
    }

    @Test
    @DisplayName("AppRole logs in to a real Vault and reads the KV v2 secret")
    void appRoleReadsKv2() {
        SecretResolver resolver = appRole(SECRET);

        SecretResolver.Credentials credentials = resolver.resolve("VAULT", "secret/data/lqm/pocdev");

        assertThat(credentials.username()).isEqualTo("poc");
        assertThat(credentials.password()).isEqualTo("poc");
        assertThat(resolver.vaultLoginHeld()).isTrue();
    }

    @Test
    @DisplayName("the policy of the role is respected: a path outside secret/data/lqm is refused, with the status only")
    void policyIsRespected() {
        assertThatThrownBy(() -> appRole(SECRET).resolve("VAULT", "secret/data/outro/segredo")).isInstanceOf(LqmException.class)
                .hasMessageContaining("VAULT answered 403");
    }

    @Test
    @DisplayName("a wrong secret id is LQM-050 and leaks neither id")
    void wrongSecretId() {
        assertThatThrownBy(() -> appRole("00000000-0000-0000-0000-000000000000").resolve("VAULT", "secret/data/lqm/pocdev"))
                .isInstanceOf(LqmException.class).hasMessageContaining("login answered").hasMessageNotContaining(ROLE);
    }
}
