package io.github.carloslimajlle.liquimanager.ingest;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.security.ServiceTokenAuthentication;

/** {@code POST /ingest/operations}: where collectors report what Liquibase just did. */
@RestController
@RequestMapping(Api.V1 + "/ingest")
class IngestController {

    private final IngestService service;
    private final String publicUrl;

    IngestController(IngestService service, @Value("${lqm.public-url:http://localhost:5173}") String publicUrl) {
        this.service = service;
        this.publicUrl = publicUrl;
    }

    /**
     * 202 for a new operation, 200 when it had already been received (idempotent).
     * The collector treats both as success.
     */
    @PostMapping("/operations")
    ResponseEntity<Map<String, Object>> ingest(Authentication authentication, @RequestBody IngestOperation body) {
        ServiceTokenAuthentication token = (ServiceTokenAuthentication) authentication;
        IngestService.Result result = service.ingest(token.getPrincipal(), body);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("operationId", result.operationId());
        response.put("url", publicUrl + "/operations/" + result.operationId());
        return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(response);
    }
}
