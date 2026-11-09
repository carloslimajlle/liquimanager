package io.github.carloslimajlle.liquimanager.policies;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.changesets.ChecksumChecks;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.security.ServiceTokenAuthentication;

/**
 * {@code GET /policies/effective}: the package a collector evaluates before it runs.
 *
 * <p>Served to collectors only (service token with {@code ingest} or {@code checks:read}).
 * The workspace comes from the token, never from a header the caller could change.
 */
@RestController
@RequestMapping(Api.V1 + "/policies")
class PolicyController {

    private final PolicyResolver resolver;
    private final PromotionGates gates;
    private final ChecksumChecks checksums;

    PolicyController(PolicyResolver resolver, PromotionGates gates, ChecksumChecks checksums) {
        this.resolver = resolver;
        this.gates = gates;
        this.checksums = checksums;
    }

    /**
     * The rule that what is in HML has to be in DEV first. For a project and an environment: is the environment a later stage of one
     * of the project's pipelines, which is the stage before it, and which changesets are executed in that stage's database. The
     * collector (the deploy job) compares that with what it is about to apply; this answers, it does not decide.
     */
    @GetMapping("/promotion-gate")
    Map<String, Object> promotionGate(Authentication authentication, @RequestParam String project, @RequestParam String env) {
        ServiceTokenAuthentication token = (ServiceTokenAuthentication) authentication;
        return gates.forDeploy(token.getPrincipal().workspaceId(), project, env);
    }

    record ChecksumRequest(String project, String env, String commit, String changelog, java.util.List<ChecksumChecks.Item> changesets) {
    }

    /**
     * {@code ChecksumChanged}: among the changesets the caller is about to run, the ones already executed in the environment with
     * another checksum, each with the lines that changed. It answers; the caller decides what it means for the gate.
     */
    @PostMapping("/checksum-check")
    java.util.List<Map<String, Object>> checksumCheck(Authentication authentication, @RequestBody ChecksumRequest body) {
        ServiceTokenAuthentication token = (ServiceTokenAuthentication) authentication;
        return checksums.changed(token.getPrincipal().workspaceId(), body.project(), body.env(), body.commit(), body.changelog(), body.changesets());
    }

    @GetMapping("/effective")
    ResponseEntity<Map<String, Object>> effective(
            Authentication authentication,
            @RequestParam String project,
            @RequestParam String env,
            @RequestParam(required = false) String changelog,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        ServiceTokenAuthentication token = (ServiceTokenAuthentication) authentication;
        PolicyResolver.Effective effective = resolver.resolve(
                token.getPrincipal().workspaceId(), project, env, changelog, Instant.now());

        // 304: the collector already has exactly this answer and need not parse it again.
        if (effective.etag() != null && effective.etag().equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(effective.etag()).build();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("package", effective.packageName());
        body.put("etag", effective.etag());
        body.put("blockThreshold", effective.blockThreshold());
        body.put("checks", effective.checks());
        body.put("exceptions", effective.exceptions());
        return ResponseEntity.ok().eTag(effective.etag()).body(body);
    }
}
