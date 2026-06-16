package dev.nquangit.corestra.integration;

import burp.api.montoya.http.message.requests.HttpRequest;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.execution.ExecutionContext;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;
import dev.nquangit.corestra.http.inject.InjectionService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AutoTriggerHttpHandler}.
 */
class AutoTriggerHttpHandlerTest {

    // ── Existing smoke tests ──────────────────────────────────────────────────

    @Test
    void classIsLoadable() {
        assertTrue(AutoTriggerHttpHandler.class.isInterface() || !AutoTriggerHttpHandler.class.isInterface());
    }

    @Test
    void proxyPipelineMarkerIsDetected() {
        assertTrue(AutoTriggerHttpHandler.hasProxyPipelineMarker(requestWithMarker(true)));
        assertFalse(AutoTriggerHttpHandler.hasProxyPipelineMarker(requestWithMarker(false)));
    }

    // ── applyExtractRulesToContext ────────────────────────────────────────────

    @Test
    void applyExtractRulesToContext_responseSideBodyText_populatesVariable() {
        AutoTriggerHttpHandler handler = handler();

        ExtractRule rule = new ExtractRule(
                "rule-1", "Capture body", true,
                ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.BODY_TEXT,
                null,
                "capturedBody",
                List.of());

        FlowStep step = stepWithExtractRules(List.of(rule));
        HttpMessageSnapshot requestSnap = new HttpMessageSnapshot("GET / HTTP/1.1", List.of(), "");
        HttpMessageSnapshot responseSnap = new HttpMessageSnapshot(
                "HTTP/1.1 200 OK", List.of(), "hello-world");
        ExecutionContext ctx = ExecutionContext.start("run-1", "flow-1", Map.of(), 1000L);

        ExecutionContext result = handler.applyExtractRulesToContext(step, requestSnap, responseSnap, ctx);

        assertEquals("hello-world", result.variables().get("capturedBody"));
    }

    @Test
    void applyExtractRulesToContext_requestSideHeader_populatesVariable() {
        AutoTriggerHttpHandler handler = handler();

        ExtractRule rule = new ExtractRule(
                "rule-2", "Capture auth header", true,
                ExtractRule.SourceSide.REQUEST,
                ExtractRule.ExtractorType.HEADER,
                "X-Auth-Token",
                "authToken",
                List.of());

        FlowStep step = stepWithExtractRules(List.of(rule));
        HttpMessageSnapshot requestSnap = new HttpMessageSnapshot(
                "POST /api HTTP/1.1",
                List.of("X-Auth-Token: abc123", "Content-Type: application/json"),
                "{\"a\":1}");
        HttpMessageSnapshot responseSnap = new HttpMessageSnapshot("HTTP/1.1 200 OK", List.of(), "");
        ExecutionContext ctx = ExecutionContext.start("run-1", "flow-1", Map.of(), 1000L);

        ExecutionContext result = handler.applyExtractRulesToContext(step, requestSnap, responseSnap, ctx);

        assertEquals("abc123", result.variables().get("authToken"));
    }

    @Test
    void applyExtractRulesToContext_failedExtractionLeavesNoVariable() {
        AutoTriggerHttpHandler handler = handler();

        ExtractRule rule = new ExtractRule(
                "rule-3", "Missing header", true,
                ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.HEADER,
                "X-Does-Not-Exist",
                "missingVar",
                List.of());

        FlowStep step = stepWithExtractRules(List.of(rule));
        HttpMessageSnapshot requestSnap = new HttpMessageSnapshot("GET / HTTP/1.1", List.of(), "");
        HttpMessageSnapshot responseSnap = new HttpMessageSnapshot(
                "HTTP/1.1 200 OK", List.of("Content-Type: text/plain"), "body");
        ExecutionContext ctx = ExecutionContext.start("run-1", "flow-1", Map.of(), 1000L);

        ExecutionContext result = handler.applyExtractRulesToContext(step, requestSnap, responseSnap, ctx);

        assertNull(result.variables().get("missingVar"),
                "Variable must not be set when extraction fails");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static AutoTriggerHttpHandler handler() {
        FlowEngine fakeEngine = new FlowEngine(
                new ExtractionService(), new InjectionService(), req -> null);
        return new AutoTriggerHttpHandler(
                List::of, fakeEngine, new ExtractionService(), new InjectionService());
    }

    private static FlowStep stepWithExtractRules(List<ExtractRule> rules) {
        StoredRequestTemplate template = new StoredRequestTemplate(
                "GET", "https", "example.com", 443, "/", List.of("Host: example.com"), "");
        return new FlowStep(
                "step-1", "Step One", true, "",
                template, rules, List.of(), List.of(),
                FlowStep.StepErrorStrategy.FAIL_FLOW);
    }

    private static HttpRequest requestWithMarker(boolean present) {
        return (HttpRequest) Proxy.newProxyInstance(
                HttpRequest.class.getClassLoader(),
                new Class<?>[]{HttpRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasHeader" -> present
                            && ProxyPipelineHttpClient.MARKER_HEADER.equals(args[0]);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
