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
 * {@link AutoTriggerHttpHandler#requireSynchronousDownstream} makes one pending trigger finish its
 * downstream steps inside the response callback (for Watch AFTER captures) without changing the
 * live response.
 */
@ResourceLock("MontoyaObjectFactory")
class AutoTriggerSynchronousDownstreamTest {
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

    @Test void disabledAggregationRunsDownstreamBeforeCallbackReturnsWithoutChangingBody() {
        handler = handler(flow(AggregationPlan.disabled(), StepTriggerRule.ExecutionScope.FULL_FLOW, ""), defaultClient());

        HttpRequestToBeSent live = live(1);
        RequestToBeSentAction action = handler.handleHttpRequestToBeSent(live);
        var wait = handler.requireSynchronousDownstream(1, 5_000).orElseThrow();
        assertFalse(wait.finished());

        String body = handler.handleHttpResponseReceived(received(1, action.request(), LIVE)).response().bodyToString();

        assertEquals("live-body", body);
        assertEquals(List.of("/s2", "/s3", "/s4", "/s5"), sent, "downstream must finish inside the callback");
        assertEquals("live-token", tokenSeenByPath.get("/s2"), "live-response extraction still precedes downstream");
        assertTrue(wait.finished());
        assertTrue(wait.ran());
        assertTrue(wait.problem().isEmpty());
        assertEquals(0, handler.pendingTriggerCount());
    }

    @Test void nonContributingAggregationKeepsLiveBodyAndWaits() {
        handler = handler(flow(new AggregationPlan(true, AggregationPlan.Mode.APPEND_RAW_RESPONSES, "s5", List.of()),
                StepTriggerRule.ExecutionScope.FULL_FLOW, ""), defaultClient());

        String body = sendWatched(2, 5_000);

        assertEquals("live-body", body);
        assertEquals(4, sent.size());
    }

    @Test void timeoutIsReportedWithoutMarkerWhenAggregationDoesNotNeedDownstream() {
        CountDownLatch release = new CountDownLatch(1);
        handler = handler(flow(AggregationPlan.disabled(), StepTriggerRule.ExecutionScope.FULL_FLOW, ""), request -> {
            if (request.path().equals("/s3")) await(release);
            return defaultClient().apply(request);
        });
        try {
            HttpRequestToBeSent live = live(3);
            RequestToBeSentAction action = handler.handleHttpRequestToBeSent(live);
            var wait = handler.requireSynchronousDownstream(3, 200).orElseThrow();
            String body = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> handler.handleHttpResponseReceived(received(3, action.request(), LIVE)).response().bodyToString());
            assertEquals("live-body", body);
            assertTrue(wait.finished());
            assertTrue(wait.problem().orElseThrow().startsWith("timeout"), wait.problem().toString());
            assertTrue(wait.problem().orElseThrow().contains("200 ms"));
        } finally {
            release.countDown();
        }
    }

    @Test void downstreamFailureIsReported() {
        handler = handler(flow(AggregationPlan.disabled(), StepTriggerRule.ExecutionScope.FULL_FLOW, ""), request -> {
            if (request.path().equals("/s3")) throw new IllegalStateException("connection reset");
            return defaultClient().apply(request);
        });
        HttpRequestToBeSent live = live(4);
        RequestToBeSentAction action = handler.handleHttpRequestToBeSent(live);
        var wait = handler.requireSynchronousDownstream(4, 5_000).orElseThrow();

        assertEquals("live-body", handler.handleHttpResponseReceived(received(4, action.request(), LIVE)).response().bodyToString());

        assertTrue(wait.problem().orElseThrow().startsWith("failed"), wait.problem().toString());
        assertFalse(sent.contains("/s5"));
    }

    @Test void contributingAggregationUsesRequestedTimeoutAndKeepsMarker() {
        CountDownLatch release = new CountDownLatch(1);
        handler = handler(flow(new AggregationPlan(true, AggregationPlan.Mode.APPEND_RAW_RESPONSES, "", List.of(), true),
                StepTriggerRule.ExecutionScope.FULL_FLOW, ""), request -> {
            if (request.path().equals("/s3")) await(release);
            return defaultClient().apply(request);
        });
        try {
            String body = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> sendWatched(5, 150));
            assertTrue(body.startsWith("live-body\n"), body);
            assertTrue(body.contains("[Corestra] Downstream steps did not complete within 150 ms"), body);
        } finally {
            release.countDown();
        }
    }

    @Test void contributingAggregationOutputIsUnchangedByTheWait() {
        handler = handler(flow(new AggregationPlan(true, AggregationPlan.Mode.APPEND_RAW_RESPONSES, "", List.of(), true),
                StepTriggerRule.ExecutionScope.FULL_FLOW, ""), defaultClient());

        assertEquals("live-body\nbody-/s2\nbody-/s3\nbody-/s5\n", sendWatched(6, 5_000));
    }

    @Test void triggerWithoutDownstreamFinishesWithoutRunning() {
        handler = handler(flow(AggregationPlan.disabled(), StepTriggerRule.ExecutionScope.MATCHED_STEP, ""), defaultClient());
        HttpRequestToBeSent live = live(7);
        RequestToBeSentAction action = handler.handleHttpRequestToBeSent(live);
        var wait = handler.requireSynchronousDownstream(7, 5_000).orElseThrow();

        handler.handleHttpResponseReceived(received(7, action.request(), LIVE));

        assertTrue(wait.finished());
        assertFalse(wait.ran());
        assertTrue(wait.problem().isEmpty());
        assertTrue(sent.isEmpty());
    }

    @Test void unknownMessageOrClosedHandlerReturnsEmpty() {
        handler = handler(flow(AggregationPlan.disabled(), StepTriggerRule.ExecutionScope.FULL_FLOW, ""), defaultClient());
        assertTrue(handler.requireSynchronousDownstream(99, 1_000).isEmpty());
        handler.handleHttpRequestToBeSent(live(8));
        handler.close();
        assertTrue(handler.requireSynchronousDownstream(8, 1_000).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> handler.requireSynchronousDownstream(8, 0));
    }

    @Test void unwatchedTriggerStaysAsynchronous() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        handler = handler(flow(AggregationPlan.disabled(), StepTriggerRule.ExecutionScope.STOP_AT_STEP, "s2"), request -> {
            await(release);
            HttpResponse response = defaultClient().apply(request);
            done.countDown();
            return response;
        });
        HttpRequestToBeSent live = live(9);
        RequestToBeSentAction action = handler.handleHttpRequestToBeSent(live);
        String body = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> handler.handleHttpResponseReceived(received(9, action.request(), LIVE)).response().bodyToString());
        assertEquals("live-body", body);
        release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static final String LIVE = "HTTP/1.1 200 OK\r\nX-Value: live-token\r\n\r\nlive-body";

    private String sendWatched(int id, long timeout) {
        HttpRequestToBeSent live = live(id);
        RequestToBeSentAction action = handler.handleHttpRequestToBeSent(live);
        assertEquals(RequestAction.CONTINUE, action.action());
        assertTrue(handler.requireSynchronousDownstream(id, timeout).isPresent());
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
