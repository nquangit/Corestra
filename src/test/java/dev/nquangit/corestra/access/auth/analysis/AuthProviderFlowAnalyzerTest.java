package dev.nquangit.corestra.access.auth.analysis;

import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthInjectionLocation;
import dev.nquangit.corestra.access.auth.AuthInjectionMode;
import dev.nquangit.corestra.access.auth.AuthInjectionRule;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthKeepAliveConfig;
import dev.nquangit.corestra.access.auth.AuthSuccessRule;
import dev.nquangit.corestra.access.auth.AuthRuleType;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.auth.analysis.AuthProviderFlowAnalyzer.AuthProviderFlowAnalysis;
import dev.nquangit.corestra.access.auth.analysis.AuthProviderFlowAnalyzer.Severity;
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthProviderFlowAnalyzerTest {
    private final AuthProviderFlowAnalyzer analyzer = new AuthProviderFlowAnalyzer();

    @Test
    void keepAliveContractRecognizesTemplateInputsAndWarnsAboutMissingLoginOutput() {
        AuthProvider p = withKeepAlive(provider("login", List.of()), new AuthKeepAliveConfig("keep",
                List.of(new AuthSuccessRule("ok", "OK", true, AuthRuleType.BODY_CONTAINS, "alive", "")), List.of()));
        InjectRule rule = new InjectRule("template", "Template", true, InjectRule.Target.REQUEST_HEADER,
                "Authorization", InjectRule.ValueSourceType.TEMPLATE, "",
                "Bearer {{auth.refreshToken}}", InjectRule.WriteStrategy.OVERWRITE);
        FlowDefinition login = flow("login", true, List.of(step(List.of(), List.of())));
        FlowDefinition keep = flow("keep", true, List.of(step(List.of(), List.of(rule))));
        var analysis = analyzer.analyze(p, List.of(login, keep));
        assertTrue(analysis.requiredInputs().contains("auth.refreshToken"));
        assertTrue(analysis.issues().stream().anyMatch(issue -> issue.title().contains("Keep-alive input")));
        login = flow("login", true, List.of(step(List.of(extract("token", ExtractRule.ExtractorType.HEADER,
                "X-Token", "refreshToken")), List.of())));
        assertFalse(analyzer.analyze(p, List.of(login, keep)).issues().stream()
                .anyMatch(issue -> issue.title().contains("Keep-alive input")));
    }

    @Test
    void optionalKeepAliveMustReferenceEnabledFlowAndHaveIndependentSuccessRules() {
        AuthProvider original = provider("login", List.of());
        AuthKeepAliveConfig config = new AuthKeepAliveConfig("keep", List.of(), List.of());
        AuthProvider provider = withKeepAlive(original, config);
        FlowDefinition login = flow("login", true, List.of(step(List.of(), List.of())));
        AuthProviderFlowAnalysis missing = analyzer.analyze(provider, List.of(login));
        assertTrue(missing.hasErrors());
        assertTrue(missing.issues().stream().anyMatch(issue -> issue.title().equals("Keep-alive Flow is missing")));
        assertTrue(missing.issues().stream().anyMatch(issue -> issue.title().contains("success rule")));

        config = new AuthKeepAliveConfig("keep", List.of(new AuthSuccessRule("alive", "Alive", true,
                AuthRuleType.BODY_CONTAINS, "alive", "")), List.of());
        provider = withKeepAlive(original, config);
        FlowDefinition disabled = flow("keep", false, List.of(step(List.of(), List.of())));
        assertTrue(analyzer.analyze(provider, List.of(login, disabled)).hasErrors());
        FlowDefinition enabled = flow("keep", true, List.of(step(List.of(), List.of(variableInject("auth.token")))));
        AuthProviderFlowAnalysis valid = analyzer.analyze(provider, List.of(login, enabled));
        assertFalse(valid.hasErrors());
        assertTrue(valid.requiredInputs().contains("auth.token"));
        assertTrue(valid.issues().stream().anyMatch(issue -> issue.title().contains("Keep-alive input")));
    }

    private static AuthProvider withKeepAlive(AuthProvider p, AuthKeepAliveConfig config) {
        return new AuthProvider(p.id(), p.name(), p.description(), p.enabled(), p.loginFlowId(), p.refreshFlowId(),
                p.validateFlowId(), p.injectionRules(), p.successRules(), p.failureRules(), p.fallbackPolicy(),
                p.targetRetryPolicy(), p.stateTtlSeconds(), config);
    }

    @Test
    void derivesCanonicalOutputsInputsAndInjectionSuggestions() {
        FlowDefinition login = flow("login", true, List.of(step(
                List.of(
                        extract("token", ExtractRule.ExtractorType.JSON_PATH, "$.access_token", "auth.accessToken"),
                        extract("cookie", ExtractRule.ExtractorType.COOKIE, "SESSION", "sessionId"),
                        extract("expiry", ExtractRule.ExtractorType.JSON_PATH, "$.expires_in", "expiresIn")),
                List.of(variableInject("credentials.username")))));

        AuthProviderFlowAnalysis analysis = analyzer.analyze(provider("login", List.of()), List.of(login));

        assertEquals(List.of("accessToken", "sessionId", "expiresIn"), analysis.outputKeys());
        assertEquals(List.of("credentials.username"), analysis.requiredInputs());
        assertTrue(analysis.issues().stream().anyMatch(issue -> issue.title().contains("Legacy auth prefix")));
        assertTrue(analysis.suggestions().stream().anyMatch(suggestion ->
                suggestion.location() == AuthInjectionLocation.HEADER
                        && suggestion.valueTemplate().equals("Bearer {{auth.accessToken}}")));
        assertTrue(analysis.suggestions().stream().anyMatch(suggestion ->
                suggestion.location() == AuthInjectionLocation.COOKIE
                        && suggestion.target().equals("SESSION")));
        assertFalse(analysis.hasErrors());
    }

    @Test
    void reportsMissingRequiredFlowAndUnknownAuthTemplateReference() {
        AuthInjectionRule rule = new AuthInjectionRule(
                "rule", "Bearer", true, AuthInjectionLocation.HEADER, "Authorization",
                "Bearer {{auth.missingToken}}", AuthInjectionMode.OVERRIDE);

        AuthProviderFlowAnalysis analysis = analyzer.analyze(provider("missing", List.of(rule)), List.of());

        assertTrue(analysis.hasErrors());
        assertTrue(analysis.issues().stream().anyMatch(issue ->
                issue.severity() == Severity.ERROR && issue.title().contains("missing")));
        assertTrue(analysis.issues().stream().anyMatch(issue ->
                issue.severity() == Severity.WARNING && issue.title().contains("not produced")));
    }

    @Test
    void reportsDisabledFlowDuplicateOutputsAndZeroTtlWithoutExpiry() {
        FlowDefinition login = flow("login", false, List.of(step(
                List.of(
                        extract("one", ExtractRule.ExtractorType.BODY_TEXT, "", "token"),
                        extract("two", ExtractRule.ExtractorType.HEADER, "X-Token", "token")),
                List.of())));
        AuthProvider provider = new AuthProvider(
                "provider", "Provider", "", true, "login", "", "", List.of(), List.of(), List.of(),
                AuthFallbackPolicy.LOGIN_ONLY, AuthRetryPolicy.NO_RETRY, 0L);

        AuthProviderFlowAnalysis analysis = analyzer.analyze(provider, List.of(login));

        assertTrue(analysis.hasErrors());
        assertTrue(analysis.issues().stream().anyMatch(issue -> issue.title().contains("disabled")));
        assertTrue(analysis.issues().stream().anyMatch(issue -> issue.title().contains("Duplicate")));
        assertTrue(analysis.issues().stream().anyMatch(issue -> issue.title().contains("reuse window")));
    }

    private static AuthProvider provider(String loginFlowId, List<AuthInjectionRule> rules) {
        return new AuthProvider(
                "provider", "Provider", "", true, loginFlowId, "", "", rules, List.of(), List.of(),
                AuthFallbackPolicy.LOGIN_ONLY, AuthRetryPolicy.NO_RETRY, 300L);
    }

    private static FlowDefinition flow(String id, boolean enabled, List<FlowStep> steps) {
        return new FlowDefinition(
                id, "Login", "", enabled, steps, FlowRuleSet.empty(), AggregationPlan.disabled(),
                FlowTriggerConfig.none());
    }

    private static FlowStep step(List<ExtractRule> extracts, List<InjectRule> injects) {
        return new FlowStep(
                "step", "Login request", true, "",
                new StoredRequestTemplate("POST", "https", "example.test", 443, "/login", List.of(), ""),
                extracts, injects, List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
    }

    private static ExtractRule extract(
            String id,
            ExtractRule.ExtractorType type,
            String selector,
            String target) {
        return new ExtractRule(
                id, id, true, ExtractRule.SourceSide.RESPONSE, type, selector, target, List.of());
    }

    private static InjectRule variableInject(String key) {
        return new InjectRule(
                "inject", "Credential", true, InjectRule.Target.REQUEST_HEADER, "X-Credential",
                InjectRule.ValueSourceType.VARIABLE, key, "", InjectRule.WriteStrategy.OVERWRITE);
    }
}
