package io.github.carloslimajlle.liquimanager.catalog;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.github.carloslimajlle.liquimanager.common.LqmException;

/**
 * Resolves which workspace a user request is about, from the {@code X-LQM-Workspace}
 * header. Without the header it falls back to the {@code default} workspace.
 *
 * <p>A missing header means the default workspace, in reads and writes alike, never "all the
 * ones the user may see" (D-23): the strict answer. The SPA has no workspace selector yet and uses the default.
 */
@Component
public class WorkspaceContext {

    public static final String HEADER = "X-LQM-Workspace";
    static final String DEFAULT_SLUG = "default";

    private final JdbcClient jdbc;

    WorkspaceContext(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID resolve(String slug) {
        String wanted = slug == null || slug.isBlank() ? DEFAULT_SLUG : slug;
        return jdbc.sql("select id from workspace where slug = :slug")
                .param("slug", wanted)
                .query(UUID.class).optional()
                .orElseThrow(() -> LqmException.notFound("workspace " + wanted));
    }
}
