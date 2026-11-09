package io.github.carloslimajlle.liquimanager.security;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Authentication produced by a valid service token. Scopes become SCOPE_* authorities. */
public class ServiceTokenAuthentication extends AbstractAuthenticationToken {

    private final ServiceToken token;

    public ServiceTokenAuthentication(ServiceToken token) {
        super(token.scopes().stream()
                .<GrantedAuthority>map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                .toList());
        this.token = token;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public ServiceToken getPrincipal() {
        return token;
    }

    @Override
    public String getName() {
        return "service:" + token.name();
    }

    public List<String> scopes() {
        return token.scopes();
    }
}
