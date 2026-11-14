package io.github.carloslimajlle.liquimanager.access;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RbacRulesTest {

    @ParameterizedTest
    @CsvSource({
            "GET,/projects,assignment:view",
            "GET,/operations/abc,assignment:view",
            "POST,/projects,catalog:edit",
            "POST,/projects/11111111-1111-1111-1111-111111111111/changelogs,catalog:edit",
            "DELETE,/teams/x,catalog:edit",
            "POST,/connections,connection:edit",
            "PUT,/pipelines/x/stages,pipeline:edit",
            "POST,/workspaces,workspace:admin",
            "POST,/environments,workspace:admin",
            "GET,/service-tokens,token:manage",
            "POST,/service-tokens,token:manage",
            "DELETE,/service-tokens/x,token:manage",
            "POST,/policy-checks,policy:edit",
            "PUT,/policy-checks/x,policy:edit",
            "POST,/policy-checks/x/copy,policy:edit",
            "POST,/policy-checks/x/test,assignment:view",
            "POST,/policy-packages,policy:edit",
            "POST,/rollback-requests/x/approve,rollback:approve",
            "POST,/rollback-requests/x/reject,rollback:approve",
            "POST,/rollback-requests/x/cancel,assignment:view",
            "GET,/rollback-requests,assignment:view",
            "GET,/operations/x/rollback-preview,assignment:view",
            "GET,/connections/x/applied-changesets,assignment:view",
            "GET,/access-requests,assignment:admin",
            "POST,/access-requests/x/dismiss,assignment:admin",
            "GET,/projects/x/changesets/in-review,assignment:view",
            "GET,/projects/x/changesets/sql,assignment:view",
            "GET,/projects/x/changesets/cdc,drift:run",
            "POST,/projects/x/changesets/in-review/close,catalog:edit",
            "POST,/projects/x/changesets/in-review/reopen,catalog:edit",
            "GET,/promotions,assignment:view",
            "POST,/policy-exceptions,exception:request",
            "POST,/policy-exceptions/x/approve,exception:approve",
            "POST,/policy-exceptions/x/revoke,exception:approve",
            "GET,/policy-exceptions,assignment:view",
            "POST,/access/bindings,assignment:admin",
            "DELETE,/access/bindings/x,assignment:admin",
            "GET,/access/bindings,assignment:view",
            "GET,/secrets/status,secret:view-status",
            "POST,/secrets/core-prd/rotated,connection:edit",
            "POST,/connections/x/drift-runs,drift:run",
            "POST,/connections/x/drift-reference,drift:run",
            "POST,/connections/x/scan,drift:run",
            "GET,/connections/x/scanner,assignment:view",
            "POST,/drift/runs/x/changelog,drift:run",
            "POST,/drift/items/x/accept,drift:accept",
            "GET,/drift/summary,assignment:view",
            "GET,/notification-rules,workspace:admin",
            "POST,/notification-rules/x/test,workspace:admin",
            "GET,/notification-deliveries,workspace:admin",
            "GET,/audit/export,audit:export",
            "POST,/something-nobody-listed,workspace:admin",
            "GET,/something-nobody-listed,assignment:view",
    })
    void eachRouteNeedsItsPermission(String method, String path, String expected) {
        assertThat(RbacRules.required(method, path).permission()).isEqualTo(expected);
    }

    @Test
    void promotionDecisionsAreCheckedByTheirController() {
        for (String action : new String[] {"approve", "reject", "cancel"}) {
            assertThat(RbacRules.required("POST", "/promotions/x/" + action).isNone()).as(action).isTrue();
        }
    }

    @Test
    void meNeedsNothingButAnIdentity() {
        assertThat(RbacRules.required("GET", "/me").isNone()).isTrue();
        assertThat(RbacRules.required("GET", "/me/pending").isNone()).isTrue();
        assertThat(RbacRules.required("POST", "/access-requests").isNone()).as("asking needs only a signed-in caller").isTrue();
        assertThat(RbacRules.required("GET", "/access-requests/mine").isNone()).isTrue();
    }

    @Test
    void theEmergencySignInNeedsNoIdentityBecauseItIsHowOneIsGotten() {
        assertThat(RbacRules.required("POST", "/auth/break-glass").isNone()).isTrue();
        assertThat(RbacRules.required("POST", "/auth/break-glass/logout").isNone()).isTrue();
    }

    @Test
    void anUnlistedWriteFallsToTheStrictestPermission() {
        assertThat(RbacRules.required("PATCH", "/brand-new-thing").permission()).isEqualTo("workspace:admin");
    }

    @Test
    void projectIdIsReadFromThePath() {
        assertThat(RbacRules.projectIdIn("/projects/11111111-1111-1111-1111-111111111111/changelogs"))
                .contains("11111111-1111-1111-1111-111111111111");
        assertThat(RbacRules.projectIdIn("/projects")).isEmpty();
        assertThat(RbacRules.projectIdIn("/pipelines/11111111-1111-1111-1111-111111111111")).isEmpty();
    }
}
