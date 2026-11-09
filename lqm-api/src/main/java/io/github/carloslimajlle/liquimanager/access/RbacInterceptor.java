package io.github.carloslimajlle.liquimanager.access;

import java.util.UUID;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Applies {@link RbacRules} to every user request. A request that fails here never reaches the
 * controller, so no controller has to remember to ask.
 */
@Configuration
class RbacInterceptor implements WebMvcConfigurer, HandlerInterceptor {

    private final PermissionService permissions;
    private final WorkspaceContext workspaces;
    private final PrincipalSync principals;
    private final ScopeResolver scopes;

    RbacInterceptor(PermissionService permissions, WorkspaceContext workspaces, PrincipalSync principals, ScopeResolver scopes) {
        this.permissions = permissions;
        this.workspaces = workspaces;
        this.principals = principals;
        this.scopes = scopes;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Collectors authenticate with a service token and have their own scopes; this is for people.
        registry.addInterceptor(this).addPathPatterns(Api.V1 + "/**")
                .excludePathPatterns(Api.V1 + "/ingest/**", Api.V1 + "/policies/**", Api.V1 + "/runner/**", Api.V1 + "/integrations/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Whoever signs in becomes a principal (and so do their groups), before any rule is applied: /me needs no
        // permission and is the first call the app makes.
        principals.sync(SecurityContextHolder.getContext().getAuthentication(), request.getHeader(WorkspaceContext.HEADER));

        String path = request.getRequestURI().substring(Api.V1.length());
        RbacRules.Requirement requirement = RbacRules.required(request.getMethod(), path);
        if (requirement.isNone()) {
            return true;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        PermissionService.Identity who = PermissionService.identityOf(authentication);
        if (who.open()) {
            return true;
        }

        UUID workspace;
        try {
            workspace = workspaces.resolve(request.getHeader(WorkspaceContext.HEADER));
        } catch (LqmException unknownWorkspace) {
            // Let the handler answer 404 for a workspace that does not exist.
            return true;
        }

        String permission = requirement.permission();
        boolean allowed;
        if (PermissionService.READ.equals(permission)) {
            // A read of a resource named in the path is judged in that resource's scope; a list (no resource) needs the permission
            // somewhere and then shows only the rows of the scopes the caller holds (ReadScope).
            ScopeTarget target = scopes.forPath(path);
            allowed = target.isEmpty() ? permissions.allowedSomewhere(workspace, who, permission)
                    : permissions.allowed(workspace, who, permission, target);
        } else {
            allowed = permissions.allowed(workspace, who, permission, scopes.forPath(path));
        }
        if (!allowed) {
            throw LqmException.forbidden("missing permission " + permission);
        }
        return true;
    }

}
