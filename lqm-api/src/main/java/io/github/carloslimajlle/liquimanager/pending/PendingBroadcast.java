package io.github.carloslimajlle.liquimanager.pending;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.stream.OperationStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Tells every open screen, the moment it happens, that what waits for a decision changed: a promotion, rollback or policy exception
 * was requested, approved, rejected, canceled or revoked. The screens ask again for their own list ({@code /me/pending}), so nobody
 * waits for a poll to learn that something needs them.
 *
 * <p>It listens to the requests that change those, after they succeeded, instead of every place that writes the rows: a new way to
 * decide gets announced without anybody remembering to. The requests opened by the system itself ({@code AutoPromotionRequests}) call
 * {@link OperationStream#broadcast} on their own.
 */
@Configuration
public class PendingBroadcast implements WebMvcConfigurer, HandlerInterceptor {

    /** The name of the event the browser listens for. */
    public static final String EVENT = "pending.changed";

    private final OperationStream stream;

    PendingBroadcast(OperationStream stream) {
        this.stream = stream;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns(
                Api.V1 + "/promotions/**",
                Api.V1 + "/pipelines/*/stages/*/promote",
                Api.V1 + "/operations/*/rollback-requests",
                Api.V1 + "/rollback-requests/**",
                Api.V1 + "/policy-exceptions/**",
                Api.V1 + "/access-requests/**",
                Api.V1 + "/access-requests");
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        boolean writes = !"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod()) && !"OPTIONS".equals(request.getMethod());
        if (writes && ex == null && response.getStatus() < 400) {
            stream.broadcast(EVENT);
        }
    }
}
