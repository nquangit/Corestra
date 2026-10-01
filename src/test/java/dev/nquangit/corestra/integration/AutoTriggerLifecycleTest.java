package dev.nquangit.corestra.integration;

import burp.api.montoya.core.ToolSource;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
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
import dev.nquangit.corestra.flow.execution.TestFlowEngineFactory;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock("MontoyaObjectFactory")
class AutoTriggerLifecycleTest {
    private MontoyaObjectFactory previousFactory;
    private final AtomicInteger sends = new AtomicInteger();
    private final AtomicReference<List<FlowDefinition>> flows = new AtomicReference<>();

    @BeforeEach
    void installFactoryForHandlerActions() {
        previousFactory = ObjectFactoryLocator.FACTORY;
        ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p, m, a) -> switch (m.getName()) {
            case "httpRequest" -> request(-1, "/api", "");
            case "requestResult" -> proxy(RequestToBeSentAction.class, (q, n, b) ->
                    n.getName().equals("request") ? a[0] : null);
            case "responseResult" -> proxy(ResponseReceivedAction.class, (q, n, b) ->
                    n.getName().equals("response") ? a[0] : null);
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    @AfterEach
    void restoreFactory() {
        ObjectFactoryLocator.FACTORY = previousFactory;
    }

    @Test
    void concurrentSameEndpointResponsesKeepTheirOwnPrerequisites() {
        AutoTriggerHttpHandler handler = handler(true, false);
        HttpRequestToBeSent first = request(101, "/api", "first");
        HttpRequestToBeSent second = request(102, "/api", "second");
        handler.handleHttpRequestToBeSent(first);
        handler.handleHttpRequestToBeSent(second);

        String secondBody = handler.handleHttpResponseReceived(response(102, second, "live-2")).response().bodyToString();
        String firstBody = handler.handleHttpResponseReceived(response(101, first, "live-1")).response().bodyToString();

        assertEquals("prerequisite-2\nlive-2\n", secondBody);
        assertEquals("prerequisite-1\nlive-1\n", firstBody);
    }

    @Test
    void responseUsesCapturedMatchWhenRequestOrFlowChangesInFlight() {
        AutoTriggerHttpHandler handler = handler(true, false);
        handler.handleHttpRequestToBeSent(request(101, "/api", "original"));
        flows.set(List.of());
        String body = handler.handleHttpResponseReceived(
                response(101, request(101, "/rewritten", "mutated"), "live")).response().bodyToString();
        assertEquals("prerequisite-1\nlive\n", body);
    }

    @Test
    void responseWithoutMatchingRequestIsNeverAggregated() {
        AutoTriggerHttpHandler handler = handler(true, false);
        HttpResponseReceived response = response(999, request(999, "/api", ""), "live");
        assertSame(response, handler.handleHttpResponseReceived(response).response());
        assertEquals(0, sends.get());
    }

    @Test
    void disabledAggregationLeavesLiveResponseIntact() {
        AutoTriggerHttpHandler handler = handler(false, false);
        HttpRequestToBeSent request = request(101, "/api", "");
        handler.handleHttpRequestToBeSent(request);
        HttpResponseReceived response = response(101, request, "live");
        assertSame(response, handler.handleHttpResponseReceived(response).response());
        assertEquals(1, sends.get());
    }

    @Test
    void failedPrerequisiteDoesNotLeavePendingAutomationForLiveResponse() {
        AutoTriggerHttpHandler handler = handler(true, true);
        HttpRequestToBeSent request = request(101, "/api", "");
        assertSame(request, handler.handleHttpRequestToBeSent(request).request());
        HttpResponseReceived response = response(101, request, "live");
        assertSame(response, handler.handleHttpResponseReceived(response).response());
        assertEquals(1, sends.get());
    }

    @Test
    void aggregationConfiguredForAnotherStepDoesNotRewriteTriggeredResponse() {
        AutoTriggerHttpHandler handler = handler(true, false);
        FlowDefinition original = flows.get().getFirst();
        flows.set(List.of(new FlowDefinition(original.id(), original.name(), original.description(), true,
                original.steps(), original.rules(), new AggregationPlan(true,
                AggregationPlan.Mode.APPEND_RAW_RESPONSES, "prerequisite", List.of()), original.triggerConfig())));
        HttpRequestToBeSent request = request(101, "/api", "");
        handler.handleHttpRequestToBeSent(request);
        HttpResponseReceived response = response(101, request, "live");
        assertSame(response, handler.handleHttpResponseReceived(response).response());
    }

    @Test
    void requestPreparationFailureDoesNotPublishPendingContext() {
        AutoTriggerHttpHandler handler = handler(true, false);
        FlowDefinition flow = flows.get().getFirst();
        FlowStep trigger = flow.steps().get(1).withInjectRules(List.of(new InjectRule(
                "inject", "Inject", true, InjectRule.Target.REQUEST_HEADER, "X-Test",
                InjectRule.ValueSourceType.STATIC, "", "value", InjectRule.WriteStrategy.OVERWRITE)));
        flows.set(List.of(new FlowDefinition(
                flow.id(), flow.name(), flow.description(), flow.enabled(),
                List.of(flow.steps().getFirst(), trigger), flow.rules(), flow.aggregation(), flow.triggerConfig())));
        HttpRequestToBeSent request = request(101, "/api", "");
        assertSame(request, handler.handleHttpRequestToBeSent(request).request());
        HttpResponseReceived response = response(101, request, "live");
        assertSame(response, handler.handleHttpResponseReceived(response).response());
    }

    @Test
    void pendingTriggersAreBoundedExpireAndClearOnClose() {
        MutableClock clock = new MutableClock(1_000L);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AutoTriggerHttpHandler handler = handler(true, false, clock, executor, 2, 100L);

        handler.handleHttpRequestToBeSent(request(101, "/api", "one"));
        handler.handleHttpRequestToBeSent(request(102, "/api", "two"));
        handler.handleHttpRequestToBeSent(request(103, "/api", "three"));
        assertEquals(2, handler.pendingTriggerCount());

        clock.advanceMillis(101L);
        assertEquals(0, handler.pendingTriggerCount());

        handler.handleHttpRequestToBeSent(request(104, "/api", "four"));
        handler.close();
        assertEquals(0, handler.pendingTriggerCount());
        assertTrue(executor.isShutdown());
    }

    private AutoTriggerHttpHandler handler(boolean aggregate, boolean fail) {
        configureFlows(aggregate);
        return new AutoTriggerHttpHandler(flows::get, flowEngine(fail),
                new ExtractionService(), new InjectionService());
    }

    private AutoTriggerHttpHandler handler(
            boolean aggregate,
            boolean fail,
            Clock clock,
            ExecutorService executor,
            int maxPending,
            long ttlMillis) {
        configureFlows(aggregate);
        return new AutoTriggerHttpHandler(flows::get, flowEngine(fail),
                new ExtractionService(), new InjectionService(), clock, executor, maxPending, ttlMillis);
    }

    private void configureFlows(boolean aggregate) {
        FlowStep prerequisite = step("prerequisite").withIncludeInAggregation(true);
        FlowStep trigger = step("trigger").withIncludeInAggregation(true).withTriggerRule(
                new StepTriggerRule(true, List.of("SCANNER"), List.of()));
        flows.set(List.of(new FlowDefinition("flow", "Flow", "", true, List.of(prerequisite, trigger),
                null, aggregate ? new AggregationPlan(true, AggregationPlan.Mode.APPEND_RAW_RESPONSES,
                "", List.of(), true) : AggregationPlan.disabled(), null)));
    }

    private dev.nquangit.corestra.flow.execution.FlowEngine flowEngine(boolean fail) {
        return TestFlowEngineFactory.withHttpClient(request -> {
            int number = sends.incrementAndGet();
            if (fail) throw new IllegalStateException("Prerequisite transport failed");
            return response(0, request, "prerequisite-" + number);
        });
    }

    private static FlowStep step(String id) {
        return new FlowStep(id, id, true, "", new StoredRequestTemplate("GET", "https", "example.com",
                443, "/api", List.of(), ""), List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
    }

    private static HttpRequestToBeSent request(int id, String path, String body) {
        return proxy(HttpRequestToBeSent.class, (p, m, a) -> switch (m.getName()) {
            case "messageId" -> id;
            case "toolSource" -> proxy(ToolSource.class, (q, n, b) -> ToolType.SCANNER);
            case "httpService" -> proxy(HttpService.class, (q, n, b) -> switch (n.getName()) {
                case "host" -> "example.com";
                case "port" -> 443;
                case "secure" -> true;
                default -> throw new UnsupportedOperationException(n.getName());
            });
            case "method" -> "GET";
            case "path" -> path;
            case "httpVersion" -> "HTTP/1.1";
            case "bodyToString" -> body;
            case "headers" -> List.of();
            case "hasHeader" -> false;
            case "toString" -> "GET " + path + " HTTP/1.1\r\n\r\n" + body;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private static HttpResponseReceived response(int id, HttpRequest initiating, String body) {
        return proxy(HttpResponseReceived.class, (p, m, a) -> switch (m.getName()) {
            case "messageId" -> id;
            case "toolSource" -> proxy(ToolSource.class, (q, n, b) -> ToolType.SCANNER);
            case "initiatingRequest" -> initiating;
            case "statusCode" -> (short) 200;
            case "httpVersion" -> "HTTP/1.1";
            case "reasonPhrase" -> "OK";
            case "bodyToString" -> body;
            case "headers" -> List.of();
            case "withBody" -> response(id, initiating, (String) a[0]);
            case "toString" -> "HTTP/1.1 200 OK\r\n\r\n" + body;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class MutableClock extends Clock {
        private long epochMillis;

        private MutableClock(long epochMillis) {
            this.epochMillis = epochMillis;
        }

        private void advanceMillis(long millis) {
            epochMillis += millis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(epochMillis);
        }

        @Override
        public long millis() {
            return epochMillis;
        }
    }
}
