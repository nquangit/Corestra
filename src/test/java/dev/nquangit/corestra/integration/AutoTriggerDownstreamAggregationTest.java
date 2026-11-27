package dev.nquangit.corestra.integration;

import burp.api.montoya.core.ToolSource;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestAction;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StepTriggerRule;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static dev.nquangit.corestra.integration.ManualTestMessages.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A live request that triggers the first step of a multi-step flow must receive an aggregated
 * response that includes the downstream steps (run after live-response extraction) when the
 * flow's aggregation targets the triggered step.
 */
@ResourceLock("MontoyaObjectFactory")
class AutoTriggerDownstreamAggregationTest {
    private MontoyaObjectFactory previous;
    private final List<String> sent = new CopyOnWriteArrayList<>();
    private final Map<String, String> tokenSeenByPath = new ConcurrentHashMap<>();
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private AutoTriggerHttpHandler handler;

    @BeforeEach void setup() {
        previous = ObjectFactoryLocator.FACTORY;
        var base = ManualTestMessages.factory();
        ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p, m, a) -> switch (m.getName()) {
            case "requestResult" -> proxy(RequestToBeSentAction.class, (q, n, b) -> switch (n.getName()) {
                case "request" -> a.length == 4 ? a[1] : a[0];
                case "action" -> a.length == 4 ? a[0] : RequestAction.CONTINUE;
                default -> null;
            });
            case "responseResult" -> proxy(ResponseReceivedAction.class, (q, n, b) -> n.getName().equals("response") ? a[0] : null);
            default -> m.invoke(base, a);
        });
    }

    @AfterEach void tearDown() {
        try { if (handler != null) handler.close(); }
        finally { ObjectFactoryLocator.FACTORY = previous; }
    }

    @Test void triggerOnFirstStepAggregatesIncludedDownstreamStepsAfterLiveExtraction() {
        handler = handler(flow(new AggregationPlan(true, AggregationPlan.Mode.APPEND_NORMALIZED_SUMMARIES, "", List.of(), true),
                StepTriggerRule.ExecutionScope.FULL_FLOW, ""), defaultClient());

        String body = sendLive(1);

        assertEquals(List.of("/s2", "/s3", "/s4", "/s5"), sent, "downstream must run before the callback returns");
        assertEquals("live-token", tokenSeenByPath.get("/s2"), "downstream must see the live-response extraction");
        int s1 = body.indexOf("[Step: s1] Status: 200\nlive-body");
        int s2 = body.indexOf("[Step: s2] Status: 200\nbody-/s2");
        int s3 = body.indexOf("[Step: s3] Status: 200\nbody-/s3");
        int s5 = body.indexOf("[Step: s5] Status: 200\nbody-/s5");
        assertTrue(s1 >= 0 && s2 > s1 && s3 > s2 && s5 > s3, body);
        assertFalse(body.contains("body-/s4"), "steps excluded from aggregation must not appear: " + body);
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test void explicitFinalStepEqualToTriggeredStepAlsoIncludesDownstreamInRawMode() {
        handler = handler(flow(new AggregationPlan(true, AggregationPlan.Mode.APPEND_RAW_RESPONSES, "s1", List.of()),
                StepTriggerRule.ExecutionScope.FULL_FLOW, ""), defaultClient());

        assertEquals("live-body\nbody-/s2\nbody-/s3\nbody-/s5\n", sendLive(2));
    }

    @Test void stopAtStepBoundsDownstreamAggregation() {
        handler = handler(flow(new AggregationPlan(true, AggregationPlan.Mode.APPEND_RAW_RESPONSES, "", List.of(), true),
                StepTriggerRule.ExecutionScope.STOP_AT_STEP, "s3"), defaultClient());

        assertEquals("live-body\nbody-/s2\nbody-/s3\n", sendLive(3));
        assertEquals(List.of("/s2", "/s3"), sent);
    }

    @Test void finalStepDownstreamOfTriggerDoesNotRewriteLiveResponse() throws Exception {
        CountDownLatch downstreamDone = new CountDownLatch(4);
        handler = handler(flow(new AggregationPlan(true, AggregationPlan.Mode.APPEND_RAW_RESPONSES, "s5", List.of()),
                StepTriggerRule.ExecutionScope.FULL_FLOW, ""), request -> {
            HttpResponse response = defaultClient().apply(request);
            downstreamDone.countDown();
            return response;
        });

        assertEquals("live-body", sendLive(4));
        assertTrue(downstreamDone.await(5, TimeUnit.SECONDS), "downstream still runs asynchronously");
    }

    @Test void disabledAggregationKeepsDownstreamAsynchronous() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch downstreamDone = new CountDownLatch(1);
        handler = handler(flow(AggregationPlan.disabled(), StepTriggerRule.ExecutionScope.STOP_AT_STEP, "s2"), request -> {
            await(release);
            HttpResponse response = defaultClient().apply(request);
            downstreamDone.countDown();
            return response;
        });

        String body = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> sendLive(5));
        assertEquals("live-body", body);
        release.countDown();
        assertTrue(downstreamDone.await(5, TimeUnit.SECONDS));
        assertEquals("live-token", tokenSeenByPath.get("/s2"));
    }

    @Test void downstreamTimeoutReturnsPartialAggregationWithMarker() {
        CountDownLatch release = new CountDownLatch(1);
        handler = handler(flow(new AggregationPlan(true, AggregationPlan.Mode.APPEND_RAW_RESPONSES, "", List.of(), true),
                StepTriggerRule.ExecutionScope.FULL_FLOW, ""), request -> {
            if (request.path().equals("/s3")) await(release);
            return defaultClient().apply(request);
        });
        handler.syncDownstreamTimeoutMillis(200L);

        try {
            String body = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> sendLive(6));
            assertTrue(body.startsWith("live-body\n"), body);
            assertTrue(body.contains("[Corestra] Downstream steps did not complete within 200 ms"), body);
            assertFalse(body.contains("body-/s5"), body);
            assertTrue(errors.stream().anyMatch(e -> e.contains("did not complete")), errors.toString());
        } finally {
            release.countDown();
        }
    }

    @Test void downstreamFailureKeepsCompletedStepsAndAddsMarker() {
        handler = handler(flow(new AggregationPlan(true, AggregationPlan.Mode.APPEND_RAW_RESPONSES, "", List.of(), true),
                StepTriggerRule.ExecutionScope.FULL_FLOW, ""), request -> {
            if (request.path().equals("/s3")) throw new IllegalStateException("connection reset");
            return defaultClient().apply(request);
        });

        String body = sendLive(7);

        assertTrue(body.startsWith("live-body\nbody-/s2\n"), body);
        assertTrue(body.contains("[Corestra] Downstream steps failed"), body);
        assertFalse(body.contains("body-/s5"), body);
        assertFalse(errors.isEmpty());
    }

    @Test void closedHandlerDuringSubmitReturnsPartialAggregationWithoutThrowing() {
        handler = handler(flow(new AggregationPlan(true, AggregationPlan.Mode.APPEND_RAW_RESPONSES, "", List.of(), true),
                StepTriggerRule.ExecutionScope.FULL_FLOW, ""), defaultClient());
        HttpRequestToBeSent live = live(8);
        HttpRequest prepared = handler.handleHttpRequestToBeSent(live).request();
        // Simulate unload racing the response callback: the executor rejects work but the
        // pending trigger was captured before close.
        try {
            var field = AutoTriggerHttpHandler.class.getDeclaredField("phase3Executor");
            field.setAccessible(true);
            ((java.util.concurrent.ExecutorService) field.get(handler)).shutdownNow();
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }

        String body = handler.handleHttpResponseReceived(received(8, prepared, LIVE)).response().bodyToString();

        assertTrue(body.startsWith("live-body\n"), body);
        assertTrue(body.contains("[Corestra] Downstream steps were cancelled"), body);
        assertTrue(sent.isEmpty());
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static final String LIVE = "HTTP/1.1 200 OK\r\nX-Value: live-token\r\n\r\nlive-body";

    private String sendLive(int id) {
        HttpRequestToBeSent live = live(id);
        RequestToBeSentAction action = handler.handleHttpRequestToBeSent(live);
        assertEquals(RequestAction.CONTINUE, action.action());
        assertEquals(1, handler.pendingTriggerCount());
        return handler.handleHttpResponseReceived(received(id, action.request(), LIVE)).response().bodyToString();
    }

    private Function<HttpRequest, HttpResponse> defaultClient() {
        return request -> {
            sent.add(request.path());
            String token = request.headerValue("X-token");
            if (token != null) tokenSeenByPath.put(request.path(), token);
            return response("HTTP/1.1 200 OK\r\nX-Value: from-" + request.path() + "\r\n\r\nbody-" + request.path());
        };
    }

    private AutoTriggerHttpHandler handler(FlowDefinition flow, Function<HttpRequest, HttpResponse> client) {
        var extraction = new ExtractionService();
        var injection = new InjectionService();
        return new AutoTriggerHttpHandler(() -> List.of(flow), new FlowEngine(extraction, injection, client::apply),
                extraction, injection, null,
                new TriggerInputService(dev.nquangit.corestra.access.workspace.AccessWorkspace::empty, Clock.systemUTC()),
                errors::add);
    }

    private static FlowDefinition flow(AggregationPlan plan, StepTriggerRule.ExecutionScope scope, String stopAt) {
        List<FlowStep> steps = new ArrayList<>();
        steps.add(step("s1").withIncludeInAggregation(true)
                .withExtractRules(List.of(new ExtractRule("token", "token", true, ExtractRule.SourceSide.RESPONSE,
                        ExtractRule.ExtractorType.HEADER, "X-Value", "token", List.of())))
                .withTriggerRule(new StepTriggerRule(true, List.of("REPEATER"), List.of(), scope, stopAt)));
        steps.add(step("s2").withIncludeInAggregation(true).withInjectRules(List.of(new InjectRule("inj", "inj", true,
                InjectRule.Target.REQUEST_HEADER, "X-token", InjectRule.ValueSourceType.VARIABLE, "token", "", null))));
        steps.add(step("s3").withIncludeInAggregation(true));
        steps.add(step("s4").withIncludeInAggregation(false));
        steps.add(step("s5").withIncludeInAggregation(true));
        return new FlowDefinition("f", "Flow", "", true, steps, null, plan, null);
    }

    private static FlowStep step(String id) {
        return new FlowStep(id, id, true, "", new StoredRequestTemplate("GET", "https", "api.test", 443, "/" + id, List.of(), ""),
                List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
    }

    private static HttpRequestToBeSent live(int id) {
        HttpRequest request = request(service("api.test", 443, true), "GET", "/s1", List.of(), "");
        return proxy(HttpRequestToBeSent.class, (p, m, a) -> switch (m.getName()) {
            case "messageId" -> id;
            case "toolSource" -> proxy(ToolSource.class, (q, n, b) -> ToolType.REPEATER);
            default -> HttpRequest.class.getMethod(m.getName(), m.getParameterTypes()).invoke(request, a);
        });
    }

    private static HttpResponseReceived received(int id, HttpRequest request, String raw) {
        HttpResponse response = response(raw);
        return proxy(HttpResponseReceived.class, (p, m, a) -> switch (m.getName()) {
            case "messageId" -> id;
            case "initiatingRequest" -> request;
            case "toolSource" -> proxy(ToolSource.class, (q, n, b) -> ToolType.REPEATER);
            case "withBody" -> received(id, request, raw.substring(0, raw.indexOf("\r\n\r\n") + 4) + a[0]);
            default -> HttpResponse.class.getMethod(m.getName(), m.getParameterTypes()).invoke(response, a);
        });
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(10, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
