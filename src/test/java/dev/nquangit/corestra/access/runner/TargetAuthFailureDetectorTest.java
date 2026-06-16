package dev.nquangit.corestra.access.runner;

import dev.nquangit.corestra.access.auth.AuthFailureRule;
import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.auth.AuthRuleType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetAuthFailureDetectorTest {
    private final TargetAuthFailureDetector detector = new TargetAuthFailureDetector();

    @Test
    void matchesStatusFailureRule() {
        assertTrue(detector.isAuthFailure(provider(rule(AuthRuleType.STATUS_CODE, "401", "")),
                401,
                "HTTP/1.1 401 Unauthorized\n\n"));
    }

    @Test
    void matchesHeaderFailureRule() {
        assertTrue(detector.isAuthFailure(provider(rule(AuthRuleType.HEADER_EXISTS, "WWW-Authenticate", "")),
                200,
                "HTTP/1.1 200 OK\nWWW-Authenticate: Bearer\n\n{}"));
    }

    @Test
    void matchesBodyContainsFailureRule() {
        assertTrue(detector.isAuthFailure(provider(rule(AuthRuleType.BODY_CONTAINS, "", "login required")),
                200,
                "HTTP/1.1 200 OK\n\n{\"error\":\"login required\"}"));
    }

    @Test
    void ignoresUnsupportedFailureRuleType() {
        assertFalse(detector.isAuthFailure(provider(rule(AuthRuleType.JSON_PATH, "$.error", "unauthorized")),
                401,
                "HTTP/1.1 401 Unauthorized\n\n{\"error\":\"unauthorized\"}"));
    }

    private static AuthProvider provider(AuthFailureRule rule) {
        return new AuthProvider(
                "provider-1",
                "Provider",
                "",
                true,
                "login-flow",
                "refresh-flow",
                "",
                List.of(),
                List.of(),
                List.of(rule),
                AuthFallbackPolicy.LOGIN_ONLY,
                AuthRetryPolicy.REFRESH_AND_RETRY_ONCE);
    }

    private static AuthFailureRule rule(AuthRuleType type, String expression, String expectedValue) {
        return new AuthFailureRule("failure-1", "Failure", true, type, expression, expectedValue);
    }
}
