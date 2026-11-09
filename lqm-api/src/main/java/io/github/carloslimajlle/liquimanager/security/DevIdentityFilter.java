package io.github.carloslimajlle.liquimanager.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * "Act as" for local development: {@code X-LQM-User: ana} makes the request come from ana.
 *
 * <p>It exists ONLY on the development chain, which has no identity provider and already
 * permits everything. Without it nobody could try the rules that depend on WHO is acting (an
 * approver being someone other than the requester, a permission being missing) before Keycloak is
 * wired. It is not registered when an issuer is configured, so it cannot exist in HML/PRD.
 */
public class DevIdentityFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-LQM-User";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String user = request.getHeader(HEADER);
        if (user != null && !user.isBlank()) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(user.trim(), null, List.of()));
        }
        chain.doFilter(request, response);
    }
}
