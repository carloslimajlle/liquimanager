package io.github.carloslimajlle.liquimanager.config;

import java.io.IOException;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.security.BreakGlassFilter;
import io.github.carloslimajlle.liquimanager.security.BreakGlassService;
import io.github.carloslimajlle.liquimanager.security.DevIdentityFilter;
import io.github.carloslimajlle.liquimanager.security.ServiceTokenFilter;
import io.github.carloslimajlle.liquimanager.security.ServiceTokenService;
import io.github.carloslimajlle.liquimanager.security.UsernameJwtConverter;
import jakarta.servlet.http.HttpServletResponse;

/**
 * API security: two chains, because two kinds of caller.
 *
 * <ul>
 *   <li>Collectors (starter, CLI, scanner) use a service token
 *       {@code lqm_<prefix8>_<secret32>} on the ingestion and policy endpoints.
 *   <li>Users use an OIDC JWT (D-03, Keycloak) everywhere else.
 * </ul>
 *
 * <p>Stateless throughout: the API runs with two or more replicas without a shared
 * session (RNF-05).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final String[] COLLECTOR_PATHS = {Api.V1 + "/ingest/**", Api.V1 + "/policies/**", Api.V1 + "/runner/**", Api.V1 + "/integrations/**"};

    /**
     * Collector chain. Always requires a service token, even in local development,
     * so the real path gets exercised everywhere instead of only in production.
     */
    @Bean
    @Order(1)
    SecurityFilterChain collectors(HttpSecurity http, ServiceTokenService tokens) throws Exception {
        return http
                .securityMatcher(COLLECTOR_PATHS)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ServiceTokenFilter(tokens), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, Api.V1 + "/ingest/**").hasAuthority("SCOPE_ingest")
                        .requestMatchers(HttpMethod.GET, Api.V1 + "/policies/**")
                        .hasAnyAuthority("SCOPE_ingest", "SCOPE_checks:read")
                        // The comparison of checksums is a question, asked with a body because it carries the whole changelog.
                        .requestMatchers(HttpMethod.POST, Api.V1 + "/policies/checksum-check")
                        .hasAnyAuthority("SCOPE_ingest", "SCOPE_checks:read")
                        .requestMatchers(HttpMethod.POST, Api.V1 + "/runner/**").hasAuthority("SCOPE_runner")
                        // GitLab's webhooks (merge request closed or reopened), sent with the token in X-Gitlab-Token.
                        .requestMatchers(HttpMethod.POST, Api.V1 + "/integrations/gitlab/webhook").hasAuthority("SCOPE_webhook")
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> problem(res, HttpStatus.UNAUTHORIZED, "Unauthorized"))
                        .accessDeniedHandler((req, res, ex) -> problem(res, HttpStatus.FORBIDDEN, "Forbidden")))
                .build();
    }

    /**
     * User chain with a JWT. Requires it everywhere except health and the contract
     * docs. Conditional on the issuer being configured: without it the resource
     * server does not start, which would make a local environment impossible to run.
     */
    @Bean
    @Order(2)
    @ConditionalOnProperty(name = "spring.security.oauth2.resourceserver.jwt.issuer-uri")
    SecurityFilterChain withJwt(HttpSecurity http, BreakGlassService breakGlass) throws Exception {
        DefaultBearerTokenResolver jwtResolver = new DefaultBearerTokenResolver();
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // The emergency account (docs/09 § 1): its own token, recognised before the JWT filter, which must not try to parse it.
                .addFilterBefore(new BreakGlassFilter(breakGlass), BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, Api.V1 + "/auth/break-glass").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/api/docs/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated())
                // The caller is named by preferred_username, not by the Keycloak subject (a UUID): see UsernameJwtConverter.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(request -> BreakGlassFilter.isBreakGlass(request) ? null : jwtResolver.resolve(request))
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new UsernameJwtConverter())))
                .build();
    }

    /**
     * Development chain, used when no issuer is configured. It permits everything
     * ON PURPOSE, so the local docker-compose environment starts without Keycloak.
     * It must never exist in HML/PRD: there the issuer is always set. The log shouts,
     * so nobody starts this way without noticing.
     */
    @Bean
    @Order(2)
    @ConditionalOnProperty(name = "spring.security.oauth2.resourceserver.jwt.issuer-uri",
            matchIfMissing = true, havingValue = "not-configured")
    SecurityFilterChain withoutJwt(HttpSecurity http) throws Exception {
        LoggerFactory.getLogger(SecurityConfig.class).warn(
                "No OIDC issuer configured: user endpoints are starting with authentication DISABLED. "
                        + "This is only acceptable for local development.");
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new DevIdentityFilter(), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    private static void problem(HttpServletResponse res, HttpStatus status, String title) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.getWriter().write("{\"title\":\"" + title + "\",\"status\":" + status.value() + "}");
    }
}
