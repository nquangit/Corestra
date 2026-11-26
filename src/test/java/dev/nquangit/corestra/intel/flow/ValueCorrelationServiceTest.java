package dev.nquangit.corestra.intel.flow;

import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.http.extract.ExtractionResult;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.http.inject.RequestMutationResult;
import dev.nquangit.corestra.intel.RawHttpMessages;
import dev.nquangit.corestra.intel.SuggestionConfidence;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueCorrelationServiceTest {
    private static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.c2lnLTEyMw";
    private static final String LOGIN_RESPONSE = "HTTP/1.1 200 OK\r\n"
            + "Set-Cookie: sid=s3ss10nValue99; Path=/; HttpOnly\r\n"
            + "Content-Type: application/json\r\n"
            + "Date: Mon, 01 Jan 2026 00:00:00 GMT\r\n"
            + "\r\n"
            + "{\"access_token\":\"" + JWT + "\",\"user\":{\"id\":4821,\"name\":\"alice\"},"
            + "\"csrf\":\"c5rfT0kenValue\",\"ok\":true}";

    private final ValueCorrelationService service = new ValueCorrelationService();

    @Test
    void findsTokenCookieIdentifierAndCsrfByValueNotByName() {
        FlowDefinition flow = flow(loginStep(List.of()), ordersStep(List.of()));

        List<CorrelationSuggestion> suggestions = service.suggest(flow, flow.steps().get(1));

        CorrelationSuggestion auth = byLocation(suggestions, "Header Authorization").orElseThrow();
        assertEquals(SuggestionConfidence.HIGH, auth.confidence());
        assertEquals("access_token", auth.variableName());
        assertEquals("pointer:/access_token", auth.newExtractRule().sourceSelector());
        assertEquals(InjectRule.ValueSourceType.TEMPLATE, auth.injectRule().valueSourceType());
        assertEquals("Bearer {{access_token}}", auth.injectRule().staticValue());

        CorrelationSuggestion cookie = byLocation(suggestions, "Cookie sid").orElseThrow();
        assertEquals(ExtractRule.ExtractorType.COOKIE, cookie.newExtractRule().extractorType());

        CorrelationSuggestion path = byLocation(suggestions, "Path segment 3").orElseThrow();
        assertEquals("userId", path.variableName());
        assertEquals(SuggestionConfidence.MEDIUM, path.confidence());
        CorrelationSuggestion json = byLocation(suggestions, "JSON userId").orElseThrow();
        assertEquals("userId", json.variableName(), "one value gets one variable across locations");

        assertTrue(byLocation(suggestions, "Query csrf").isPresent());
        assertTrue(byLocation(suggestions, "Cookie theme").isEmpty());

        CorrelationSuggestion echoed = byLocation(suggestions, "JSON note").orElseThrow();
        assertEquals(SuggestionConfidence.LOW, echoed.confidence(), "alice was typed in the login request");
        assertEquals(SuggestionConfidence.HIGH, suggestions.getFirst().confidence(), "sorted by confidence");
    }

    @Test
    void appliedSuggestionsReplayWithFreshValuesThroughTheRuntimeExtractorsAndInjectors() {
        FlowDefinition flow = flow(loginStep(List.of()), ordersStep(List.of()));
        List<CorrelationSuggestion> suggestions = service.suggest(flow, flow.steps().get(1)).stream()
                .filter(suggestion -> suggestion.confidence() != SuggestionConfidence.LOW)
                .toList();

        FlowDefinition applied = new CorrelationApplier().apply(flow, "orders", suggestions);

        String freshJwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJib2IifQ.bmV3LXNpZw";
        HttpMessageSnapshot freshLogin = RawHttpMessages.parse((LOGIN_RESPONSE
                .replace(JWT, freshJwt)
                .replace("s3ss10nValue99", "n3wSessi0n77")
                .replace("4821", "9999")
                .replace("c5rfT0kenValue", "fr3shCsrfTok")).getBytes(StandardCharsets.UTF_8)).orElseThrow();
        Map<String, String> variables = new LinkedHashMap<>();
        ExtractionService extraction = new ExtractionService();
        for (ExtractRule rule : applied.steps().getFirst().extractRules()) {
            ExtractionResult result = extraction.extract(rule, null, freshLogin);
            assertTrue(result.isSuccess(), rule.toString());
            variables.put(result.targetVariable(), result.value());
        }

        FlowStep orders = applied.steps().get(1);
        StoredRequestTemplate template = orders.requestTemplate();
        RequestMutationResult rendered = new InjectionService().apply(orders.injectRules(), variables,
                new InjectionService.MutableHttpRequest(
                        template.method() + " " + template.pathAndQuery() + " HTTP/1.1",
                        template.headerLines(), template.body()));

        assertEquals("POST /api/users/9999/orders?csrf=fr3shCsrfTok&page=2 HTTP/1.1", rendered.requestLine());
        assertTrue(rendered.headers().contains("Authorization: Bearer " + freshJwt), rendered.headers()::toString);
        assertTrue(rendered.headers().stream().anyMatch(h -> h.startsWith("Cookie:")
                && h.contains("sid=n3wSessi0n77") && h.contains("theme=dark")), rendered.headers()::toString);
        assertEquals("{\"userId\":9999,\"note\":\"alice\"}", rendered.body());
        assertFalse(rendered.diagnostics().stream().anyMatch(d -> d.contains("skipped")), rendered.diagnostics()::toString);
    }

    @Test
    void pathSegmentRuleInsertsRuntimeValuesLiterallyAndEncoded() {
        FlowDefinition flow = flow(loginStep(List.of()), ordersStep(List.of()));
        CorrelationSuggestion path = byLocation(service.suggest(flow, flow.steps().get(1)), "Path segment 3")
                .orElseThrow();
        StoredRequestTemplate template = flow.steps().get(1).requestTemplate();

        RequestMutationResult rendered = new InjectionService().applyRequired(List.of(path.injectRule()),
                Map.of("userId", "a$1\\b/c d"),
                new InjectionService.MutableHttpRequest(
                        template.method() + " " + template.pathAndQuery() + " HTTP/1.1",
                        template.headerLines(), template.body()));

        assertEquals("POST /api/users/a%241%5Cb%2Fc+d/orders?csrf=c5rfT0kenValue&page=2 HTTP/1.1",
                rendered.requestLine());
    }

    @Test
    void reusesExistingExtractorInsteadOfProposingADuplicate() {
        ExtractRule existing = new ExtractRule("x1", "token", true, ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.JSON_PATH, "access_token", "accessToken", List.of());
        FlowDefinition flow = flow(loginStep(List.of(existing)), ordersStep(List.of()));

        CorrelationSuggestion auth = byLocation(service.suggest(flow, flow.steps().get(1)), "Header Authorization")
                .orElseThrow();

        assertTrue(auth.reusesExistingExtractor());
        assertNull(auth.newExtractRule());
        assertEquals("Bearer {{accessToken}}", auth.injectRule().staticValue());
    }

    @Test
    void slotsAlreadyInjectedAreNotSuggestedAgain() {
        InjectRule present = new InjectRule("i1", "auth", true, InjectRule.Target.REQUEST_HEADER, "authorization",
                InjectRule.ValueSourceType.VARIABLE, "tok", "", InjectRule.WriteStrategy.OVERWRITE);
        FlowDefinition flow = flow(loginStep(List.of()), ordersStep(List.of(present)));

        assertTrue(byLocation(service.suggest(flow, flow.steps().get(1)), "Header Authorization").isEmpty());
    }

    @Test
    void disabledOrLaterStepsAreNotProducersAndFirstStepHasNoSuggestions() {
        FlowStep disabledLogin = loginStep(List.of()).withEnabled(false);
        FlowDefinition flow = flow(disabledLogin, ordersStep(List.of()));

        assertTrue(service.suggest(flow, flow.steps().get(1)).isEmpty());
        assertTrue(service.suggest(flow, flow.steps().getFirst()).isEmpty());
    }

    @Test
    void appliedFlowRejectsVariableCollisions() {
        FlowDefinition flow = flow(loginStep(List.of()), ordersStep(List.of()));
        CorrelationSuggestion auth = byLocation(service.suggest(flow, flow.steps().get(1)), "Header Authorization")
                .orElseThrow();
        FlowDefinition once = new CorrelationApplier().apply(flow, "orders", List.of(auth));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new CorrelationApplier().apply(once, "orders", List.of(auth)));
    }

    @Test
    void eligibilityFiltersAmbiguousValues() {
        assertFalse(ValueCorrelationService.eligible("12"));
        assertTrue(ValueCorrelationService.eligible("123"));
        assertFalse(ValueCorrelationService.eligible("true"));
        assertFalse(ValueCorrelationService.eligible("2026-01-01"));
        assertFalse(ValueCorrelationService.eligible("abcd"));
        assertFalse(ValueCorrelationService.eligible("has space"));
        assertTrue(ValueCorrelationService.eligible("abcde"));
        assertEquals("csrfToken", ValueCorrelationService.headerVariable("X-CSRF-Token"));
        assertEquals("requestId", ValueCorrelationService.headerVariable("X-Request-Id"));
    }

    private static Optional<CorrelationSuggestion> byLocation(List<CorrelationSuggestion> suggestions, String location) {
        return suggestions.stream().filter(suggestion -> suggestion.location().equals(location)).findFirst();
    }

    private static FlowStep loginStep(List<ExtractRule> extractRules) {
        return new FlowStep("login", "Login", true, "",
                new StoredRequestTemplate("POST", "https", "app.test", 443, "/login",
                        List.of("Host: app.test", "Content-Type: application/json"),
                        "{\"username\":\"alice\",\"password\":\"pw-secret\"}"),
                extractRules, List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW,
                Base64.getEncoder().encodeToString(LOGIN_RESPONSE.getBytes(StandardCharsets.UTF_8)));
    }

    private static FlowStep ordersStep(List<InjectRule> injectRules) {
        return new FlowStep("orders", "Orders", true, "",
                new StoredRequestTemplate("POST", "https", "app.test", 443,
                        "/api/users/4821/orders?csrf=c5rfT0kenValue&page=2",
                        List.of("Host: app.test",
                                "Authorization: Bearer " + JWT,
                                "Cookie: sid=s3ss10nValue99; theme=dark",
                                "Content-Type: application/json"),
                        "{\"userId\":4821,\"note\":\"alice\"}"),
                List.of(), injectRules, List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
    }

    private static FlowDefinition flow(FlowStep... steps) {
        return new FlowDefinition("flow-1", "Flow", "", true, List.of(steps), null, null, null);
    }
}
