package dev.nquangit.corestra.access.auth.runtime;

import dev.nquangit.corestra.access.auth.AuthFailureRule;
import dev.nquangit.corestra.access.auth.AuthKeepAliveConfig;
import dev.nquangit.corestra.access.auth.AuthRuleType;
import dev.nquangit.corestra.access.auth.AuthSuccessRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthKeepAliveEvaluatorTest {
    private final AuthKeepAliveEvaluator evaluator = new AuthKeepAliveEvaluator(new AuthRuleEvaluator());

    @Test
    void expiryTakesPriorityEvenWhenSuccessRuleAlsoMatches() {
        AuthKeepAliveConfig config = new AuthKeepAliveConfig("keep", List.of(
                new AuthSuccessRule("ok", "200", true, AuthRuleType.STATUS_CODE, "200", "")), List.of(
                new AuthFailureRule("expired", "Login page", true, AuthRuleType.BODY_CONTAINS, "login", "")));
        assertEquals(AuthKeepAliveEvaluator.Outcome.EXPIRED, evaluator.evaluate(config, result(200, "login")).outcome());
    }

    @Test
    void emptyTransportResponseCannotMatchBodyNotContainsExpiryRule() {
        AuthKeepAliveConfig config = new AuthKeepAliveConfig("keep", List.of(), List.of(
                new AuthFailureRule("expired", "No user", true, AuthRuleType.BODY_NOT_CONTAINS, "user", "")));
        assertEquals(AuthKeepAliveEvaluator.Outcome.INCONCLUSIVE, evaluator.evaluate(config, result(0, "")).outcome());
    }

    @Test
    void emptyExpressionsCannotConfirmSuccessOrExpiry() {
        AuthKeepAliveConfig config = new AuthKeepAliveConfig("keep", List.of(
                new AuthSuccessRule("ok", "Empty", true, AuthRuleType.BODY_CONTAINS, "", "")), List.of(
                new AuthFailureRule("expired", "Empty", true, AuthRuleType.BODY_CONTAINS, "", "")));
        assertEquals(AuthKeepAliveEvaluator.Outcome.INCONCLUSIVE, evaluator.evaluate(config, result(200, "anything")).outcome());
    }

    private static AuthFlowRunResult result(int code, String body) {
        return AuthFlowRunResult.executed(true, "response", code, Map.of(),
                code == 0 ? "" : "HTTP/1.1 " + code + "\r\n\r\n" + body, List.of());
    }
}
