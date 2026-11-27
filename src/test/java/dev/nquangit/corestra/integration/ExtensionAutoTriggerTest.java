package dev.nquangit.corestra.integration;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.*;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.http.sessions.ActionResult;
import burp.api.montoya.internal.*;
import dev.nquangit.corestra.flow.*;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.flow.rule.*;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static dev.nquangit.corestra.integration.ManualTestMessages.*;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("MontoyaObjectFactory")
class ExtensionAutoTriggerTest {
    private MontoyaObjectFactory previous;
    private final CorestraTrafficOrigin origin = new CorestraTrafficOrigin();
    private final ExecutorService callbacks = Executors.newCachedThreadPool();
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
            case "actionResult" -> proxy(ActionResult.class, (q, n, b) -> n.getName().equals("request") ? a[0] : null);
            default -> m.invoke(base, a);
        });
        origin.activate();
    }
    @AfterEach void close() throws Exception {
        try {
            if (handler != null) handler.close();
            origin.close(); callbacks.shutdownNow(); assertTrue(callbacks.awaitTermination(5, TimeUnit.SECONDS));
        } finally { ObjectFactoryLocator.FACTORY = previous; }
    }

    @Test void foreignExtensionRunsPrerequisiteAndDownstreamWithoutRecursingOnNativeSendsAcrossThreads() throws Exception {
        var ids = new AtomicInteger(100); var sends = new AtomicInteger();
        var done = new CountDownLatch(1); var downstreamToken = new AtomicReference<String>();
        var outgoing = new CopyOnWriteArrayList<HttpRequest>();
        var errors = new CopyOnWriteArrayList<String>();
        Http http = proxy(Http.class, (p, m, a) -> {
            if (!m.getName().equals("sendRequest")) throw new UnsupportedOperationException(m.getName());
            assertTrue(sends.incrementAndGet() <= 4, "Corestra recursively triggered its own flow");
            var marked = (HttpRequest) a[0];
            return callbacks.submit(() -> {
                assertTrue(origin.hasActiveMarker(marked));
                var before = live(ids.incrementAndGet(), marked, ToolType.EXTENSIONS);
                assertEquals(RequestAction.CONTINUE, handler.handleHttpRequestToBeSent(before).action(),
                        "Ownership must work even before the stripping handler runs");
                var clean = origin.handleHttpRequestToBeSent(before).request();
                assertFalse(clean.hasHeader(CorestraTrafficOrigin.HEADER));
                var callback = live(before.messageId(), clean, ToolType.EXTENSIONS);
                assertTrue(origin.owns(callback));
                var action = handler.handleHttpRequestToBeSent(callback);
                assertEquals(RequestAction.CONTINUE, action.action());
                assertEquals(0, handler.pendingTriggerCount(), "Internal callbacks must not register triggers");
                outgoing.add(action.request());
                if (clean.path().equals("/after")) {
                    downstreamToken.set(clean.headerValue("X-next")); done.countDown();
                }
                var response = response("HTTP/1.1 200 OK\r\nX-Value: prefix-token\r\n\r\n");
                handler.handleHttpResponseReceived(received(callback.messageId(), action.request(), response));
                return proxy(HttpRequestResponse.class, (q, n, b) -> n.getName().equals("response") ? response : null);
            }).get(5, TimeUnit.SECONDS);
        });
        var api = proxy(MontoyaApi.class, (p, m, a) -> m.getName().equals("http") ? http : null);
        var extraction = new ExtractionService(); var injection = new InjectionService();
        var engine = new FlowEngine(extraction, injection, new MontoyaHttpClient(api, origin));
        var prefix = step("prefix").withExtractRules(List.of(extract("token")));
        var target = step("target").withInjectRules(List.of(inject("token"))).withExtractRules(List.of(extract("next")))
                .withTriggerRule(new StepTriggerRule(true, List.of("EXTENSIONS"), List.of(), StepTriggerRule.ExecutionScope.FULL_FLOW, ""));
        var after = step("after").withInjectRules(List.of(inject("next")));
        var flow = new FlowDefinition("f", "Flow", "", true, List.of(prefix, target, after), null, null, null);
        handler = new AutoTriggerHttpHandler(() -> List.of(flow), engine, extraction, injection, null,
                new TriggerInputService(dev.nquangit.corestra.access.workspace.AccessWorkspace::empty, Clock.systemUTC()),
                errors::add, origin);

        var external = live(1, request(service("api.test", 443, true), "POST", "/target", List.of(), "external-payload"), ToolType.EXTENSIONS);
        var prepared = handler.handleHttpRequestToBeSent(external);
        assertEquals(RequestAction.CONTINUE, prepared.action());
        assertEquals("prefix-token", prepared.request().headerValue("X-token"));
        assertEquals("external-payload", prepared.request().bodyToString());
        assertEquals(1, handler.pendingTriggerCount());
        handler.handleHttpResponseReceived(received(1, prepared.request(), response("HTTP/1.1 200 OK\r\nX-Value: live-next\r\n\r\n")));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        var field = AutoTriggerHttpHandler.class.getDeclaredField("phase3Executor"); field.setAccessible(true);
        var downstream = (ExecutorService) field.get(handler); downstream.shutdown();
        assertTrue(downstream.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals("live-next", downstreamToken.get());
        assertEquals(2, sends.get());
        assertTrue(outgoing.stream().noneMatch(r -> r.hasHeader(CorestraTrafficOrigin.HEADER)));
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test void synchronousAggregatedDownstreamDoesNotRetriggerOnItsOwnNativeSends() throws Exception {
        var ids = new AtomicInteger(200); var sends = new AtomicInteger();
        var errors = new CopyOnWriteArrayList<String>();
        Http http = proxy(Http.class, (p, m, a) -> {
            if (!m.getName().equals("sendRequest")) throw new UnsupportedOperationException(m.getName());
            assertTrue(sends.incrementAndGet() <= 2, "Corestra recursively triggered its own flow");
            var marked = (HttpRequest) a[0];
            // Burp delivers internal-send callbacks on other threads while the live response
            // callback is blocked waiting for downstream steps.
            return callbacks.submit(() -> {
                var callback = live(ids.incrementAndGet(), origin.handleHttpRequestToBeSent(
                        live(ids.get(), marked, ToolType.EXTENSIONS)).request(), ToolType.EXTENSIONS);
                var action = handler.handleHttpRequestToBeSent(callback);
                assertEquals(0, handler.pendingTriggerCount(), "Internal callbacks must not register triggers");
                var response = response("HTTP/1.1 200 OK\r\nX-Value: internal\r\n\r\nbody" + callback.path());
                handler.handleHttpResponseReceived(received(callback.messageId(), action.request(), response));
                return proxy(HttpRequestResponse.class, (q, n, b) -> n.getName().equals("response") ? response : null);
            }).get(5, TimeUnit.SECONDS);
        });
        var api = proxy(MontoyaApi.class, (p, m, a) -> m.getName().equals("http") ? http : null);
        var extraction = new ExtractionService(); var injection = new InjectionService();
        var engine = new FlowEngine(extraction, injection, new MontoyaHttpClient(api, origin));
        var target = step("target").withIncludeInAggregation(true).withExtractRules(List.of(extract("next")))
                .withTriggerRule(new StepTriggerRule(true, List.of("REPEATER"), List.of(), StepTriggerRule.ExecutionScope.FULL_FLOW, ""));
        var flow = new FlowDefinition("f", "Flow", "", true, List.of(target,
                step("after").withIncludeInAggregation(true).withInjectRules(List.of(inject("next"))),
                step("last").withIncludeInAggregation(true)), null,
                new AggregationPlan(true, AggregationPlan.Mode.APPEND_RAW_RESPONSES, "", List.of(), true), null);
        handler = new AutoTriggerHttpHandler(() -> List.of(flow), engine, extraction, injection, null,
                new TriggerInputService(dev.nquangit.corestra.access.workspace.AccessWorkspace::empty, Clock.systemUTC()),
                errors::add, origin);

        var repeater = live(1, request(service("api.test", 443, true), "GET", "/target", List.of(), ""), ToolType.REPEATER);
        var prepared = handler.handleHttpRequestToBeSent(repeater).request();
        var liveResponse = response("HTTP/1.1 200 OK\r\nX-Value: live-next\r\n\r\nlive");
        var liveReceived = proxy(HttpResponseReceived.class, (p, m, a) -> switch (m.getName()) {
            case "messageId" -> 1; case "initiatingRequest" -> prepared;
            case "toolSource" -> proxy(ToolSource.class, (q, n, b) -> ToolType.REPEATER);
            case "withBody" -> response("HTTP/1.1 200 OK\r\n\r\n" + a[0]);
            default -> HttpResponse.class.getMethod(m.getName(), m.getParameterTypes()).invoke(liveResponse, a);
        });

        var body = handler.handleHttpResponseReceived(liveReceived).response().bodyToString();

        assertEquals("live\nbody/after\nbody/last\n", body);
        assertEquals(2, sends.get(), "Downstream ran inside the callback exactly once per step");
        assertEquals(0, handler.pendingTriggerCount());
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test void leasesReleaseOnErrorsAndIdenticalForeignRequestsAreNotMistakenForInternalTraffic() {
        var original = request(service("api.test", 443, true), "GET", "/same",
                List.of(header(CorestraTrafficOrigin.HEADER, "old"), header(CorestraTrafficOrigin.HEADER.toLowerCase(Locale.ROOT), "duplicate")), "");
        var seen = new AtomicReference<HttpRequest>(); var cleaned = new AtomicReference<HttpRequestToBeSent>();
        assertThrows(IllegalStateException.class, () -> origin.send(original, marked -> {
            seen.set(marked);
            assertEquals(1, marked.headers().stream().filter(header -> header.name().equalsIgnoreCase(CorestraTrafficOrigin.HEADER)).count());
            var callback = live(8, origin.handleHttpRequestToBeSent(live(8, marked, ToolType.EXTENSIONS)).request(), ToolType.EXTENSIONS);
            cleaned.set(callback);
            assertTrue(origin.owns(callback));
            assertFalse(origin.owns(live(9, original, ToolType.EXTENSIONS)));
            assertFalse(origin.hasActiveMarker(original.withAddedHeader(CorestraTrafficOrigin.HEADER, "forged")));
            assertFalse(origin.handleHttpRequestToBeSent(live(9, original.withAddedHeader(CorestraTrafficOrigin.HEADER, "forged"), ToolType.EXTENSIONS))
                    .request().hasHeader(CorestraTrafficOrigin.HEADER));
            throw new IllegalStateException("connection reset");
        }));
        assertFalse(origin.hasActiveMarker(seen.get()));
        assertFalse(origin.owns(cleaned.get()));
        origin.close();
        assertThrows(HttpTransportException.class, () -> origin.send(original, request -> fail("Must not send after unload")));
    }

    @Test void originGuardMustBeRegisteredBeforeItCanMarkTraffic() {
        try (var unregistered = new CorestraTrafficOrigin()) {
            assertThrows(HttpTransportException.class, () -> unregistered.send(
                    request(service("api.test", 443, true), "GET", "/", List.of(), ""), request -> fail("Must not send before registration")));
        }
    }

    @Test void legacyAllToolsDoNotOptIntoExtensionsAndExtenderAliasWorks() {
        assertFalse(new StepTriggerRule(true, List.of(), List.of()).matches("EXTENSIONS", "host", "/", List.of(), ""));
        assertTrue(new StepTriggerRule(true, List.of(), List.of()).matches("SCANNER", "host", "/", List.of(), ""));
        assertTrue(new StepTriggerRule(true, List.of("EXTENDER"), List.of()).matches("EXTENSIONS", "host", "/", List.of(), ""));
        assertFalse(new StepTriggerRule(true, List.of(StepTriggerRule.NO_TOOLS), List.of()).matches("SCANNER", "host", "/", List.of(), ""));
    }

    @Test void foreignExtensionCannotBypassRequiredInputsEvenWithoutProfileBindings() {
        var errors = new CopyOnWriteArrayList<String>();
        var target = step("target").withInjectRules(List.of(inject("missing"))).withTriggerRule(
                new StepTriggerRule(true, List.of("EXTENSIONS", "SCANNER"), List.of()));
        var flow = new FlowDefinition("f", "Flow", "", true, List.of(target), null, null, null);
        handler = new AutoTriggerHttpHandler(() -> List.of(flow),
                new FlowEngine(new ExtractionService(), new InjectionService(), request -> response("HTTP/1.1 200 OK\r\n\r\n")),
                new ExtractionService(), new InjectionService(), null,
                new TriggerInputService(dev.nquangit.corestra.access.workspace.AccessWorkspace::empty, Clock.systemUTC()),
                errors::add, origin);

        var extensionRequest = live(41, request(service("api.test", 443, true), "POST", "/target", List.of(), "payload"), ToolType.EXTENSIONS);
        var extensionAction = handler.handleHttpRequestToBeSent(extensionRequest);
        assertEquals(RequestAction.DROP, extensionAction.action());
        assertEquals(0, handler.pendingTriggerCount());
        assertTrue(errors.getFirst().contains("missing"), errors.toString());

        var scannerRequest = live(42, request(service("api.test", 443, true), "POST", "/target", List.of(), "payload"), ToolType.SCANNER);
        assertEquals(RequestAction.DROP, handler.handleHttpRequestToBeSent(scannerRequest).action());
    }

    @Test void fourStepFlowPreparesPostTargetsButLeavesMethodFlipRequestsOutsideItsScope() {
        var errors = new CopyOnWriteArrayList<String>();
        var sends = new CopyOnWriteArrayList<String>();
        var extraction = new ExtractionService(); var injection = new InjectionService();
        var first = step("first").withExtractRules(List.of(extract("cifId")));
        var second = step("second").withInjectRules(List.of(inject("cifId")));
        var jsonRule = new InjectRule("cif", "Inject cifId into cifId", true, InjectRule.Target.REQUEST_JSON_PATH,
                "cifId", InjectRule.ValueSourceType.VARIABLE, "cifId", "", InjectRule.WriteStrategy.OVERWRITE);
        var target = step("target").withInjectRules(List.of(jsonRule)).withTriggerRule(new StepTriggerRule(true,
                List.of("EXTENSIONS"), List.of(new StepTriggerRule.MatchCriterion(
                StepTriggerRule.MatchCriterion.MatchType.METHOD, "", "^POST$"))));
        var flow = new FlowDefinition("f", "Flow", "", true, List.of(first, second, target, step("after")), null, null, null);
        handler = new AutoTriggerHttpHandler(() -> List.of(flow), new FlowEngine(extraction, injection, request -> {
            sends.add(request.path());
            return response("HTTP/1.1 200 OK\r\nX-Value: fresh-cif\r\n\r\n");
        }), extraction, injection, null,
                new TriggerInputService(dev.nquangit.corestra.access.workspace.AccessWorkspace::empty, Clock.systemUTC()),
                errors::add, origin);

        var get = live(50, request(service("api.test", 443, true), "GET", "/target", List.of(), ""), ToolType.EXTENSIONS);
        assertSame(get, handler.handleHttpRequestToBeSent(get).request());
        assertTrue(sends.isEmpty(), "Method-filtered requests must not run prerequisites");

        for (int id : List.of(51, 52)) {
            var post = live(id, request(service("api.test", 443, true), "POST", "/target",
                    List.of(header("Content-Type", "application/json")), "{\"cifId\":\"old\",\"probe\":\"payload\"}"), ToolType.EXTENSIONS);
            var action = handler.handleHttpRequestToBeSent(post);
            assertEquals(RequestAction.CONTINUE, action.action());
            assertEquals("{\"cifId\":\"fresh-cif\",\"probe\":\"payload\"}", action.request().bodyToString());
            handler.handleHttpResponseReceived(received(id, action.request(), response("HTTP/1.1 200 OK\r\n\r\n")));
        }
        assertEquals(List.of("/first", "/second", "/first", "/second"), sends);
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(0, handler.pendingTriggerCount());

        var malformed = live(53, request(service("api.test", 443, true), "POST", "/target",
                List.of(header("Content-Type", "application/json")), "SENSITIVE_CREDENTIAL"), ToolType.EXTENSIONS);
        assertEquals(RequestAction.DROP, handler.handleHttpRequestToBeSent(malformed).action());
        assertEquals(0, handler.pendingTriggerCount());
        assertTrue(errors.getLast().contains("Invalid JSON body"), errors.toString());
        assertTrue(errors.getLast().contains("method=POST, contentType=application/json"), errors.toString());
        assertFalse(errors.getLast().contains("SENSITIVE_CREDENTIAL"), errors.toString());
    }

    @Test void nativeSessionActionIgnoresOwnSendEvenOnAnotherThread() throws Exception {
        var runs = new AtomicInteger();
        var flow = new FlowDefinition("login", "Login", "", true, List.of(), null, null,
                new FlowTriggerConfig(List.of(), List.of(), null, true, List.of()));
        var action = new CorestraSessionHandlingAction(() -> List.of(flow), ignored -> runs.incrementAndGet(),
                new SessionHandlingFlowMapper(), origin);
        var original = request(service("api.test", 443, true), "GET", "/login", List.of(), "");
        origin.send(original, marked -> {
            try {
                callbacks.submit(() -> action.performAction(proxy(burp.api.montoya.http.sessions.SessionHandlingActionData.class,
                        (p, m, a) -> m.getName().equals("request") ? marked : null))).get(5, TimeUnit.SECONDS);
            } catch (Exception failure) { throw new AssertionError(failure); }
            return "done";
        });
        assertEquals(0, runs.get());
        action.performAction(proxy(burp.api.montoya.http.sessions.SessionHandlingActionData.class,
                (p, m, a) -> m.getName().equals("request") ? original : null));
        assertEquals(1, runs.get());
    }

    private static FlowStep step(String id) {
        return new FlowStep(id, id, true, "", new StoredRequestTemplate("GET", "https", "api.test", 443, "/" + id, List.of(), ""),
                List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
    }
    private static ExtractRule extract(String key) {
        return new ExtractRule(key, key, true, ExtractRule.SourceSide.RESPONSE, ExtractRule.ExtractorType.HEADER, "X-Value", key, List.of());
    }
    private static InjectRule inject(String key) {
        return new InjectRule(key, key, true, InjectRule.Target.REQUEST_HEADER, "X-" + key, InjectRule.ValueSourceType.VARIABLE, key, "", null);
    }
    private static HttpRequestToBeSent live(int id, HttpRequest request, ToolType tool) {
        return proxy(HttpRequestToBeSent.class, (p, m, a) -> switch (m.getName()) {
            case "messageId" -> id;
            case "toolSource" -> proxy(ToolSource.class, (q, n, b) -> tool);
            default -> HttpRequest.class.getMethod(m.getName(), m.getParameterTypes()).invoke(request, a);
        });
    }
    private static HttpResponseReceived received(int id, HttpRequest request, HttpResponse response) {
        return proxy(HttpResponseReceived.class, (p, m, a) -> switch (m.getName()) {
            case "messageId" -> id; case "initiatingRequest" -> request;
            case "toolSource" -> proxy(ToolSource.class, (q, n, b) -> ToolType.EXTENSIONS);
            default -> HttpResponse.class.getMethod(m.getName(), m.getParameterTypes()).invoke(response, a);
        });
    }
}
