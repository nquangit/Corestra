package dev.nquangit.corestra.integration;

import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.ToolSource;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import burp.api.montoya.core.HighlightColor;
import dev.nquangit.corestra.flow.control.StepConditionOperator;
import dev.nquangit.corestra.http.retry.RetryCondition;
import dev.nquangit.corestra.http.retry.RetryConditionSource;
import dev.nquangit.corestra.http.retry.RetryConfig;
import dev.nquangit.corestra.http.retry.RetryProfile;
import dev.nquangit.corestra.http.retry.RetryRule;
import dev.nquangit.corestra.http.retry.HostCooldownGate;
import dev.nquangit.corestra.http.retry.LiveRetryEngine;
import dev.nquangit.corestra.http.retry.RetryWaiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Global retry on live Burp traffic: the calling tool receives the final response. */
@ResourceLock("MontoyaObjectFactory")
class GlobalRetryHttpHandlerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(5_000_000), ZoneOffset.UTC);
    private MontoyaObjectFactory previousFactory;
    private final List<Long> waits = new CopyOnWriteArrayList<>();
    private final List<HttpRequest> resent = new CopyOnWriteArrayList<>();
    private final Deque<HttpResponse> resendResponses = new ArrayDeque<>();
    private final List<String> diagnostics = new CopyOnWriteArrayList<>();
    private final AtomicReference<RetryConfig> policy = new AtomicReference<>(enabledPolicy());
    private HostCooldownGate gate;
    private GlobalRetryHttpHandler handler;

    private static RetryConfig enabledPolicy() {
        RetryRule waf = RetryRule.of("WAF block", RetryRule.Action.STOP, List.of(RetryCondition.of(
                RetryConditionSource.RESPONSE_BODY, "", StepConditionOperator.CONTAINS, "request blocked")))
                .withHighlight(RetryRule.Highlight.RED);
        RetryRule rate = RetryRule.of("Rate limited", RetryRule.Action.RETRY, List.of(RetryCondition.of(
                RetryConditionSource.STATUS, "", StepConditionOperator.EQUALS, "429")));
        RetryProfile api = new RetryProfile(null, "API", true, List.of("PROXY", "REPEATER"), true, "", "^/api",
                List.of(), List.of(waf, rate), 3, 1_000, 30_000, 2.0, true, 60, true);
        return new RetryConfig(true, List.of(api));
    }

    @BeforeEach
    void setUp() {
        previousFactory = ObjectFactoryLocator.FACTORY;
        ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p, m, a) -> switch (m.getName()) {
            case "requestResult" -> proxy(RequestToBeSentAction.class,
                    (q, n, b) -> n.getName().equals("request") ? a[0] : null);
            case "responseResult" -> proxy(ResponseReceivedAction.class, (q, n, b) -> switch (n.getName()) {
                case "response" -> a[0];
                case "annotations" -> a.length > 1 ? a[1] : null;
                default -> null;
            });
            default -> throw new UnsupportedOperationException(m.getName());
        });
        gate = new HostCooldownGate(CLOCK);
        RetryWaiter waiter = millis -> { waits.add(millis); return true; };
        handler = new GlobalRetryHttpHandler(policy::get, new LiveRetryEngine(CLOCK, waiter, gate), gate, waiter, null);
        handler.setDiagnostics(diagnostics::add);
        handler.setSender(request -> {
            resent.add(request);
            HttpResponse next = resendResponses.poll();
            if (next == null) throw new IllegalStateException("connection reset");
            return next;
        });
    }

    @AfterEach
    void restoreFactory() {
        ObjectFactoryLocator.FACTORY = previousFactory;
    }

    @Test
    void rateLimitedProxyResponseIsReplacedByTheRetriedResponseWithANote() {
        HttpRequestToBeSent request = request(1, ToolType.PROXY, true, "GET");
        handler.handleHttpRequestToBeSent(request);
        resendResponses.add(response(200, List.of(), "ok"));

        ResponseReceivedAction action = handler.handleHttpResponseReceived(received(1, request, 429, List.of(), ""));

        assertEquals(200, action.response().statusCode());
        assertEquals(List.of(request), resent, "the original request is resent unchanged");
        assertEquals(List.of(1_000L), waits);
        assertEquals("earlier note | Corestra retry [API]: 1 resend (rule 'Rate limited'), waited 1.0s,"
                + " final status 200 - recovered", action.annotations().notes());
        assertEquals(HighlightColor.NONE, action.annotations().highlightColor());
    }

    @Test
    void retryAfterIsHonoredAndGivingUpKeepsTheLastResponse() {
        HttpRequestToBeSent request = request(2, ToolType.REPEATER, true, "POST");
        handler.handleHttpRequestToBeSent(request);
        resendResponses.add(response(429, List.of(header("Retry-After", "2")), "still limited"));

        ResponseReceivedAction action = handler.handleHttpResponseReceived(
                received(2, request, 429, List.of(header("Retry-After", "4")), "limited"));

        assertEquals(List.of(4_000L, 2_000L), waits);
        assertEquals("still limited", action.response().bodyToString());
        assertTrue(action.annotations().notes().contains("gave up: resend failed (connection reset)"),
                action.annotations().notes());
        assertEquals(1, diagnostics.size());
    }

    @Test
    void untouchedWhenOutOfScopeToolNotSelectedOrResponseIsFine() {
        HttpRequestToBeSent outOfScope = request(3, ToolType.PROXY, false, "GET");
        HttpRequestToBeSent scanner = request(4, ToolType.SCANNER, true, "GET");
        HttpRequestToBeSent fine = request(5, ToolType.PROXY, true, "GET");
        for (HttpRequestToBeSent request : List.of(outOfScope, scanner, fine)) handler.handleHttpRequestToBeSent(request);

        HttpResponseReceived limited3 = received(3, outOfScope, 429, List.of(), "");
        HttpResponseReceived limited4 = received(4, scanner, 429, List.of(), "");
        HttpResponseReceived ok5 = received(5, fine, 200, List.of(), "");
        assertSame(limited3, handler.handleHttpResponseReceived(limited3).response());
        assertSame(limited4, handler.handleHttpResponseReceived(limited4).response());
        assertSame(ok5, handler.handleHttpResponseReceived(ok5).response());
        assertTrue(resent.isEmpty());
    }

    @Test
    void disabledPolicyAndUnadmittedResponsesAreIgnored() {
        policy.set(RetryConfig.defaults());
        HttpRequestToBeSent request = request(6, ToolType.PROXY, true, "GET");
        handler.handleHttpRequestToBeSent(request);
        HttpResponseReceived limited = received(6, request, 429, List.of(), "");
        assertSame(limited, handler.handleHttpResponseReceived(limited).response());

        policy.set(enabledPolicy());
        HttpResponseReceived neverAdmitted = received(77, request, 429, List.of(), "");
        assertSame(neverAdmitted, handler.handleHttpResponseReceived(neverAdmitted).response());
        assertTrue(resent.isEmpty());
    }

    @Test
    void stopRuleDeliversTheResponseWithNoteAndHighlight() {
        HttpRequestToBeSent request = request(11, ToolType.PROXY, true, "GET");
        handler.handleHttpRequestToBeSent(request);
        HttpResponseReceived blocked = received(11, request, 429, List.of(), "Request blocked by policy");

        ResponseReceivedAction action = handler.handleHttpResponseReceived(blocked);

        assertSame(blocked, action.response());
        assertTrue(resent.isEmpty(), "STOP is evaluated before the 429 retry rule");
        assertEquals("earlier note | Corestra retry [API]: rule 'WAF block' - stop, not retried",
                action.annotations().notes());
        assertEquals(HighlightColor.RED, action.annotations().highlightColor());
    }

    @Test
    void pathOutsideTheProfileIsNotCovered() {
        HttpRequestToBeSent request = request(12, ToolType.PROXY, true, "GET", "/static/app.js");
        handler.handleHttpRequestToBeSent(request);
        HttpResponseReceived limited = received(12, request, 429, List.of(), "");
        assertSame(limited, handler.handleHttpResponseReceived(limited).response());
        assertTrue(resent.isEmpty());
    }

    @Test
    void requestToACoolingDownHostWaitsBeforeBeingSent() {
        gate.block("example.com:443", CLOCK.millis() + 7_000);
        handler.handleHttpRequestToBeSent(request(8, ToolType.PROXY, true, "GET"));
        assertEquals(List.of(7_000L), waits);

        waits.clear();
        handler.handleHttpRequestToBeSent(request(9, ToolType.SCANNER, true, "GET"));
        assertTrue(waits.isEmpty(), "requests outside the policy are never delayed");
    }

    @Test
    void closedHandlerPassesTrafficThrough() {
        HttpRequestToBeSent request = request(10, ToolType.PROXY, true, "GET");
        handler.handleHttpRequestToBeSent(request);
        handler.close();
        HttpResponseReceived limited = received(10, request, 429, List.of(), "");
        assertSame(limited, handler.handleHttpResponseReceived(limited).response());
        assertTrue(resent.isEmpty());
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static HttpRequestToBeSent request(int id, ToolType toolType, boolean inScope, String method) {
        return request(id, toolType, inScope, method, "/api/items");
    }

    private static HttpRequestToBeSent request(int id, ToolType toolType, boolean inScope, String method, String path) {
        HttpService service = proxy(HttpService.class, (p, m, a) -> switch (m.getName()) {
            case "host" -> "Example.com";
            case "port" -> 443;
            case "secure" -> true;
            default -> null;
        });
        ToolSource tool = proxy(ToolSource.class, (p, m, a) -> m.getName().equals("toolType") ? toolType : null);
        return proxy(HttpRequestToBeSent.class, (p, m, a) -> switch (m.getName()) {
            case "messageId" -> id;
            case "toolSource" -> tool;
            case "isInScope" -> inScope;
            case "method" -> method;
            case "path" -> path;
            case "httpService" -> service;
            case "headers" -> List.<HttpHeader>of();
            case "hasHeader" -> false;
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            case "toString" -> method + " /api #" + id;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private static HttpResponseReceived received(int id, HttpRequest request, int status, List<HttpHeader> headers,
                                                 String body) {
        Annotations annotations = annotations("earlier note");
        return proxy(HttpResponseReceived.class, (p, m, a) -> switch (m.getName()) {
            case "messageId" -> id;
            case "initiatingRequest" -> request;
            case "statusCode" -> (short) status;
            case "headers" -> headers;
            case "bodyToString" -> body;
            case "annotations" -> annotations;
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private static HttpResponse response(int status, List<HttpHeader> headers, String body) {
        return proxy(HttpResponse.class, (p, m, a) -> switch (m.getName()) {
            case "statusCode" -> (short) status;
            case "headers" -> headers;
            case "bodyToString" -> body;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private static Annotations annotations(String notes) {
        return annotations(notes, HighlightColor.NONE);
    }

    private static Annotations annotations(String notes, HighlightColor color) {
        return proxy(Annotations.class, (p, m, a) -> switch (m.getName()) {
            case "notes" -> notes;
            case "hasNotes" -> notes != null && !notes.isEmpty();
            case "withNotes" -> annotations((String) a[0], color);
            case "highlightColor" -> color;
            case "withHighlightColor" -> annotations(notes, (HighlightColor) a[0]);
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private static HttpHeader header(String name, String value) {
        return proxy(HttpHeader.class, (p, m, a) -> switch (m.getName()) {
            case "name" -> name;
            case "value" -> value;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler invocationHandler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, invocationHandler));
    }
}
