package io.github.carloslimajlle.liquimanager.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import io.github.carloslimajlle.liquimanager.access.PermissionService;
import tools.jackson.databind.JsonNode;

/** The emergency account: three factors, one use per code, a lockout, a session that can be revoked, and everything audited. */
@TestPropertySource(properties = {
        "lqm.security.break-glass.enabled=true",
        "lqm.security.break-glass.username=glass",
        "lqm.security.break-glass.password-hash={noop}correct-horse",
        "lqm.security.break-glass.totp-secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"})
class BreakGlassIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1/auth/break-glass";
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Autowired
    private BreakGlassService service;
    @Autowired
    private PermissionService permissions;

    @BeforeEach
    void reset() {
        jdbc.sql("insert into break_glass_state (id) values (1) on conflict do nothing").update();
        jdbc.sql("update break_glass_state set failed_attempts = 0, locked_until = null, last_totp_step = 0 where id = 1").update();
    }

    private static String body(String user, String password, String code) {
        return "{\"username\":\"" + user + "\",\"password\":\"" + password + "\",\"code\":\"" + code + "\"}";
    }

    private static String codeAt(long secondsFromNow) {
        return Totp.codeAt(SECRET, Instant.now().plusSeconds(secondsFromNow));
    }

    private HttpResponse<String> signIn(String user, String password, String code) {
        return post(API, body(user, password, code));
    }

    private long audited(String action) {
        return jdbc.sql("select count(*) from audit_event where action = :a").param("a", action).query(Long.class).single();
    }

    @Test
    @DisplayName("name, password and code together open a session; any one wrong is the same refusal; a code works once")
    void threeFactors() {
        long failedBefore = audited("auth.break-glass.failed");

        assertThat(signIn("glass", "correct-horse", "000000").statusCode()).as("wrong code").isEqualTo(401);
        assertThat(signIn("glass", "wrong", codeAt(0)).statusCode()).as("wrong password").isEqualTo(401);
        HttpResponse<String> wrongName = signIn("someone", "correct-horse", codeAt(0));
        assertThat(wrongName.statusCode()).as("wrong name").isEqualTo(401);
        assertThat(json(wrongName).get("code").asString()).isEqualTo("LQM-080");
        assertThat(audited("auth.break-glass.failed") - failedBefore).isEqualTo(3);

        String code = codeAt(0);
        HttpResponse<String> ok = signIn("glass", "correct-horse", code);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        JsonNode session = json(ok);
        assertThat(session.get("token").asString()).startsWith("lqm_bg_");
        assertThat(Instant.parse(session.get("expiresAt").asString())).isAfter(Instant.now());

        assertThat(signIn("glass", "correct-horse", code).statusCode()).as("the same code again").isEqualTo(401);
        assertThat(signIn("glass", "correct-horse", codeAt(30)).statusCode()).as("the next step is a new code").isEqualTo(200);
        assertThat(audited("auth.break-glass.login")).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("five wrong attempts lock it, and the right credentials do not help until it opens")
    void lockout() {
        for (int i = 0; i < 5; i++) {
            assertThat(signIn("glass", "wrong", "000000").statusCode()).isEqualTo(401);
        }

        HttpResponse<String> locked = signIn("glass", "correct-horse", codeAt(0));

        assertThat(locked.statusCode()).isEqualTo(429);
        assertThat(json(locked).get("code").asString()).isEqualTo("LQM-081");
        jdbc.sql("update break_glass_state set locked_until = now() - interval '1 second' where id = 1").update();
        assertThat(signIn("glass", "correct-horse", codeAt(0)).statusCode()).as("after the lockout").isEqualTo(200);
    }

    @Test
    @DisplayName("the token authenticates as the emergency account with every permission; revoked, it is a 401; nobody gets it by name")
    void sessionLifecycle() throws Exception {
        String token = json(signIn("glass", "correct-horse", codeAt(0))).get("token").asString();
        BreakGlassFilter filter = new BreakGlassFilter(service);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        request.addHeader("Authorization", "Bearer " + token);
        SecurityContextHolder.clearContext();
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        PermissionService.Identity who = PermissionService.identityOf(SecurityContextHolder.getContext().getAuthentication());
        SecurityContextHolder.clearContext();

        assertThat(who.breakGlass()).isTrue();
        assertThat(who.name()).isEqualTo("break-glass:glass");
        assertThat(permissions.isBootstrapAdmin(who)).isTrue();
        assertThat(permissions.allowed(UUID.randomUUID(), who, "workspace:admin", Optional.empty())).isTrue();

        PermissionService.Identity claimed = new PermissionService.Identity("break-glass:glass", null, List.of(), false);
        assertThat(permissions.isBootstrapAdmin(claimed)).as("a name is not the account").isFalse();

        service.revoke(token);
        MockHttpServletResponse refused = new MockHttpServletResponse();
        filter.doFilter(request, refused, new MockFilterChain());
        assertThat(refused.getStatus()).isEqualTo(401);

        MockHttpServletRequest other = new MockHttpServletRequest("GET", "/api/v1/projects");
        other.addHeader("Authorization", "Bearer lqm_bg_not-a-session");
        MockHttpServletResponse unknown = new MockHttpServletResponse();
        filter.doFilter(other, unknown, new MockFilterChain());
        assertThat(unknown.getStatus()).isEqualTo(401);
    }
}
