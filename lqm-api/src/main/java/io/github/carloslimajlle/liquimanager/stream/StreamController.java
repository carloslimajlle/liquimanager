package io.github.carloslimajlle.liquimanager.stream;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.github.carloslimajlle.liquimanager.common.Api;
import org.springframework.security.core.Authentication;
import io.github.carloslimajlle.liquimanager.access.ReadScope;
import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;

/** {@code GET /stream/operations}: live feed behind the dashboard (screen 01). */
@RestController
@RequestMapping(Api.V1 + "/stream")
class StreamController {

    private final OperationStream stream;
    private final ReadScope read;
    private final WorkspaceContext workspaces;

    StreamController(OperationStream stream, ReadScope read, WorkspaceContext workspaces) {
        this.stream = stream;
        this.read = read;
        this.workspaces = workspaces;
    }

    @GetMapping(path = "/operations", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter operations(Authentication authentication) {
        // Each subscriber sees the operations of the connections its scopes cover, fixed when it connects: it reconnects every
        // half hour at most, which is also when a change of its bindings takes effect.
        return stream.subscribe(read.of(workspaces.resolve(null), authentication));
    }
}
