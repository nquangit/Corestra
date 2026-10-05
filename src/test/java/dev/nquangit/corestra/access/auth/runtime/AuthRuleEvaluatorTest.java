package dev.nquangit.corestra.access.auth.runtime;

import dev.nquangit.corestra.access.auth.AuthFailureRule;
import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.auth.AuthRuleType;
import dev.nquangit.corestra.access.auth.AuthSuccessRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthRuleEvaluatorTest {
    private final AuthRuleEvaluator evaluator = new AuthRuleEvaluator();

    @Test
    void successRuleCannotOverrideExecutionFailure() {
        AuthProvider provider = provider(List.of(new AuthSuccessRule(
                "success", "HTTP 200", true, AuthRuleType.STATUS_CODE, "200", "")), List.of());
        AuthFlowRunResult run = AuthFlowRunResult.executed(false, "Required input missing", 200,
                Map.of(), "HTTP/1.1 200 OK\r\n\r\n{}", List.of());
        AuthRuleEvaluationResult result = evaluator.evaluate(provider, AuthFlowPurpose.REFRESH, run);
        assertFalse(result.success());
        assertTrue(result.reason().contains("Required input missing"));
    }

    @Test
    void failureRuleTakesPrecedenceOverSuccessRule() {
        AuthProvider provider = provider(
                List.of(new AuthSuccessRule("success-1", "HTTP 200", true, AuthRuleType.STATUS_CODE, "200", "")),
                List.of(new AuthFailureRule("failure-1", "Failure header", true, AuthRuleType.HEADER_EXISTS, "X-Auth-Failed", "")));
        AuthFlowRunResult runResult = AuthFlowRunResult.executed(
                true,
                "ok",
                200,
                Map.of(),
                "HTTP/1.1 200 OK\nX-Auth-Failed: true\n\n{}",
                List.of());

        AuthRuleEvaluationResult result = evaluator.evaluate(provider, AuthFlowPurpose.LOGIN, runResult);

        assertFalse(result.success());
        assertTrue(result.reason().contains("failure rule matched"));
    }

    @Test
    void successRuleMatchSucceeds() {
        AuthProvider provider = provider(
                List.of(new AuthSuccessRule("success-1", "HTTP 200", true, AuthRuleType.STATUS_CODE, "200", "")),
                List.of());
        AuthFlowRunResult runResult = AuthFlowRunResult.executed(
                true,
                "ok",
                200,
                Map.of(),
                "HTTP/1.1 200 OK\n\n{}",
                List.of());

        AuthRuleEvaluationResult result = evaluator.evaluate(provider, AuthFlowPurpose.LOGIN, runResult);

        assertTrue(result.success());
        assertTrue(result.reason().contains("success rule matched"));
    }

    @Test
    void noSuccessRulesAcceptsFlowSuccess() {
        AuthProvider provider = provider(List.of(), List.of());
        AuthFlowRunResult runResult = AuthFlowRunResult.executed(
                true,
                "flow success",
                0,
                Map.of(),
                "",
                List.of());

        AuthRuleEvaluationResult result = evaluator.evaluate(provider, AuthFlowPurpose.LOGIN, runResult);

        assertTrue(result.success());
    }

    private static AuthProvider provider(List<AuthSuccessRule> successRules, List<AuthFailureRule> failureRules) {
        return new AuthProvider(
                "provider-1",
                "Provider",
                "",
                true,
                "login-flow",
                "refresh-flow",
                "validate-flow",
                List.of(),
                successRules,
                failureRules,
                AuthFallbackPolicy.LOGIN_ONLY,
                AuthRetryPolicy.NO_RETRY);
    }
}
