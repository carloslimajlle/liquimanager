package io.github.carloslimajlle.liquimanager.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Names the caller by {@code preferred_username}, falling back to the subject.
 *
 * <p>Spring names a JWT caller by its {@code sub}, which in Keycloak is a UUID. Everything that writes down who did
 * something (the audit trail, "created by", the separation-of-duties comparisons) uses {@code authentication.getName()},
 * and it has to be the same name the person is known by everywhere else: the one the screens show, the one a binding
 * is granted to, the one {@code /me} reports.
 */
public final class UsernameJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtAuthenticationConverter authorities = new JwtAuthenticationConverter();

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        String name = username != null && !username.isBlank() ? username : jwt.getSubject();
        return new JwtAuthenticationToken(jwt, authorities.convert(jwt).getAuthorities(), name);
    }
}
