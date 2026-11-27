package dev.nquangit.corestra.integration;

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
import dev.nquangit.corestra.access.auth.AuthFailureRule;
import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthInjectionLocation;
import dev.nquangit.corestra.access.auth.AuthInjectionMode;
import dev.nquangit.corestra.access.auth.AuthInjectionRule;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.auth.AuthRuleType;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.auth.runtime.AuthFlowRunResult;
import dev.nquangit.corestra.access.auth.runtime.AuthInjectionRuleRenderer;
import dev.nquangit.corestra.access.auth.runtime.AuthManager;
import dev.nquangit.corestra.access.auth.runtime.AuthRuleEvaluator;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.execution.TestFlowEngineFactory;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.session.SessionAuthConfig;
import dev.nquangit.corestra.session.SessionInjectionRule;
import dev.nquangit.corestra.session.SessionManager;
import dev.nquangit.corestra.session.SessionProfile;
import dev.nquangit.corestra.session.SessionProfileRepository;
import dev.nquangit.corestra.session.SessionSchedulerConfig;
import dev.nquangit.corestra.session.SessionScope;
import dev.nquangit.corestra.session.SessionState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live traffic handled by the Session Handler re-authenticates according to the user's configured
 * failure rules and retry policy, instead of waiting for the session TTL to expire.
 */
@ResourceLock("MontoyaObjectFactory")
class LiveAuthFailureRecoveryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(61_000), ZoneOffset.UTC);
    private MontoyaObjectFactory previousFactory;
    private SessionProfileRepository repository;
    private InMemoryAccessRepository accessRepository;
    private final AtomicInteger loginRuns = new AtomicInteger();
    private final AtomicBoolean loginFails = new AtomicBoolean();
    private final List<HttpRequest> resent = new CopyOnWriteArrayList<>();
    private final List<String> diagnostics = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        previousFactory = ObjectFactoryLocator.FACTORY;
        ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p, m, a) -> switch (m.getName()) {
            case "httpHeader" -> header((String) a[0], (String) a[1]);
            case "requestResult" -> proxy(RequestToBeSentAction.class, (q, n, b) -> switch (n.getName()) {
                case "request" -> a.length == 4 ? a[1] : a[0];
                case "action" -> a.length == 4 ? a[0] : burp.api.montoya.http.handler.RequestAction.CONTINUE;
                default -> null;
            });
            case "responseResult" -> proxy(ResponseReceivedAction.class,
                    (q, n, b) -> n.getName().equals("response") ? a[0] : null);
            default -> throw new UnsupportedOperationException(m.getName());
        });
        repository = new SessionProfileRepository();
        accessRepository = new InMemoryAccessRepository();
    }

    @AfterEach
    void restoreFactory() {
        ObjectFactoryLocator.FACTORY = previousFactory;
    }

    @Test
    void linkedProfileReauthenticatesByProviderPolicyAndResendsOnce() {
        CorestraSessionHttpHandler handler = linkedHandler(AuthRetryPolicy.LOGIN_AND_RETRY_ONCE);

        HttpRequest sent = handler.handleHttpRequestToBeSent(request(1, List.of())).request();
        assertEquals("token=old", sent.headerValue("Cookie"));

        HttpResponse delivered = handler.handleHttpResponseReceived(response(1, sent, 401, "denied")).response();

        assertEquals(1, loginRuns.get());
        assertEquals(1, resent.size(), "The failed request is sent once more");
        assertEquals("token=new", resent.getFirst().headerValue("Cookie"));
        assertEquals(200, delivered.statusCode(), "Burp receives the response of the retried request");
        assertEquals("new", accessRepository.authProfile("alice").orElseThrow().state().variables().get("token"));
        assertTrue(diagnostics.stream().anyMatch(message -> message.contains("re-authenticated")), diagnostics.toString());
    }

    @Test
    void concurrentFailuresFromTheSameSessionTriggerOnlyOneLogin() {
        CorestraSessionHttpHandler handler = linkedHandler(AuthRetryPolicy.LOGIN_AND_RETRY_ONCE);
        HttpRequest first = handler.handleHttpRequestToBeSent(request(1, List.of())).request();
        HttpRequest second = handler.handleHttpRequestToBeSent(request(2, List.of())).request();

        handler.handleHttpResponseReceived(response(1, first, 401, "denied"));
        HttpResponse delivered = handler.handleHttpResponseReceived(response(2, second, 401, "denied")).response();

        assertEquals(1, loginRuns.get(), "The second stale response reuses the new session");
        assertEquals(2, resent.size());
        assertEquals("token=new", resent.get(1).headerValue("Cookie"));
        assertEquals(200, delivered.statusCode());
    }

    @Test
    void responsesThatDoNotMatchFailureRulesAreUntouched() {
        CorestraSessionHttpHandler handler = linkedHandler(AuthRetryPolicy.LOGIN_AND_RETRY_ONCE);
        HttpRequest sent = handler.handleHttpRequestToBeSent(request(1, List.of())).request();
        HttpResponseReceived original = response(1, sent, 403, "forbidden by business rule");

        HttpResponse delivered = handler.handleHttpResponseReceived(original).response();

        assertSame(original, delivered);
        assertEquals(0, loginRuns.get());
        assertTrue(resent.isEmpty());
    }

    @Test
    void noRetryPolicyLeavesTheLiveResponseAlone() {
        CorestraSessionHttpHandler handler = linkedHandler(AuthRetryPolicy.NO_RETRY);
        HttpRequest sent = handler.handleHttpRequestToBeSent(request(1, List.of())).request();
        HttpResponseReceived original = response(1, sent, 401, "denied");

        assertSame(original, handler.handleHttpResponseReceived(original).response());
        assertEquals(0, loginRuns.get());
        assertTrue(resent.isEmpty());
    }

    @Test
    void failedReauthenticationBacksOffInsteadOfLoggingInOnEveryResponse() {
        CorestraSessionHttpHandler handler = linkedHandler(AuthRetryPolicy.LOGIN_AND_RETRY_ONCE);
        loginFails.set(true);
        HttpRequest first = handler.handleHttpRequestToBeSent(request(1, List.of())).request();
        HttpResponseReceived failed = response(1, first, 401, "denied");

        assertSame(failed, handler.handleHttpResponseReceived(failed).response());
        HttpRequest second = handler.handleHttpRequestToBeSent(request(2, List.of())).request();
        handler.handleHttpResponseReceived(response(2, second, 401, "denied"));

        assertEquals(1, loginRuns.get(), "Repeated failures must not hammer the login endpoint");
        assertTrue(resent.isEmpty());
        assertTrue(diagnostics.stream().anyMatch(message -> message.contains("failed")), diagnostics.toString());
    }

    @Test
    void standaloneProfileUsesItsOwnFailureRules() {
        SessionManager manager = standaloneManager(AuthRetryPolicy.LOGIN_AND_RETRY_ONCE);
        CorestraSessionHttpHandler handler = handler(manager);

        HttpRequest sent = handler.handleHttpRequestToBeSent(request(1, List.of())).request();
        assertEquals("Bearer old", sent.headerValue("Authorization"));
        HttpResponse ignored = handler.handleHttpResponseReceived(response(1, sent, 200, "ok")).response();
        assertEquals(200, ignored.statusCode());
        assertEquals(0, loginRuns.get());

        HttpRequest next = handler.handleHttpRequestToBeSent(request(2, List.of())).request();
        HttpResponse delivered = handler.handleHttpResponseReceived(
                response(2, next, 200, "{\"error\":\"Session expired\"}")).response();

        assertEquals(1, loginRuns.get(), "BODY_CONTAINS failure rule triggers re-login");
        assertEquals("Bearer fresh", resent.getFirst().headerValue("Authorization"));
        assertEquals(200, delivered.statusCode());
        assertEquals("fresh", repository.profile(SessionProfile.GENERAL_ID).orElseThrow().state().values().get("accessToken"));
    }

    @Test
    void standaloneNoRetryPolicyLeavesTheResponseUntouched() {
        CorestraSessionHttpHandler handler = handler(standaloneManager(AuthRetryPolicy.NO_RETRY));
        HttpRequest sent = handler.handleHttpRequestToBeSent(request(1, List.of())).request();
        HttpResponseReceived original = response(1, sent, 401, "denied");

        assertSame(original, handler.handleHttpResponseReceived(original).response());
        assertEquals(0, loginRuns.get());
        assertTrue(resent.isEmpty());
    }

    @Test
    void standaloneFailureRulesRoundTripThroughJson() throws Exception {
        SessionAuthConfig config = new SessionAuthConfig("login", 30, List.of(),
                List.of(new AuthFailureRule("r1", "401", true, AuthRuleType.STATUS_CODE, "401", "")),
                AuthRetryPolicy.NO_RETRY);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        String json = mapper.writeValueAsString(config);
        SessionAuthConfig restored = mapper.readValue(json, SessionAuthConfig.class);
        SessionAuthConfig empty = mapper.readValue(
                "{\"mainAuthFlowId\":\"login\",\"ttlSeconds\":30,\"triggerCriteria\":[]}", SessionAuthConfig.class);

        assertEquals(config, restored);
        assertFalse(json.contains("reloginStatusCodes"), json);
        assertTrue(empty.failureRules().isEmpty());
        assertEquals(AuthRetryPolicy.LOGIN_AND_RETRY_ONCE, empty.retryPolicy());
    }

    @Test
    void legacyReloginFieldsMigrateToFailureRules() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        SessionAuthConfig legacy = mapper.readValue("{\"mainAuthFlowId\":\"login\",\"ttlSeconds\":30,"
                + "\"triggerCriteria\":[],\"reloginStatusCodes\":\"401,419\","
                + "\"reloginBodyContains\":\"expired\",\"resendAfterRelogin\":false}", SessionAuthConfig.class);

        assertEquals(List.of(AuthRuleType.STATUS_CODE, AuthRuleType.STATUS_CODE, AuthRuleType.BODY_CONTAINS),
                legacy.failureRules().stream().map(AuthFailureRule::type).toList());
        assertEquals(List.of("401", "419", "expired"),
                legacy.failureRules().stream().map(AuthFailureRule::expression).toList());
        assertTrue(legacy.failureRules().stream().allMatch(AuthFailureRule::enabled));
        assertEquals(AuthRetryPolicy.LOGIN_AND_RETRY_ONCE, legacy.retryPolicy());
    }

    @Test
    void standaloneHasNoRefreshFlowSoRefreshPoliciesMeanLogin() {
        SessionAuthConfig config = new SessionAuthConfig("login", 0, List.of(), List.of(),
                AuthRetryPolicy.REFRESH_THEN_LOGIN_AND_RETRY_ONCE);
        assertEquals(AuthRetryPolicy.LOGIN_AND_RETRY_ONCE, config.retryPolicy());
    }

    private SessionManager standaloneManager(AuthRetryPolicy policy) {
        repository.saveProfile(SessionProfile.general().withConfig("General", SessionScope.custom(List.of()),
                        new SessionAuthConfig("login", 0, List.of(), List.of(
                                new AuthFailureRule("s401", "401", true, AuthRuleType.STATUS_CODE, "401", ""),
                                new AuthFailureRule("body", "expired", true, AuthRuleType.BODY_CONTAINS,
                                        "Session expired", "")), policy),
                        new SessionSchedulerConfig(false, 60, false),
                        List.of(new SessionInjectionRule("auth", true, InjectRule.Target.REQUEST_HEADER,
                                "Authorization", "Bearer {{session.accessToken}}", InjectRule.WriteStrategy.OVERWRITE,
                                List.of("PROXY"))),
                        List.of())
                .withState(new SessionState(Map.of("accessToken", "old"), 1_000, 0, 1_000)));
        FlowDefinition loginFlow = new FlowDefinition("login", "Login", "", true, List.of(new FlowStep("s", "s", true, "",
                new StoredRequestTemplate("POST", "https", "example.com", 443, "/login", List.of(), ""),
                List.of(new ExtractRule("t", "Token", true, ExtractRule.SourceSide.RESPONSE,
                        ExtractRule.ExtractorType.HEADER, "X-Token", "session.accessToken", List.of())),
                List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW)), null, null, null);
        SessionManager manager = new SessionManager(repository, () -> List.of(loginFlow),
                TestFlowEngineFactory.withHttpClient(request -> {
                    loginRuns.incrementAndGet();
                    return plainResponse(200, List.of(header("X-Token", "fresh")), "");
                }), new ExtractionService(), CLOCK);
        return manager;
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private CorestraSessionHttpHandler linkedHandler(AuthRetryPolicy policy) {
        AuthProvider provider = new AuthProvider("provider", "Login", "", true, "login", "", "",
                List.of(new AuthInjectionRule("token", "token", true, AuthInjectionLocation.COOKIE, "token",
                        "{{auth.token}}", AuthInjectionMode.OVERRIDE)),
                List.of(),
                List.of(new AuthFailureRule("unauthorized", "401", true, AuthRuleType.STATUS_CODE, "401", "")),
                AuthFallbackPolicy.LOGIN_ONLY, policy, 3_600L);
        accessRepository.saveAuthProvider(provider);
        accessRepository.saveAuthProfile(new AuthProfile("alice", "Alice", "USER", provider.id(), Map.of(),
                new AuthState(Map.of("token", "old"), Map.of(), Map.of(), 10_000_000, 1_000), true));
        repository.saveProfile(new SessionProfile(SessionProfile.GENERAL_ID, "General", true, true,
                SessionScope.custom(List.of()), "alice", SessionAuthConfig.defaults(),
                new SessionSchedulerConfig(false, 60, false),
                new SessionState(Map.of(), 1_000, 10_000_000, 1_000), List.of(), List.of()));
        AuthManager authManager = new AuthManager(accessRepository, (flowId, actor, p, env, purpose, mode) -> {
            loginRuns.incrementAndGet();
            return loginFails.get()
                    ? AuthFlowRunResult.executed(false, "bad credentials", 401, Map.of(), "", List.of())
                    : AuthFlowRunResult.executed(true, "ok", 200, Map.of("token", "new"), "", List.of());
        }, new AuthRuleEvaluator(), CLOCK);
        SessionManager manager = new SessionManager(repository, List::of,
                TestFlowEngineFactory.withHttpClient(r -> plainResponse(200, List.of(), "")), new ExtractionService(),
                CLOCK, accessRepository, authManager, new AuthInjectionRuleRenderer());
        return handler(manager);
    }

    private CorestraSessionHttpHandler handler(SessionManager manager) {
        CorestraSessionHttpHandler handler = new CorestraSessionHttpHandler(manager, new InjectionService());
        handler.setDiagnostics(diagnostics::add);
        handler.setRetrySender(request -> {
            resent.add(request);
            return plainResponse(200, List.of(), "retried");
        });
        return handler;
    }

    private static HttpRequestToBeSent request(int id, List<HttpHeader> headers) {
        HttpService service = proxy(HttpService.class, (p, m, a) -> switch (m.getName()) {
            case "host" -> "example.com";
            case "port" -> 443;
            case "secure" -> true;
            default -> null;
        });
        ToolSource tool = proxy(ToolSource.class, (p, m, a) -> m.getName().equals("toolType") ? ToolType.PROXY : null);
        return proxy(HttpRequestToBeSent.class, (p, m, a) -> switch (m.getName()) {
            case "messageId" -> id;
            case "toolSource" -> tool;
            case "isInScope" -> true;
            case "method" -> "GET";
            case "path" -> "/api";
            case "httpVersion" -> "HTTP/1.1";
            case "httpService" -> service;
            case "bodyToString" -> "";
            case "headers" -> headers;
            case "headerValue" -> headers.stream().filter(h -> h.name().equalsIgnoreCase((String) a[0]))
                    .map(HttpHeader::value).findFirst().orElse(null);
            case "hasHeader" -> headers.stream().anyMatch(h -> h.name().equalsIgnoreCase((String) a[0]));
            case "withHeader" -> {
                List<HttpHeader> updated = new ArrayList<>(headers.stream()
                        .filter(h -> !h.name().equalsIgnoreCase((String) a[0])).toList());
                updated.add(header((String) a[0], (String) a[1]));
                yield request(id, updated);
            }
            case "withMethod", "withPath" -> request(id, headers);
            case "withRemovedHeaders" -> request(id, List.of());
            case "withAddedHeaders" -> {
                List<HttpHeader> combined = new ArrayList<>(headers);
                @SuppressWarnings("unchecked") List<HttpHeader> added = (List<HttpHeader>) a[0];
                combined.addAll(added);
                yield request(id, combined);
            }
            case "withRemovedHeader" -> request(id,
                    headers.stream().filter(h -> !h.name().equalsIgnoreCase((String) a[0])).toList());
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private static HttpResponseReceived response(int id, HttpRequest request, int status, String body) {
        return proxy(HttpResponseReceived.class, (p, m, a) -> switch (m.getName()) {
            case "messageId" -> id;
            case "initiatingRequest" -> request;
            case "toolSource" -> proxy(ToolSource.class,
                    (q, n, b) -> n.getName().equals("toolType") ? ToolType.PROXY : null);
            case "statusCode" -> (short) status;
            case "httpVersion" -> "HTTP/1.1";
            case "reasonPhrase" -> "";
            case "headers" -> List.<HttpHeader>of();
            case "bodyToString" -> body;
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private static HttpResponse plainResponse(int status, List<HttpHeader> headers, String body) {
        return proxy(HttpResponse.class, (p, m, a) -> switch (m.getName()) {
            case "statusCode" -> (short) status;
            case "reasonPhrase" -> "OK";
            case "httpVersion" -> "HTTP/1.1";
            case "headers" -> headers;
            case "bodyToString" -> body;
            case "toString" -> "HTTP/1.1 " + status + " OK\r\n\r\n" + body;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private static HttpHeader header(String name, String value) {
        return proxy(HttpHeader.class, (p, m, a) -> switch (m.getName()) {
            case "name" -> name;
            case "value" -> value;
            case "toString" -> name + ": " + value;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler invocationHandler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, invocationHandler));
    }
}
