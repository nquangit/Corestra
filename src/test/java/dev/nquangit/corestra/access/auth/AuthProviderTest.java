package dev.nquangit.corestra.access.auth;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthProviderTest {
    @Test
    void authProviderDefensivelyCopiesRuleCollections() {
        AuthInjectionRule injectionRule = injectionRule();
        AuthSuccessRule successRule = successRule();
        AuthFailureRule failureRule = failureRule();
        List<AuthInjectionRule> injectionRules = new ArrayList<>();
        List<AuthSuccessRule> successRules = new ArrayList<>();
        List<AuthFailureRule> failureRules = new ArrayList<>();
        injectionRules.add(injectionRule);
        successRules.add(successRule);
        failureRules.add(failureRule);

        AuthProvider provider = new AuthProvider(
                "provider-1",
                "Session Auth",
                "Uses login flow",
                true,
                "login-flow",
                "refresh-flow",
                "validate-flow",
                injectionRules,
                successRules,
                failureRules,
                AuthFallbackPolicy.REFRESH_THEN_LOGIN,
                AuthRetryPolicy.REFRESH_AND_RETRY_ONCE);
        injectionRules.clear();
        successRules.clear();
        failureRules.clear();

        assertEquals(List.of(injectionRule), provider.injectionRules());
        assertEquals(List.of(successRule), provider.successRules());
        assertEquals(List.of(failureRule), provider.failureRules());
        assertEquals(AuthProvider.DEFAULT_STATE_TTL_SECONDS, provider.stateTtlSeconds());
        assertThrows(UnsupportedOperationException.class, () -> provider.injectionRules().add(injectionRule));
        assertThrows(UnsupportedOperationException.class, () -> provider.successRules().add(successRule));
        assertThrows(UnsupportedOperationException.class, () -> provider.failureRules().add(failureRule));
    }

    @Test
    void validatesConfiguredFallbackStateTtl() {
        AuthProvider provider = new AuthProvider(
                "provider-1", "Auth", "", true, "login", "", "", List.of(), List.of(), List.of(),
                AuthFallbackPolicy.LOGIN_ONLY, AuthRetryPolicy.NO_RETRY, 180L);
        assertEquals(180L, provider.stateTtlSeconds());
        assertThrows(IllegalArgumentException.class, () -> new AuthProvider(
                "provider-1", "Auth", "", true, "login", "", "", List.of(), List.of(), List.of(),
                AuthFallbackPolicy.LOGIN_ONLY, AuthRetryPolicy.NO_RETRY, -1L));
    }

    @Test
    void oldJsonWithoutStateTtlUsesCompatibleDefault() throws Exception {
        String json = """
                {"id":"provider-1","name":"Auth","description":"","enabled":true,
                 "loginFlowId":"login","refreshFlowId":"","validateFlowId":"",
                 "injectionRules":[],"successRules":[],"failureRules":[],
                 "fallbackPolicy":"LOGIN_ONLY","targetRetryPolicy":"NO_RETRY"}
                """;
        AuthProvider provider = new ObjectMapper().readValue(json, AuthProvider.class);
        assertEquals(AuthProvider.DEFAULT_STATE_TTL_SECONDS, provider.stateTtlSeconds());
    }

    @Test
    void authInjectionRuleStoresLocationTargetTemplateAndMode() {
        AuthInjectionRule rule = injectionRule();

        assertEquals(AuthInjectionLocation.HEADER, rule.location());
        assertEquals("Authorization", rule.target());
        assertEquals("Bearer {{auth.accessToken}}", rule.valueTemplate());
        assertEquals(AuthInjectionMode.OVERRIDE, rule.mode());
        assertTrue(rule.enabled());
    }

    @Test
    void successAndFailureRulesStoreTypeAndExpressions() {
        AuthSuccessRule successRule = successRule();
        AuthFailureRule failureRule = failureRule();

        assertEquals(AuthRuleType.STATUS_CODE, successRule.type());
        assertEquals("200", successRule.expression());
        assertEquals("", successRule.expectedValue());
        assertEquals(AuthRuleType.BODY_CONTAINS, failureRule.type());
        assertEquals("$.error", failureRule.expression());
        assertEquals("invalid", failureRule.expectedValue());
    }

    @Test
    void authProfileManualProviderIdRemainsCompatible() {
        AuthProfile manual = AuthProfile.manual("auth-1", "Manual", "tester");
        AuthProfile configured = new AuthProfile(
                "auth-2",
                "Configured",
                "tester",
                "provider-1",
                java.util.Map.of(),
                AuthState.empty(),
                true);

        assertEquals(AuthProfile.MANUAL_PROVIDER_ID, manual.providerId());
        assertEquals("provider-1", configured.providerId());
    }

    private static AuthInjectionRule injectionRule() {
        return new AuthInjectionRule(
                "inj-1",
                "Bearer token",
                true,
                AuthInjectionLocation.HEADER,
                "Authorization",
                "Bearer {{auth.accessToken}}",
                AuthInjectionMode.OVERRIDE);
    }

    private static AuthSuccessRule successRule() {
        return new AuthSuccessRule(
                "success-1",
                "HTTP 200",
                true,
                AuthRuleType.STATUS_CODE,
                "200",
                "");
    }

    private static AuthFailureRule failureRule() {
        return new AuthFailureRule(
                "failure-1",
                "Error body",
                true,
                AuthRuleType.BODY_CONTAINS,
                "$.error",
                "invalid");
    }
}
