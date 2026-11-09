package io.github.carloslimajlle.liquimanager.security;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.Api;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Every request made with a break-glass session is audited, reads included (docs/09 § 1: "todo uso auditado"): who, what and how it
 * ended. The sign-in and logout are audited by the controller. An audit failure never changes the answer to the request.
 */
@Configuration
class BreakGlassAudit implements WebMvcConfigurer, HandlerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(BreakGlassAudit.class);

    private final AuditService audit;
    private final WorkspaceContext workspaces;

    BreakGlassAudit(AuditService audit, WorkspaceContext workspaces) {
        this.audit = audit;
        this.workspaces = workspaces;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns(Api.V1 + "/**").excludePathPatterns(Api.V1 + "/auth/break-glass/**");
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof BreakGlassAuthentication session)) {
            return;
        }
        try {
            audit.record(workspaces.resolve(request.getHeader(WorkspaceContext.HEADER)), session.getName(), "auth.break-glass.use",
                    "request", request.getMethod() + " " + request.getRequestURI(), null, Map.of("status", response.getStatus()));
        } catch (RuntimeException e) {
            LOG.warn("could not audit a break-glass request: {}", e.toString());
        }
    }
}
