package dev.nquangit.corestra.integration;

import burp.api.montoya.core.ToolSource;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.flow.*;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static dev.nquangit.corestra.integration.ManualTestMessages.*;
import static dev.nquangit.corestra.integration.TriggerInputServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("MontoyaObjectFactory")
class AutoTriggerProfileInputsTest {
    private MontoyaObjectFactory previous;
    private final InMemoryAccessRepository profiles = new InMemoryAccessRepository();
    private final List<HttpRequest> sent = new CopyOnWriteArrayList<>();
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private AutoTriggerHttpHandler handler;

    @BeforeEach void setup() {
        previous = ObjectFactoryLocator.FACTORY;
        var factory = ManualTestMessages.factory();
        ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p, m, a) -> switch (m.getName()) {
            case "requestResult" -> proxy(RequestToBeSentAction.class, (q, n, b) -> switch (n.getName()) {
                case "request" -> a.length == 4 ? a[1] : a[0];
                case "action" -> a.length == 4 ? a[0] : RequestAction.CONTINUE;
                default -> null;
            });
            case "responseResult" -> proxy(ResponseReceivedAction.class, (q, n, b) -> n.getName().equals("response") ? a[0] : null);
            default -> m.invoke(factory, a);
        });
        profiles.saveAuthProfile(actor("alice"));
        profiles.saveDataProfile(DataProfile.enabled("data", "Data", "unrelated-owner", Map.of("id", "42")));
    }

    @AfterEach void close() {
        if (handler != null) handler.close();
        ObjectFactoryLocator.FACTORY = previous;
    }

    @Test void sharedFourStepFlowUsesSelectedProfilesAndKeepsAllOutputsLocal() throws Exception {
        var login = step("login", "credentials.username").withExtractRules(List.of(extract("token", ExtractRule.SourceSide.RESPONSE)));
        var setup = step("setup", "auth.token").withExtractRules(List.of(extract("csrf", ExtractRule.SourceSide.RESPONSE)));
        var target = triggered(step("target", "auth.token", "csrf", "data.id")
                .withExtractRules(List.of(extract("next", ExtractRule.SourceSide.RESPONSE))), new TriggerInputBindings("actor", "data"), true);
        var downstream = step("downstream", "auth.next");
        FlowDefinition flow = flow(login, setup, target, downstream);
        var done = new CountDownLatch(1);
        wire(flow, request -> {
            if (request.path().equals("/downstream")) done.countDown();
            return response("HTTP/1.1 200 OK\r\nX-Value: " + request.path().substring(1) + "-output\r\n\r\n");
        });
        HttpRequestToBeSent live = live(1, "Scanner payload");
        var action = handler.handleHttpRequestToBeSent(live);
        assertEquals(RequestAction.CONTINUE, action.action());
        assertEquals(List.of("/login", "/setup"), sent.stream().map(HttpRequest::path).toList());
        assertEquals("alice", sent.getFirst().headerValue("X-credentials.username"));
        assertEquals("login-output", sent.get(1).headerValue("X-auth.token"));
        assertEquals("login-output", action.request().headerValue("X-auth.token"));
        assertEquals("setup-output", action.request().headerValue("X-csrf"));
        assertEquals("42", action.request().headerValue("X-data.id"));
        assertEquals("Scanner payload", action.request().bodyToString());
        handler.handleHttpResponseReceived(received(1, action.request(), "HTTP/1.1 200 OK\r\nX-Value: final-output\r\n\r\n"));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("final-output", sent.getLast().headerValue("X-auth.next"));
        assertEquals("alice-state", profiles.authProfile("actor").orElseThrow().state().variables().get("token"));
        assertEquals("", target.requestTemplate().body());
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test void bothNoneStillRunStaticFlowsAndUseTheLiveRequestUnchanged() {
        wire(flow(triggered(step("target"), TriggerInputBindings.none(), false)), r -> response("ok"));
        var live = live(1, "payload");
        assertSame(live, handler.handleHttpRequestToBeSent(live).request());
        assertEquals(0, sent.size());
        assertEquals(1, handler.pendingTriggerCount());
    }

    @Test void missingSelectedProfileOrMissingVariableStopsBeforeAnyPrerequisiteCanSend() {
        for (var bindings : List.of(TriggerInputBindings.none(), new TriggerInputBindings("actor", ""),
                new TriggerInputBindings("deleted", "data"))) {
            if (handler != null) handler.close();
            wire(flow(step("login"), triggered(step("target", "data.id"), bindings, false)), r -> response("ok"));
            assertEquals(RequestAction.DROP, handler.handleHttpRequestToBeSent(live(1, "payload")).action());
            assertEquals(0, sent.size());
            assertEquals(0, handler.pendingTriggerCount());
            assertFalse(errors.isEmpty());
        }
    }

    @Test void failedRuntimeExtractionCannotFallBackToCapturedOrLiveAuth() {
        var first = step("login").withExtractRules(List.of(extract("freshToken", ExtractRule.SourceSide.RESPONSE)));
        wire(flow(first, triggered(step("target", "auth.freshToken"), TriggerInputBindings.none(), false)), r -> response("no header"));
        var action = handler.handleHttpRequestToBeSent(live(1, "stale captured data"));
        assertEquals(RequestAction.DROP, action.action());
        assertEquals(1, sent.size());
        assertEquals(0, handler.pendingTriggerCount());
        assertTrue(errors.getLast().contains("auth.freshToken"));
    }

    @Test void failedPrerequisiteInjectionCannotContinueEvenWithContinueStepPolicy() {
        var first = step("login").withExtractRules(List.of(extract("freshToken", ExtractRule.SourceSide.RESPONSE)));
        var template = step("setup", "freshToken");
        var second = new FlowStep(template.id(), template.name(), true, "", template.requestTemplate(), List.of(),
                template.injectRules(), List.of(), FlowStep.StepErrorStrategy.CONTINUE_FLOW);
        wire(flow(first, second, triggered(step("target"), TriggerInputBindings.none(), false)), r -> response("no header"));
        assertEquals(RequestAction.DROP, handler.handleHttpRequestToBeSent(live(1, "payload")).action());
        assertEquals(1, sent.size());
        assertEquals(0, handler.pendingTriggerCount());
    }

    @Test void missingLiveResponseOutputStopsDownstreamWithoutSendingItsSnapshot() throws Exception {
        var target = triggered(step("target").withExtractRules(List.of(extract("next", ExtractRule.SourceSide.RESPONSE))), TriggerInputBindings.none(), true);
        wire(flow(target, step("downstream", "next")), r -> response("ok"));
        var live = live(1, "payload");
        handler.handleHttpRequestToBeSent(live);
        handler.handleHttpResponseReceived(received(1, live, "HTTP/1.1 200 OK\r\n\r\nno required header"));
        awaitErrors();
        assertEquals(0, sent.size());
        assertTrue(errors.getLast().contains("next"));
    }

    @Test void responseOnlyExtractionWaitsForResponseAndProfilesAreSnapshotsAcrossInterleavedRuns() throws Exception {
        var bodyExtract = new ExtractRule("body", "Body token", true, ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.BODY_TEXT, "", "token", List.of());
        var target = triggered(step("target", "auth.token", "data.id").withExtractRules(List.of(bodyExtract)),
                new TriggerInputBindings("actor", "data"), true);
        var done = new CountDownLatch(2);
        wire(flow(target, step("downstream", "auth.token", "data.id")), r -> { done.countDown(); return response("ok"); });
        var first = handler.handleHttpRequestToBeSent(live(1, "payload")).request();
        assertEquals("alice-state", first.headerValue("X-auth.token"));
        profiles.saveAuthProfile(actor("bob"));
        profiles.saveDataProfile(DataProfile.enabled("data", "Data", "", Map.of("id", "99")));
        var second = handler.handleHttpRequestToBeSent(live(2, "payload")).request();
        assertEquals("bob-state", second.headerValue("X-auth.token"));
        handler.handleHttpResponseReceived(received(2, second, "HTTP/1.1 200 OK\r\n\r\nsecond-response"));
        handler.handleHttpResponseReceived(received(1, first, "HTTP/1.1 200 OK\r\n\r\nfirst-response"));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(Map.of("first-response", "42", "second-response", "99"), sent.stream().collect(
                java.util.stream.Collectors.toMap(r -> r.headerValue("X-auth.token"), r -> r.headerValue("X-data.id"))));
        assertEquals("bob-state", profiles.authProfile("actor").orElseThrow().state().variables().get("token"));
    }

    @Test void liveRequestExtractsMaySupplyOwnInjectionWithoutAProfile() {
        var target = triggered(step("target", "auth.token").withExtractRules(List.of(extract("token", ExtractRule.SourceSide.REQUEST))), TriggerInputBindings.none(), false);
        wire(flow(target), r -> response("ok"));
        var action = handler.handleHttpRequestToBeSent(live(1, "payload"));
        assertEquals(RequestAction.CONTINUE, action.action());
        assertEquals("from-live-request", action.request().headerValue("X-auth.token"));
    }

    @Test void excludedTargetInjectionPreservesScannerPayloadWithoutRequiringItsProfile() {
        var original = step("target", "data.id");
        var target = triggered(original, new TriggerInputBindings("", "", List.of(original.injectRules().getFirst().id())), false);
        wire(flow(target), r -> response("ok"));
        var live = live(1, "scanner-payload");
        assertSame(live, handler.handleHttpRequestToBeSent(live).request());
        assertEquals(original.injectRules(), target.injectRules());
        assertEquals(1, handler.pendingTriggerCount());
        assertTrue(errors.isEmpty());
    }

    @Test void deletedExcludedRuleStopsBeforeSendingAnything() {
        wire(flow(step("before"), triggered(step("target"), new TriggerInputBindings("", "", List.of("deleted")), false)), r -> response("ok"));
        assertEquals(RequestAction.DROP, handler.handleHttpRequestToBeSent(live(1, "payload")).action());
        assertTrue(sent.isEmpty());
        assertTrue(errors.getLast().contains("no longer exists"));
    }

    private void wire(FlowDefinition flow, Function<HttpRequest, HttpResponse> transport) {
        var extraction = new ExtractionService(); var injection = new InjectionService();
        var engine = new FlowEngine(extraction, injection, request -> { sent.add(request); return transport.apply(request); });
        handler = new AutoTriggerHttpHandler(() -> List.of(flow), engine, extraction, injection, null,
                new TriggerInputService(profiles::profileSnapshot, Clock.systemUTC()), errors::add);
    }
    private void awaitErrors() throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (errors.isEmpty() && System.nanoTime() < until) Thread.sleep(10);
        assertFalse(errors.isEmpty());
    }
    private static AuthProfile actor(String name) {
        return new AuthProfile("actor", name, "", "manual", Map.of("username", name),
                new AuthState(Map.of("token", name + "-state"), Map.of(), Map.of(), 0, 0), true);
    }
    private static FlowStep triggered(FlowStep step, TriggerInputBindings bindings, boolean full) {
        return step.withTriggerRule(new StepTriggerRule(true, List.of("SCANNER"), List.of(),
                full ? StepTriggerRule.ExecutionScope.FULL_FLOW : StepTriggerRule.ExecutionScope.MATCHED_STEP,
                "", false, List.of(), bindings));
    }
    private static HttpRequestToBeSent live(int id, String body) {
        var request = request(service("api.test", 443, true), "GET", "/target",
                List.of(header("X-Value", "from-live-request")), body);
        return proxy(HttpRequestToBeSent.class, (p, m, a) -> switch (m.getName()) {
            case "messageId" -> id;
            case "toolSource" -> proxy(ToolSource.class, (q, n, b) -> ToolType.SCANNER);
            default -> HttpRequest.class.getMethod(m.getName(), m.getParameterTypes()).invoke(request, a);
        });
    }
    private static HttpResponseReceived received(int id, HttpRequest request, String raw) {
        var response = response(raw);
        return proxy(HttpResponseReceived.class, (p, m, a) -> switch (m.getName()) {
            case "messageId" -> id;
            case "toolSource" -> proxy(ToolSource.class, (q, n, b) -> ToolType.SCANNER);
            case "initiatingRequest" -> request;
            default -> HttpResponse.class.getMethod(m.getName(), m.getParameterTypes()).invoke(response, a);
        });
    }
}
