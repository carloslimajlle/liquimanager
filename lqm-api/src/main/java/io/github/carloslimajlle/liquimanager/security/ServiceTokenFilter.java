package io.github.carloslimajlle.liquimanager.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates collector requests carrying {@code Authorization: Bearer lqm_...}, or {@code X-Gitlab-Token: lqm_...}: the header GitLab
 * sends with a webhook, which cannot be made to send an Authorization one.
 *
 * <p>Deliberately NOT a Spring bean: a Filter bean would be registered on every
 * path by the servlet container, and this one belongs to a single security chain.
 * A missing or unrecognised header just falls through unauthenticated, and the
 * chain's authorization rules answer 401.
 */
public class ServiceTokenFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";
    private static final String GITLAB_TOKEN = "X-Gitlab-Token";

    private final ServiceTokenService tokens;

    public ServiceTokenFilter(ServiceTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String presented = header != null && header.startsWith(PREFIX) ? header.substring(PREFIX.length()).trim() : request.getHeader(GITLAB_TOKEN);
        if (presented != null && !presented.isBlank()) {
            tokens.authenticate(presented.trim())
                    .ifPresent(token -> SecurityContextHolder.getContext()
                            .setAuthentication(new ServiceTokenAuthentication(token)));
        }
        chain.doFilter(request, response);
    }
}
