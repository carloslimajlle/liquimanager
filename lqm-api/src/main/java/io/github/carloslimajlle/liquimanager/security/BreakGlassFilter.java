package io.github.carloslimajlle.liquimanager.security;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Recognises {@code Authorization: Bearer lqm_bg_...} as a break-glass session. Registered before the JWT filter, which is told to
 * ignore that prefix ({@link #isBreakGlass}); anything else passes through untouched. A break-glass token that is expired, revoked or
 * unknown is a 401 here, not a JWT parse error further on.
 */
public class BreakGlassFilter extends OncePerRequestFilter {

    private final BreakGlassService service;

    public BreakGlassFilter(BreakGlassService service) {
        this.service = service;
    }

    /** Whether the request carries a break-glass token, so the JWT resolver leaves it alone. */
    public static boolean isBreakGlass(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.startsWith("Bearer " + BreakGlassService.PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isBreakGlass(request)) {
            chain.doFilter(request, response);
            return;
        }
        Optional<UUID> session = service.authenticate(request.getHeader("Authorization").substring("Bearer ".length()).trim());
        if (session.isEmpty()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"title\":\"Unauthorized\",\"status\":401}");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new BreakGlassAuthentication(service.username(), session.get()));
        chain.doFilter(request, response);
    }
}
