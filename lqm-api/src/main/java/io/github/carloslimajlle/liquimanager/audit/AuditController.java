package io.github.carloslimajlle.liquimanager.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reading the audit trail (screen 08, "evidence for auditors") and proving it has not been touched.
 *
 * <p>The export is CSV. The spec also asks for a signed PDF; that is not done, so the export instead does the
 * part that matters for evidence: it records an {@code audit.export} event carrying the SHA-256 of the exact
 * bytes it returned, so a copy of the file can later be matched against the log.
 */
@RestController
@RequestMapping(Api.V1 + "/audit")
class AuditController {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_EXPORT = 50_000;
    private static final List<String> EXPORT_COLUMNS = List.of(
            "seq", "id", "at", "actor", "actor_type", "action", "target_type", "target_id", "hash", "before", "after");

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final AuditService audit;

    AuditController(JdbcClient jdbc, WorkspaceContext workspaces, AuditService audit) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.audit = audit;
    }

    private record Filter(UUID workspace, Instant from, Instant to, String actor, String action) {
    }

    private static final String SELECT = """
            select seq, id, at, actor, actor_type, action, target_type, target_id,
                   cast(before as text) as before, cast(after as text) as after, hash
              from audit_event
            """;

    private static final String WHERE = """
            where workspace_id = :ws
              and (cast(:from as timestamptz) is null or at >= :from)
              and (cast(:to as timestamptz) is null or at < :to)
              and (cast(:actor as text) is null or actor = :actor)
              and (cast(:action as text) is null or action like :action)
            """;

    @GetMapping
    Map<String, Object> list(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                             @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                             @RequestParam(required = false) String actor, @RequestParam(required = false) String action,
                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        Filter filter = filter(ws, from, to, actor, action);
        int pageSize = Math.max(1, Math.min(size, 200));
        int pageIndex = Math.max(0, page);

        List<Map<String, Object>> rows = jdbc.sql(SELECT + WHERE + " order by seq desc limit :limit offset :offset")
                .params(params(filter)).param("limit", pageSize).param("offset", (long) pageIndex * pageSize)
                .query().listOfRows().stream().map(AuditController::present).toList();
        long total = jdbc.sql("select count(*) from audit_event " + WHERE).params(params(filter)).query(Long.class).single();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", rows);
        out.put("page", pageIndex);
        out.put("size", pageSize);
        out.put("totalElements", total);
        return out;
    }

    /** Walks the whole chain. Global, not per workspace: the chain is one (see AuditService). */
    @GetMapping("/verify")
    Map<String, Object> verify() {
        AuditService.Verification result = audit.verify();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("intact", result.intact());
        out.put("checked", result.checked());
        out.put("brokenAt", result.brokenAt());
        return out;
    }

    @GetMapping("/export")
    ResponseEntity<byte[]> export(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                  Authentication authentication,
                                  @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                  @RequestParam(required = false) String actor, @RequestParam(required = false) String action,
                                  @RequestParam(defaultValue = "csv") String format) {
        boolean pdf = "pdf".equalsIgnoreCase(format);
        if (!pdf && !"csv".equalsIgnoreCase(format)) {
            throw LqmException.invalid("format must be csv or pdf");
        }
        Filter filter = filter(ws, from, to, actor, action);
        List<Map<String, Object>> rows = jdbc.sql(SELECT + WHERE + " order by seq limit :limit")
                .params(params(filter)).param("limit", MAX_EXPORT).query().listOfRows();
        String who = AuditService.actorOf(authentication);

        byte[] bytes = pdf ? pdf(filter, rows, who) : csv(rows);

        // The seal the spec asks for: the hash of exactly the bytes delivered, written in the log itself.
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("format", pdf ? "pdf" : "csv");
        proof.put("rows", rows.size());
        proof.put("sha256", sha256(bytes));
        audit.record(filter.workspace(), who, "audit.export", "audit", "export", null, proof);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"lqm-audit." + (pdf ? "pdf" : "csv") + "\"")
                .header("X-LQM-Export-Sha256", (String) proof.get("sha256"))
                .contentType(pdf ? MediaType.APPLICATION_PDF : MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(bytes);
    }

    private static byte[] csv(List<Map<String, Object>> rows) {
        StringBuilder csv = new StringBuilder(String.join(",", EXPORT_COLUMNS)).append('\n');
        for (Map<String, Object> r : rows) {
            List<String> cells = new ArrayList<>();
            for (String column : EXPORT_COLUMNS) {
                cells.add(cell(r.get(column)));
            }
            csv.append(String.join(",", cells)).append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] pdf(Filter filter, List<Map<String, Object>> rows, String generatedBy) {
        AuditService.Verification chain = audit.verify();
        String workspace = jdbc.sql("select name from workspace where id = :id").param("id", filter.workspace())
                .query(String.class).optional().orElse(filter.workspace().toString());
        List<AuditPdf.Row> lines = rows.stream().map(r -> new AuditPdf.Row(((Number) r.get("seq")).longValue(),
                ((Timestamp) r.get("at")).toInstant(), String.valueOf(r.get("actor")), String.valueOf(r.get("action")),
                r.get("target_type") == null ? "" : r.get("target_type") + (r.get("target_id") == null ? "" : ":" + r.get("target_id")),
                (String) r.get("hash"))).toList();
        List<String> filters = new ArrayList<>();
        if (filter.from() != null) {
            filters.add("de " + filter.from());
        }
        if (filter.to() != null) {
            filters.add("até " + filter.to());
        }
        if (filter.actor() != null) {
            filters.add("autor " + filter.actor());
        }
        if (filter.action() != null) {
            filters.add("ação " + filter.action());
        }
        AuditPdf.Header header = new AuditPdf.Header(workspace, String.join(", ", filters), generatedBy, java.time.Instant.now(),
                chain.intact(), chain.checked(), chain.brokenAt(), rows.size(), rows.size() >= MAX_EXPORT,
                rows.isEmpty() ? null : (String) rows.get(rows.size() - 1).get("hash"));
        try {
            return AuditPdf.render(header, lines);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("could not build the audit PDF", e);
        }
    }

    // ----------------------------------------------------------------- helpers

    private Filter filter(String ws, Instant from, Instant to, String actor, String action) {
        String pattern = blankToNull(action);
        return new Filter(workspaces.resolve(ws), from, to, blankToNull(actor),
                pattern == null ? null : pattern.replace('*', '%'));
    }

    private static Map<String, Object> params(Filter f) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("ws", f.workspace());
        p.put("from", f.from() == null ? null : Timestamp.from(f.from()));
        p.put("to", f.to() == null ? null : Timestamp.from(f.to()));
        p.put("actor", f.actor());
        p.put("action", f.action());
        return p;
    }

    private static Map<String, Object> present(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", r.get("seq"));
        m.put("id", r.get("id"));
        m.put("at", ((Timestamp) r.get("at")).toInstant());
        m.put("actor", r.get("actor"));
        m.put("actorType", r.get("actor_type"));
        m.put("action", r.get("action"));
        m.put("targetType", r.get("target_type"));
        m.put("targetId", r.get("target_id"));
        m.put("before", r.get("before") == null ? null : JSON.readTree((String) r.get("before")));
        m.put("after", r.get("after") == null ? null : JSON.readTree((String) r.get("after")));
        m.put("hash", r.get("hash"));
        return m;
    }

    private static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String text = value instanceof Timestamp t ? t.toInstant().toString() : String.valueOf(value);
        // A cell that starts with = + - or @ is read as a formula by spreadsheets; defuse it.
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }
}
