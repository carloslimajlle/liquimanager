package io.github.carloslimajlle.liquimanager.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import io.github.carloslimajlle.liquimanager.access.PermissionService;

class UsernameJwtConverterTest {

    private static final String SUBJECT = "5d1f3c2a-0000-4000-8000-000000000001";

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("t").header("alg", "none").subject(SUBJECT)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
    }

    @Test
    void theCallerIsNamedByTheUsernameNotTheSubject() {
        var token = new UsernameJwtConverter().convert(jwt().claim("preferred_username", "dora").build());
        assertThat(token.getName()).isEqualTo("dora");
        // and the identity the rules use is the same person under the same name
        assertThat(PermissionService.identityOf(token).name()).isEqualTo("dora");
    }

    @Test
    void withoutAUsernameItFallsBackToTheSubject() {
        assertThat(new UsernameJwtConverter().convert(jwt().build()).getName()).isEqualTo(SUBJECT);
    }

    @Test
    void aBlankUsernameDoesNotBecomeTheName() {
        assertThat(new UsernameJwtConverter().convert(jwt().claim("preferred_username", "  ").build()).getName()).isEqualTo(SUBJECT);
    }

    @Test
    void scopesStillBecomeAuthorities() {
        var token = new UsernameJwtConverter().convert(jwt().claim("preferred_username", "dora").claim("scope", "openid profile").build());
        assertThat(token.getAuthorities()).extracting(Object::toString).contains("SCOPE_openid", "SCOPE_profile");
    }
}
