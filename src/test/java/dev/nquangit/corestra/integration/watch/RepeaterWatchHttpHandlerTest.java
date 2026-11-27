package dev.nquangit.corestra.integration.watch;

import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.HighlightColor;
import burp.api.montoya.core.ToolSource;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestAction;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
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
import dev.nquangit.corestra.integration.AutoTriggerHttpHandler;
import dev.nquangit.corestra.integration.CorestraTrafficOrigin;
import dev.nquangit.corestra.integration.ManualTestMessages;
import dev.nquangit.corestra.integration.TriggerInputService;
import dev.nquangit.corestra.watch.WatchDefinition;
import dev.nquangit.corestra.watch.WatchDisplayOptions;
import dev.nquangit.corestra.watch.WatchEngine;
import dev.nquangit.corestra.watch.WatchProbe;
import dev.nquangit.corestra.watch.WatchResult;
import dev.nquangit.corestra.watch.WatchScope;
import dev.nquangit.corestra.watch.WatchSnapshot;
import dev.nquangit.corestra.watch.WatchTiming;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static dev.nquangit.corestra.integration.ManualTestMessages.header;
import static dev.nquangit.corestra.integration.ManualTestMessages.proxy;
import static dev.nquangit.corestra.integration.ManualTestMessages.request;
import static dev.nquangit.corestra.integration.ManualTestMessages.service;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("MontoyaObjectFactory")
class RepeaterWatchHttpHandlerTest {
    private MontoyaObjectFactory previous;
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private final List<WatchResult> published = new CopyOnWriteArrayList<>();
    private final FakeEngine engine = new FakeEngine();
    private AutoTriggerHttpHandler autoTrigger;
    private RepeaterWatchHttpHandler handler;

    @BeforeEach void setup() {
        previous = ObjectFactoryLocator.FACTORY;
        var base = ManualTestMessages.factory();
        ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p, m, a) -> switch (m.getName()) {
            case "requestResult" -> proxy(RequestToBeSentAction.class, (q, n, b) -> switch (n.getName()) {
                case "request" -> a.length == 4 ? a[1] : a[0];
                case "action" -> a.length == 4 ? a[0] : RequestAction.CONTINUE;
                case "annotations" -> a.length == 2 ? a[1] : a.length == 4 ? a[3] : null;
                default -> null;
            });
            case "responseResult" -> proxy(ResponseReceivedAction.class, (q, n, b) -> switch (n.getName()) {
                case "response" -> a[0];
                case "annotations" -> a.length == 2 ? a[1] : null;
                case "action" -> ResponseAction.CONTINUE;
                default -> null;
            });
            case "annotations" -> annotations(a != null && a.length > 0 && a[0] instanceof String s ? s : null, null);
            default -> m.invoke(base, a);
        });
    }

    @AfterEach void tearDown() {
        try {
            if (handler != null) handler.close();
            if (autoTrigger != null) autoTrigger.close();
        } finally {
            ObjectFactoryLocator.FACTORY = previous;
        }
    }

    // ── plain Repeater watch ─────────────────────────────────────────────────

    @Test void watchedRepeaterRequestCapturesBeforeAndAfterAndDecoratesResponse() {
        handler = handler(List.of(definition(WatchDisplayOptions.defaults())), null);
        HttpRequestToBeSent live = live(1, ToolType.REPEATER, "POST", "/transfer",
                List.of(header("Cookie", "sid=1"), header("authorization", "Bearer t"), header("X-Amount", "500000"),
                        header("X-Other", "no")), "{\"to\":\"0456\"}");

        RequestToBeSentAction action = handler.handleHttpRequestToBeSent(live);

        assertSame(live, action.request());
        assertEquals(List.of("BEFORE"), events);
        assertEquals(Map.of("amount", "500000", "to", "0456"), engine.lastVariables);
        assertEquals(List.of("Cookie: sid=1", "authorization: Bearer t"), engine.lastInherited);
        assertEquals(1, handler.pendingCount());

        ResponseReceivedAction result = handler.handleHttpResponseReceived(received(1, action.request(),
                response(200, List.of(header("Content-Type", "application/json")), "{\"ok\":true}"), existing("mine")));

        assertEquals(List.of("BEFORE", "AFTER"), events);
        assertEquals(1, published.size());
        assertEquals("FAIL changed=1 fail=1 settled=yes 700ms", result.response().headerValue("X-Corestra-Watch"));
        assertTrue(result.response().headerValue("X-Corestra-Watch-1").startsWith("balance[0123] 100 -> 50 d=-50"));
        assertEquals("{\"ok\":true}", result.response().bodyToString());
        assertEquals(HighlightColor.RED, result.annotations().highlightColor());
        assertTrue(result.annotations().notes().startsWith("mine | Watch Transfer: FAIL"), result.annotations().notes());
        assertEquals(0, handler.pendingCount());
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test void jsonEnvelopeIsAppliedToTheResponseBody() {
        handler = handler(List.of(definition(new WatchDisplayOptions(false, true, false, false))), null);
        RequestToBeSentAction action = handler.handleHttpRequestToBeSent(live(2, ToolType.REPEATER, "POST", "/transfer", List.of(), ""));

        ResponseReceivedAction result = handler.handleHttpResponseReceived(received(2, action.request(),
                response(200, List.of(), "{\"ok\":true}"), null));

        String body = result.response().bodyToString();
        assertTrue(body.startsWith("{\"corestraWatch\":{"), body);
        assertTrue(body.endsWith(",\"response\":{\"ok\":true}}"), body);
        assertEquals(Integer.toString(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length),
                result.response().headerValue("Content-Length"));
        assertNull(result.response().headerValue("X-Corestra-Watch"));
    }

    @Test void nonRepeaterOutOfScopeDisabledAndUnconfiguredRequestsAreNotWatched() {
        WatchDefinition enabled = definition(WatchDisplayOptions.defaults());
        handler = handler(List.of(enabled.withEnabled(false), enabled.withProbe(new WatchProbe(null, null)),
                enabled.withScope(new WatchScope(List.of("POST"), "", "^/other"))), null);
        assertNotWatched(live(3, ToolType.REPEATER, "POST", "/transfer", List.of(), ""));

        handler = handler(List.of(enabled), null);
        assertNotWatched(live(4, ToolType.PROXY, "POST", "/transfer", List.of(), ""));
        assertNotWatched(live(5, ToolType.EXTENSIONS, "POST", "/transfer", List.of(), ""));
        assertNotWatched(live(6, ToolType.REPEATER, "GET", "/transfer", List.of(), ""));
    }

    @Test void corestraOriginRequestsAreNotWatched() {
        CorestraTrafficOrigin origin = new CorestraTrafficOrigin();
        origin.activate();
        handler = new RepeaterWatchHttpHandler(() -> List.of(definition(WatchDisplayOptions.defaults())), engine,
                new ExtractionService(), null, origin, published::add, errors::add);
        HttpRequest base = request(service("bank.test", 443, true), "POST", "/transfer", List.of(), "");

        origin.send(base, marked -> {
            HttpRequestToBeSent toBeSent = live(7, ToolType.REPEATER, marked);
            handler.handleHttpRequestToBeSent(toBeSent);
            return null;
        });

        assertTrue(events.isEmpty());
        assertEquals(0, handler.pendingCount());
    }

    @Test void invalidScopePatternIsReportedAndSkipped() {
        WatchDefinition broken = definition(WatchDisplayOptions.defaults()).withScope(new WatchScope(List.of(), "(", ""));
        handler = handler(List.of(broken, definition(WatchDisplayOptions.defaults())), null);
        handler.handleHttpRequestToBeSent(live(8, ToolType.REPEATER, "POST", "/transfer", List.of(), ""));
        assertEquals(List.of("BEFORE"), events, "second definition still matches");
        assertTrue(errors.stream().anyMatch(e -> e.contains("invalid scope pattern")), errors.toString());
    }

    @Test void missingRequestVariablesBecomeNotes() {
        handler = handler(List.of(definition(WatchDisplayOptions.defaults())), null);
        RequestToBeSentAction action = handler.handleHttpRequestToBeSent(live(9, ToolType.REPEATER, "POST", "/transfer", List.of(), ""));
        handler.handleHttpResponseReceived(received(9, action.request(), response(200, List.of(), ""), null));
        assertTrue(engine.lastExtraNotes.stream().anyMatch(n -> n.startsWith("request variable 'amount' not extracted")),
                engine.lastExtraNotes.toString());
    }

    // ── failure isolation ────────────────────────────────────────────────────

    @Test void captureFailurePassesRequestThroughAndReports() {
        engine.failCapture = true;
        handler = handler(List.of(definition(WatchDisplayOptions.defaults())), null);
        HttpRequestToBeSent live = live(10, ToolType.REPEATER, "POST", "/transfer", List.of(), "");

        RequestToBeSentAction action = handler.handleHttpRequestToBeSent(live);

        assertSame(live, action.request());
        assertEquals(RequestAction.CONTINUE, action.action());
        assertEquals(0, handler.pendingCount());
        assertTrue(errors.stream().anyMatch(e -> e.contains("probe exploded")), errors.toString());
    }

    @Test void completeFailureReturnsUndecoratedResponse() {
        engine.failComplete = true;
        handler = handler(List.of(definition(WatchDisplayOptions.defaults())), null);
        RequestToBeSentAction action = handler.handleHttpRequestToBeSent(live(11, ToolType.REPEATER, "POST", "/transfer", List.of(), ""));
        HttpResponseReceived received = received(11, action.request(), response(200, List.of(), "body"), null);

        ResponseReceivedAction result = handler.handleHttpResponseReceived(received);

        assertSame(received, result.response());
        assertTrue(published.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("AFTER failed")), errors.toString());
    }

    @Test void listenerFailureStillDecorates() {
        handler = new RepeaterWatchHttpHandler(() -> List.of(definition(WatchDisplayOptions.defaults())), engine,
                new ExtractionService(), null, null, r -> { throw new IllegalStateException("ui gone"); }, errors::add);
        RequestToBeSentAction action = handler.handleHttpRequestToBeSent(live(12, ToolType.REPEATER, "POST", "/transfer", List.of(), ""));
        ResponseReceivedAction result = handler.handleHttpResponseReceived(received(12, action.request(), response(200, List.of(), ""), null));
        assertNotNull(result.response().headerValue("X-Corestra-Watch"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("ui gone")));
    }

    @Test void pendingWatchesAreBoundedAndExpire() {
        long[] now = {1_000L};
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public java.time.Instant instant() { return java.time.Instant.ofEpochMilli(now[0]); }
        };
        handler = new RepeaterWatchHttpHandler(() -> List.of(definition(WatchDisplayOptions.defaults())), engine,
                new ExtractionService(), null, null, published::add, errors::add, clock, 2, 5_000L);
        for (int id = 20; id < 23; id++) {
            now[0] += 10;
            handler.handleHttpRequestToBeSent(live(id, ToolType.REPEATER, "POST", "/transfer", List.of(), ""));
        }
        assertEquals(2, handler.pendingCount());
        now[0] += 10_000L;
        assertEquals(0, handler.pendingCount());
    }

    @Test void unmatchedResponsesAndClosedHandlerPassThrough() {
        handler = handler(List.of(definition(WatchDisplayOptions.defaults())), null);
        HttpResponseReceived received = received(30, request(service("bank.test", 443, true), "POST", "/transfer", List.of(), ""),
                response(200, List.of(), "x"), null);
        assertSame(received, handler.handleHttpResponseReceived(received).response());

        handler.close();
        assertNotWatched(live(31, ToolType.REPEATER, "POST", "/transfer", List.of(), ""));
    }

    // ── auto-trigger coordination ────────────────────────────────────────────

    @Test void beforeRunsBeforePrerequisitesAndAfterRunsAfterDownstream() {
        autoTrigger = autoTrigger(flow(AggregationPlan.disabled(), List.of()), recordingClient(Function.identity()));
        handler = handler(List.of(definition(WatchDisplayOptions.defaults())), autoTrigger);

        RequestToBeSentAction action = handler.handleHttpRequestToBeSent(live(40, ToolType.REPEATER, "POST", "/transfer", List.of(), ""));
        assertEquals(List.of("BEFORE", "send /s0"), events, "BEFORE must precede prerequisite steps");
        assertEquals("from-/s0", action.request().headerValue("X-Prereq"), "delegate's prepared request is returned");

        ResponseReceivedAction result = handler.handleHttpResponseReceived(received(40, action.request(),
                response(200, List.of(header("X-Value", "live")), "live-body"), null));

        assertEquals(List.of("BEFORE", "send /s0", "send /s2", "send /s3", "AFTER"), events);
        assertTrue(engine.lastExtraNotes.stream().noneMatch(n -> n.startsWith("downstream")), engine.lastExtraNotes.toString());
        assertEquals("live-body", result.response().bodyToString(), "aggregation off: body unchanged");
        assertNotNull(result.response().headerValue("X-Corestra-Watch"));
    }

    @Test void downstreamTimeoutStillCapturesAfterWithNote() {
        CountDownLatch release = new CountDownLatch(1);
        autoTrigger = autoTrigger(flow(AggregationPlan.disabled(), List.of()), recordingClient(r -> {
            if (r.path().equals("/s3")) await(release);
            return r;
        }));
        WatchDefinition definition = definition(WatchDisplayOptions.defaults())
                .withTiming(new WatchTiming(5_000, 700, 2, 6_000, 1_000));
        handler = handler(List.of(definition), autoTrigger);
        try {
            RequestToBeSentAction action = handler.handleHttpRequestToBeSent(live(41, ToolType.REPEATER, "POST", "/transfer", List.of(), ""));
            ResponseReceivedAction result = assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    handler.handleHttpResponseReceived(received(41, action.request(), response(200, List.of(header("X-Value", "live")), "live-body"), null)));

            assertEquals("AFTER", events.getLast());
            assertTrue(engine.lastExtraNotes.stream().anyMatch(n -> n.startsWith("downstream flow: timeout")), engine.lastExtraNotes.toString());
            assertTrue(noteHeaders(result).stream().anyMatch(n -> n.startsWith("downstream flow: timeout")), noteHeaders(result).toString());
        } finally {
            release.countDown();
        }
    }

    @Test void downstreamFailureStillCapturesAfterWithNote() {
        autoTrigger = autoTrigger(flow(AggregationPlan.disabled(), List.of()), recordingClient(r -> {
            if (r.path().equals("/s2")) throw new IllegalStateException("connection reset");
            return r;
        }));
        handler = handler(List.of(definition(WatchDisplayOptions.defaults())), autoTrigger);
        RequestToBeSentAction action = handler.handleHttpRequestToBeSent(live(42, ToolType.REPEATER, "POST", "/transfer", List.of(), ""));
        handler.handleHttpResponseReceived(received(42, action.request(), response(200, List.of(header("X-Value", "live")), "live-body"), null));

        assertEquals("AFTER", events.getLast());
        assertTrue(engine.lastExtraNotes.stream().anyMatch(n -> n.startsWith("downstream flow: failed")), engine.lastExtraNotes.toString());
    }

    @Test void decorationIsAppliedOnTopOfAggregatedResponse() {
        autoTrigger = autoTrigger(flow(new AggregationPlan(true, AggregationPlan.Mode.APPEND_RAW_RESPONSES, "", List.of(), true),
                List.of()), recordingClient(Function.identity()));
        handler = handler(List.of(definition(new WatchDisplayOptions(true, true, false, false))), autoTrigger);
        RequestToBeSentAction action = handler.handleHttpRequestToBeSent(live(43, ToolType.REPEATER, "POST", "/transfer", List.of(), ""));

        ResponseReceivedAction result = handler.handleHttpResponseReceived(received(43, action.request(),
                response(200, List.of(header("X-Value", "live")), "live-body"), null));

        String body = result.response().bodyToString();
        assertTrue(body.startsWith("[Corestra Watch] Transfer: FAIL"), body);
        assertTrue(body.endsWith("\n\nlive-body\nbody-/s2\nbody-/s3\n"), body);
        assertTrue(noteHeaders(result).stream().anyMatch(n -> n.startsWith("jsonEnvelope skipped")), noteHeaders(result).toString());
    }

    @Test void requestDroppedByAutoTriggerIsNotWatched() {
        autoTrigger = autoTrigger(flow(AggregationPlan.disabled(), List.of(new InjectRule("inj", "inj", true,
                InjectRule.Target.REQUEST_HEADER, "X-Need", InjectRule.ValueSourceType.VARIABLE, "neverSet", "", null))),
                recordingClient(Function.identity()));
        handler = handler(List.of(definition(WatchDisplayOptions.defaults())), autoTrigger);

        RequestToBeSentAction action = handler.handleHttpRequestToBeSent(live(44, ToolType.REPEATER, "POST", "/transfer", List.of(), ""));

        assertEquals(RequestAction.DROP, action.action());
        assertEquals(0, handler.pendingCount());
        assertTrue(errors.stream().anyMatch(e -> e.contains("not sent")), errors.toString());
    }

    @Test void unwatchedTrafficIsPassedToTheDelegateUnchanged() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        autoTrigger = autoTrigger(flow(AggregationPlan.disabled(), List.of()), recordingClient(r -> {
            if (!r.path().equals("/s0")) { await(release); done.countDown(); }
            return r;
        }));
        handler = handler(List.of(definition(WatchDisplayOptions.defaults()).withEnabled(false)), autoTrigger);

        RequestToBeSentAction action = handler.handleHttpRequestToBeSent(live(45, ToolType.REPEATER, "POST", "/transfer", List.of(), ""));
        assertEquals("from-/s0", action.request().headerValue("X-Prereq"));
        HttpResponseReceived received = received(45, action.request(), response(200, List.of(header("X-Value", "live")), "live-body"), null);
        ResponseReceivedAction result = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> handler.handleHttpResponseReceived(received));

        assertSame(received, result.response(), "downstream stays asynchronous and the response untouched");
        release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse(events.contains("BEFORE"));
    }

    @Test void variableChangedByAutoTriggerIsNoted() {
        autoTrigger = autoTrigger(flow(AggregationPlan.disabled(), List.of(new InjectRule("inj", "inj", true,
                InjectRule.Target.REQUEST_HEADER, "X-Amount", InjectRule.ValueSourceType.STATIC, "", "999", null))),
                recordingClient(Function.identity()));
        handler = handler(List.of(definition(WatchDisplayOptions.defaults())), autoTrigger);
        RequestToBeSentAction action = handler.handleHttpRequestToBeSent(live(46, ToolType.REPEATER, "POST", "/transfer",
                List.of(header("X-Amount", "500000")), ""));
        handler.handleHttpResponseReceived(received(46, action.request(), response(200, List.of(header("X-Value", "live")), ""), null));

        assertEquals("500000", engine.lastVariables.get("amount"));
        assertTrue(engine.lastExtraNotes.stream().anyMatch(n -> n.contains("'amount' was changed by auto trigger to '999'")),
                engine.lastExtraNotes.toString());
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private RepeaterWatchHttpHandler handler(List<WatchDefinition> definitions, AutoTriggerHttpHandler delegate) {
        return new RepeaterWatchHttpHandler(() -> definitions, engine, new ExtractionService(), delegate, null,
                published::add, errors::add);
    }

    private static List<String> noteHeaders(ResponseReceivedAction action) {
        return action.response().headers().stream().filter(h -> h.name().startsWith("X-Corestra-Watch-Note-"))
                .map(HttpHeader::value).toList();
    }

    private void assertNotWatched(HttpRequestToBeSent live) {
        int before = events.size();
        RequestToBeSentAction action = handler.handleHttpRequestToBeSent(live);
        assertSame(live, action.request());
        assertEquals(before, events.size(), "BEFORE must not be captured");
        assertEquals(0, handler.pendingCount());
    }

    private static WatchDefinition definition(WatchDisplayOptions display) {
        var probe = new WatchProbe(new StoredRequestTemplate("GET", "https", "bank.test", 443, "/accounts", List.of(), ""), null);
        var amount = new ExtractRule("a", "amount", true, ExtractRule.SourceSide.REQUEST, ExtractRule.ExtractorType.HEADER,
                "X-Amount", "amount", List.of());
        var to = new ExtractRule("t", "to", true, ExtractRule.SourceSide.REQUEST, ExtractRule.ExtractorType.JSON_PATH,
                "$.to", "to", List.of());
        return new WatchDefinition("w1", "Transfer", true, new WatchScope(List.of("POST"), "bank\\.test", "^/transfer"),
                probe, List.of(amount, to), List.of(), List.of(), display, null);
    }

    private AutoTriggerHttpHandler autoTrigger(FlowDefinition flow, Function<HttpRequest, HttpResponse> client) {
        var extraction = new ExtractionService();
        var injection = new InjectionService();
        return new AutoTriggerHttpHandler(() -> List.of(flow), new FlowEngine(extraction, injection, client::apply),
                extraction, injection, null, new TriggerInputService(AccessWorkspace::empty, Clock.systemUTC()), errors::add);
    }

    /** s0 prerequisite, s1 triggered by Repeater POST /transfer, s2 and s3 downstream. */
    private static FlowDefinition flow(AggregationPlan plan, List<InjectRule> triggeredInjects) {
        List<FlowStep> steps = new ArrayList<>();
        steps.add(step("s0").withExtractRules(List.of(new ExtractRule("p", "p", true, ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.HEADER, "X-Value", "prereq", List.of()))));
        List<InjectRule> injects = new ArrayList<>(triggeredInjects);
        injects.add(new InjectRule("pi", "pi", true, InjectRule.Target.REQUEST_HEADER, "X-Prereq",
                InjectRule.ValueSourceType.VARIABLE, "prereq", "", null));
        steps.add(step("s1").withIncludeInAggregation(true).withInjectRules(injects)
                .withTriggerRule(new StepTriggerRule(true, List.of("REPEATER"), List.of(),
                        StepTriggerRule.ExecutionScope.FULL_FLOW, "")));
        steps.add(step("s2").withIncludeInAggregation(true));
        steps.add(step("s3").withIncludeInAggregation(true));
        return new FlowDefinition("f", "Flow", "", true, steps, null, plan, null);
    }

    private static FlowStep step(String id) {
        return new FlowStep(id, id, true, "", new StoredRequestTemplate("GET", "https", "bank.test", 443, "/" + id, List.of(), ""),
                List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
    }

    private Function<HttpRequest, HttpResponse> recordingClient(Function<HttpRequest, HttpRequest> hook) {
        return request -> {
            hook.apply(request);
            events.add("send " + request.path());
            return ManualTestMessages.response("HTTP/1.1 200 OK\r\nX-Value: from-" + request.path() + "\r\n\r\nbody-" + request.path());
        };
    }

    private static HttpRequestToBeSent live(int id, ToolType tool, String method, String path, List<HttpHeader> headers, String body) {
        return live(id, tool, request(service("bank.test", 443, true), method, path, headers, body));
    }

    private static HttpRequestToBeSent live(int id, ToolType tool, HttpRequest request) {
        return proxy(HttpRequestToBeSent.class, (p, m, a) -> switch (m.getName()) {
            case "messageId" -> id;
            case "toolSource" -> toolSource(tool);
            case "annotations" -> null;
            default -> HttpRequest.class.getMethod(m.getName(), m.getParameterTypes()).invoke(request, a);
        });
    }

    private static ToolSource toolSource(ToolType tool) {
        return proxy(ToolSource.class, (q, n, b) -> switch (n.getName()) {
            case "toolType" -> tool;
            case "isFromTool" -> Arrays.asList((ToolType[]) b[0]).contains(tool);
            default -> null;
        });
    }

    /** Response fake supporting header replacement and body changes with Content-Length. */
    static HttpResponse response(int status, List<HttpHeader> headers, String body) {
        return proxy(HttpResponse.class, (p, m, a) -> switch (m.getName()) {
            case "statusCode" -> (short) status;
            case "httpVersion" -> "HTTP/1.1";
            case "reasonPhrase" -> "OK";
            case "headers" -> headers;
            case "bodyToString" -> body;
            case "body" -> ManualTestMessages.bytes(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            case "headerValue" -> headers.stream().filter(h -> h.name().equalsIgnoreCase((String) a[0])).map(HttpHeader::value).findFirst().orElse(null);
            case "hasHeader" -> a.length == 1 && a[0] instanceof String name && headers.stream().anyMatch(h -> h.name().equalsIgnoreCase(name));
            case "withAddedHeader" -> {
                List<HttpHeader> updated = new ArrayList<>(headers);
                updated.add(header((String) a[0], (String) a[1]));
                yield response(status, updated, body);
            }
            case "withUpdatedHeader" -> response(status, headers.stream()
                    .map(h -> h.name().equalsIgnoreCase((String) a[0]) ? header(h.name(), (String) a[1]) : h).toList(), body);
            case "withBody" -> {
                String changed = (String) a[0];
                List<HttpHeader> updated = new ArrayList<>(headers.stream().filter(h -> !h.name().equalsIgnoreCase("Content-Length")).toList());
                updated.add(header("Content-Length", Integer.toString(changed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)));
                yield response(status, updated, changed);
            }
            case "toString" -> "HTTP/1.1 " + status + " OK\r\n\r\n" + body;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private static HttpResponseReceived received(int id, HttpRequest initiating, HttpResponse response, Annotations annotations) {
        return proxy(HttpResponseReceived.class, (p, m, a) -> switch (m.getName()) {
            case "messageId" -> id;
            case "initiatingRequest" -> initiating;
            case "toolSource" -> toolSource(ToolType.REPEATER);
            case "annotations" -> annotations;
            default -> HttpResponse.class.getMethod(m.getName(), m.getParameterTypes()).invoke(response, a);
        });
    }

    private static Annotations existing(String notes) {
        return annotations(notes, null);
    }

    private static Annotations annotations(String notes, HighlightColor color) {
        return proxy(Annotations.class, (p, m, a) -> switch (m.getName()) {
            case "notes" -> notes;
            case "hasNotes" -> notes != null && !notes.isEmpty();
            case "highlightColor" -> color;
            case "hasHighlightColor" -> color != null;
            case "withNotes" -> annotations((String) a[0], color);
            case "withHighlightColor" -> annotations(notes, (HighlightColor) a[0]);
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(10, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Records BEFORE/AFTER ordering and returns a fixed FAIL result. */
    private final class FakeEngine implements WatchEngine {
        volatile boolean failCapture;
        volatile boolean failComplete;
        volatile Map<String, String> lastVariables;
        volatile List<String> lastInherited;
        volatile List<String> lastExtraNotes = List.of();

        @Override public WatchSnapshot capture(WatchDefinition definition, Map<String, String> variables, List<String> inheritedHeaders) {
            if (failCapture) throw new IllegalStateException("probe exploded");
            events.add("BEFORE");
            lastVariables = Map.copyOf(variables);
            lastInherited = List.copyOf(inheritedHeaders);
            return new WatchSnapshot(1L, 200, Map.of("balance[0123]", new WatchSnapshot.Value("100", new java.math.BigDecimal("100"))), List.of());
        }

        @Override public WatchResult complete(WatchDefinition definition, Map<String, String> variables, List<String> inheritedHeaders,
                                              WatchSnapshot before, List<String> extraNotes) {
            if (failComplete) throw new IllegalStateException("after exploded");
            events.add("AFTER");
            lastExtraNotes = List.copyOf(extraNotes);
            var after = new WatchSnapshot(2L, 200, Map.of("balance[0123]", new WatchSnapshot.Value("50", new java.math.BigDecimal("50"))), List.of());
            return new WatchResult(definition.id(), definition.name(), WatchResult.Status.FAIL,
                    List.of(new WatchResult.Row("balance[0123]", "100", "50", new java.math.BigDecimal("-50"), true,
                            WatchResult.Verdict.FAIL, "expected -40")),
                    true, 700L, before, after, extraNotes);
        }

        @Override public WatchResult evaluate(WatchDefinition definition, Map<String, String> variables, WatchSnapshot before,
                                              WatchSnapshot after, boolean settled, long settleMillis, List<String> notes) {
            throw new UnsupportedOperationException();
        }
    }
}
