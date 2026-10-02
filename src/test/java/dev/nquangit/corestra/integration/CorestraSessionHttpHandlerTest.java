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
import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthInjectionLocation;
import dev.nquangit.corestra.access.auth.AuthInjectionMode;
import dev.nquangit.corestra.access.auth.AuthInjectionRule;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
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
import dev.nquangit.corestra.session.SessionExtractionRule;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("MontoyaObjectFactory")
class CorestraSessionHttpHandlerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(61_000), ZoneOffset.UTC);
    private MontoyaObjectFactory previousFactory;
    private SessionProfileRepository repository;
    private SessionManager manager;
    private CorestraSessionHttpHandler handler;

    @BeforeEach
    void setUp() {
        previousFactory = ObjectFactoryLocator.FACTORY;
        ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p, m, a) -> switch (m.getName()) {
            case "httpHeader" -> header((String) a[0], (String) a[1]);
            case "requestResult" -> proxy(RequestToBeSentAction.class,
                    (q, n, b) -> n.getName().equals("request") ? a[0] : null);
            case "responseResult" -> proxy(ResponseReceivedAction.class,
                    (q, n, b) -> n.getName().equals("response") ? a[0] : null);
            default -> throw new UnsupportedOperationException(m.getName());
        });
        repository = new SessionProfileRepository();
        repository.saveProfile(profile("", List.of()));
        manager = manager(List.of(), r -> loginResponse("unused"));
        handler = new CorestraSessionHttpHandler(manager, new InjectionService());
    }

    @AfterEach
    void restoreFactory() {
        ObjectFactoryLocator.FACTORY = previousFactory;
    }

    @Test
    void proxyLoginResponseRemainsExcludedAfterAutoTriggerRemovesMarker() {
        HttpRequestToBeSent login = request(101, "/login", List.of(
                header(ProxyPipelineHttpClient.MARKER_HEADER, "internal-login")));
        handler.handleHttpRequestToBeSent(login);
        try (AutoTriggerHttpHandler autoTrigger = new AutoTriggerHttpHandler(
                List::of, TestFlowEngineFactory.withHttpClient(r -> loginResponse("unused")),
                new ExtractionService(), new InjectionService())) {
            HttpRequest wireRequest = autoTrigger.handleHttpRequestToBeSent(login).request();
            assertFalse(wireRequest.hasHeader(ProxyPipelineHttpClient.MARKER_HEADER));
            handler.handleHttpResponseReceived(response(101, wireRequest, "intermediate-login-token"));
        }
        assertEquals("old", stateToken());
    }

    @Test
    void copiedInitiatingRequestCannotBypassGenerationCheck() {
        HttpRequestToBeSent original = request(102, "/api", List.of());
        handler.handleHttpRequestToBeSent(original);
        manager.clearState(SessionProfile.GENERAL_ID);
        repository.updateState(SessionProfile.GENERAL_ID, ignored -> state("fresh"));

        // Montoya exposes the sent request as a separate immutable object on response callbacks.
        handler.handleHttpResponseReceived(response(102, request(102, "/api", List.of()), "stale"));

        assertEquals("fresh", stateToken());
    }

    @Test
    void currentResponseWithCopiedRequestStillUpdatesState() {
        handler.handleHttpRequestToBeSent(request(103, "/api", List.of()));
        handler.handleHttpResponseReceived(response(103, request(103, "/api", List.of()), "rotated"));
        assertEquals("rotated", stateToken());
    }

    @Test
    void responseWithoutRequestContextCannotChangeSessionState() {
        handler.handleHttpResponseReceived(response(104, request(104, "/api", List.of()), "unknown"));
        assertEquals("old", stateToken());
    }

    @Test
    void pendingContextsAreBoundedAndEvictionCannotBypassGenerationChecks() {
        handler = new CorestraSessionHttpHandler(manager, new InjectionService(), CLOCK, 2, 1_000);
        for (int id = 110; id <= 112; id++) {
            handler.handleHttpRequestToBeSent(request(id, "/api", List.of()));
        }
        handler.handleHttpResponseReceived(response(110, request(110, "/api", List.of()), "evicted"));
        assertEquals("old", stateToken());
        handler.handleHttpResponseReceived(response(111, request(111, "/api", List.of()), "current"));
        assertEquals("current", stateToken());
    }

    @Test
    void expiredContextCannotUpdateSessionState() {
        AtomicInteger elapsed = new AtomicInteger();
        Clock advancingClock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(millis()); }
            @Override public long millis() { return CLOCK.millis() + elapsed.get(); }
        };
        handler = new CorestraSessionHttpHandler(manager, new InjectionService(), advancingClock, 2, 1_000);
        handler.handleHttpRequestToBeSent(request(113, "/api", List.of()));
        elapsed.set(1_000);
        handler.handleHttpResponseReceived(response(113, request(113, "/api", List.of()), "expired"));
        assertEquals("old", stateToken());
    }

    @Test
    void closingHandlerDiscardsPendingAndNewRequests() {
        handler.handleHttpRequestToBeSent(request(114, "/api", List.of()));
        handler.close();
        handler.handleHttpResponseReceived(response(114, request(114, "/api", List.of()), "pending"));
        handler.handleHttpRequestToBeSent(request(115, "/api", List.of()));
        handler.handleHttpResponseReceived(response(115, request(115, "/api", List.of()), "new"));
        assertEquals("old", stateToken());
    }

    @Test
    void linkedAuthRenewalInjectsBothNewCookiesAndIgnoresLateOldAndInternalResponses() throws Exception {
        InMemoryAccessRepository accessRepository = new InMemoryAccessRepository();
        Map<String, String> oldPair = Map.of("JSESSIONID", "old-id", "token", "old-token");
        Map<String, String> newPair = Map.of("JSESSIONID", "new-id", "token", "new-token");
        AuthProvider provider = new AuthProvider("provider", "Login", "", true, "login", "", "",
                List.of(cookieRule("JSESSIONID"), cookieRule("token")), List.of(), List.of(),
                AuthFallbackPolicy.LOGIN_ONLY, AuthRetryPolicy.NO_RETRY, 300L);
        accessRepository.saveAuthProvider(provider);
        accessRepository.saveAuthProfile(new AuthProfile("alice", "Alice", "USER", provider.id(), Map.of(),
                new AuthState(oldPair, Map.of(), Map.of(), 1_000_000, 1_000), true));
        repository.saveProfile(new SessionProfile(SessionProfile.GENERAL_ID, "General", true, true,
                SessionScope.custom(List.of()), "alice", SessionAuthConfig.defaults(),
                new SessionSchedulerConfig(true, 60, false),
                new SessionState(Map.of(), 1_000, 1_000_000, 1_000),
                List.of(new SessionInjectionRule("override-token", true, InjectRule.Target.REQUEST_COOKIE,
                        "token", "{{auth.token}}", InjectRule.WriteStrategy.OVERWRITE, List.of("PROXY"))),
                List.of(new SessionExtractionRule("extract-token", true, ExtractRule.SourceSide.RESPONSE,
                        ExtractRule.ExtractorType.COOKIE, "token", "token", List.of("PROXY")))));
        CountDownLatch loginStarted = new CountDownLatch(1);
        CountDownLatch finishLogin = new CountDownLatch(1);
        AtomicInteger loginRuns = new AtomicInteger();
        AuthManager authManager = new AuthManager(accessRepository, (flowId, actor, p, env, purpose, mode) -> {
            loginRuns.incrementAndGet();
            handler.handleHttpRequestToBeSent(request(301, "/login", List.of(
                    header(ProxyPipelineHttpClient.MARKER_HEADER, "internal-login"))));
            handler.handleHttpResponseReceived(cookieResponse(301, request(301, "/login", List.of()),
                    "intermediate-token"));
            loginStarted.countDown();
            try {
                assertTrue(finishLogin.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return AuthFlowRunResult.executed(true, "ok", 200, newPair, "", List.of());
        }, new AuthRuleEvaluator(), CLOCK);
        manager = new SessionManager(repository, List::of,
                TestFlowEngineFactory.withHttpClient(r -> loginResponse("unused")), new ExtractionService(),
                CLOCK, accessRepository, authManager, new AuthInjectionRuleRenderer());
        handler = new CorestraSessionHttpHandler(manager, new InjectionService());
        handler.handleHttpRequestToBeSent(request(300, "/api", List.of()));
        var executor = Executors.newFixedThreadPool(3);
        try {
            var renewal = executor.submit(() -> manager.refreshScheduledProfile(SessionProfile.GENERAL_ID));
            assertTrue(loginStarted.await(5, TimeUnit.SECONDS));
            assertEquals(oldPair, accessRepository.authProfile("alice").orElseThrow().state().variables());
            var outgoing = executor.submit(() -> handler.handleHttpRequestToBeSent(request(302, "/api", List.of(
                    header("Cookie", "JSESSIONID=old-id; token=old-token; preference=keep")))).request());
            var lateOldResponse = executor.submit(() -> handler.handleHttpResponseReceived(
                    cookieResponse(300, request(300, "/api", List.of()), "stale-token")));
            awaitQueuedProfileRequest(manager);
            assertFalse(outgoing.isDone(), "Both cookies must wait for the completed login");
            finishLogin.countDown();
            renewal.get(5, TimeUnit.SECONDS);
            HttpRequest sent = outgoing.get(5, TimeUnit.SECONDS);
            lateOldResponse.get(5, TimeUnit.SECONDS);
            assertEquals("JSESSIONID=new-id; token=new-token; preference=keep", sent.headerValue("Cookie"));
            assertEquals(newPair, accessRepository.authProfile("alice").orElseThrow().state().variables());
            assertEquals(1, loginRuns.get(), "Waiting traffic must reuse the scheduled login");
            // An unmarked internal response arriving after publication must also remain excluded.
            handler.handleHttpResponseReceived(cookieResponse(301, request(301, "/login", List.of()), "late-login"));
            assertEquals(newPair, accessRepository.authProfile("alice").orElseThrow().state().variables());
            handler.handleHttpResponseReceived(cookieResponse(302, request(302, "/api", List.of()), "rotated-token"));
            assertEquals("rotated-token", accessRepository.authProfile("alice").orElseThrow().state().variables().get("token"));
        } finally {
            finishLogin.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static AuthInjectionRule cookieRule(String name) {
        return new AuthInjectionRule(name, name, true, AuthInjectionLocation.COOKIE, name,
                "{{auth." + name + "}}", AuthInjectionMode.OVERRIDE);
    }

    @Test
    void scheduledMultiStepProxyLoginBlocksRequestThenInjectsCompleteNewSession() throws Exception {
        CountDownLatch loginStarted = new CountDownLatch(1);
        CountDownLatch finishLogin = new CountDownLatch(1);
        AtomicInteger loginSteps = new AtomicInteger();
        List<SessionInjectionRule> rules = List.of(
                new SessionInjectionRule("token", true, InjectRule.Target.REQUEST_HEADER, "Authorization",
                        "Bearer {{session.accessToken}}", InjectRule.WriteStrategy.OVERWRITE, List.of("PROXY")),
                new SessionInjectionRule("cookie", true, InjectRule.Target.REQUEST_COOKIE, "sid",
                        "{{session.sid}}", InjectRule.WriteStrategy.OVERWRITE, List.of("PROXY")));
        repository.saveProfile(profile("login", rules));
        FlowDefinition loginFlow = new FlowDefinition("login", "Login", "", true,
                List.of(step("begin", List.of()), step("finish", List.of(
                        new ExtractRule("token", "Token", true, ExtractRule.SourceSide.RESPONSE,
                                ExtractRule.ExtractorType.HEADER, "X-Token", "session.accessToken", List.of()),
                        new ExtractRule("sid", "Session cookie", true, ExtractRule.SourceSide.RESPONSE,
                                ExtractRule.ExtractorType.COOKIE, "sid", "session.sid", List.of())))),
                null, null, null);
        manager = manager(List.of(loginFlow), r -> {
            int step = loginSteps.incrementAndGet();
            if (step == 1) {
                // Model the internal login request traversing Burp Proxy and losing its marker.
                HttpRequestToBeSent internal = request(201, "/login", List.of(
                        header(ProxyPipelineHttpClient.MARKER_HEADER, "login")));
                handler.handleHttpRequestToBeSent(internal);
                handler.handleHttpResponseReceived(response(201,
                        request(201, "/login", List.of()), "intermediate"));
                loginStarted.countDown();
                try {
                    assertTrue(finishLogin.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return loginResponse(step == 1 ? "intermediate" : "fresh");
        });
        handler = new CorestraSessionHttpHandler(manager, new InjectionService());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var renewal = executor.submit(() -> manager.refreshScheduledProfile(SessionProfile.GENERAL_ID));
            assertTrue(loginStarted.await(5, TimeUnit.SECONDS));
            assertEquals("old", stateToken(), "Internal login response must not modify the live state mid-flow");
            var outgoing = executor.submit(() -> handler.handleHttpRequestToBeSent(request(202, "/api", List.of(
                    header("Authorization", "Bearer old"), header("Cookie", "sid=old-sid; preference=keep")))).request());
            awaitQueuedProfileRequest(manager);
            assertFalse(outgoing.isDone(), "User request must wait until all login steps finish");
            finishLogin.countDown();
            renewal.get(5, TimeUnit.SECONDS);
            HttpRequest sent = outgoing.get(5, TimeUnit.SECONDS);
            assertEquals("Bearer fresh", sent.headerValue("Authorization"));
            assertEquals("sid=fresh-sid; preference=keep", sent.headerValue("Cookie"));
            assertEquals(2, loginSteps.get(), "Waiting request must not run another login");
            assertEquals("fresh", stateToken());
        } finally {
            finishLogin.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private String stateToken() {
        return repository.profile(SessionProfile.GENERAL_ID).orElseThrow().state().values().get("accessToken");
    }

    private SessionManager manager(List<FlowDefinition> flows, HttpClient client) {
        return new SessionManager(repository, () -> flows, TestFlowEngineFactory.withHttpClient(client),
                new ExtractionService(), CLOCK);
    }

    private static SessionState state(String token) {
        return new SessionState(Map.of("accessToken", token, "sid", token + "-sid"), 1_000, 1_000_000, 1_000);
    }

    private static SessionProfile profile(String flowId, List<SessionInjectionRule> injectionRules) {
        return SessionProfile.general().withConfig("General", SessionScope.custom(List.of()),
                new SessionAuthConfig(flowId, 180, List.of()), new SessionSchedulerConfig(true, 60, false),
                injectionRules, List.of(new SessionExtractionRule("extract", true, ExtractRule.SourceSide.RESPONSE,
                        ExtractRule.ExtractorType.HEADER, "X-Token", "accessToken", List.of("PROXY"))))
                .withState(state("old"));
    }

    private static FlowStep step(String id, List<ExtractRule> extracts) {
        return new FlowStep(id, id, true, "", new StoredRequestTemplate("POST", "https", "example.com",
                443, "/login", List.of(), ""), extracts, List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
    }

    private static void awaitQueuedProfileRequest(SessionManager manager) throws Exception {
        var field = SessionManager.class.getDeclaredField("locks");
        field.setAccessible(true);
        var locks = (Map<?, ?>) field.get(manager);
        ReentrantLock lock = (ReentrantLock) locks.get(SessionProfile.GENERAL_ID);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!lock.hasQueuedThreads() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(lock.hasQueuedThreads(), "Request should be queued on the authentication lock");
    }

    private static HttpRequestToBeSent request(int id, String path, List<HttpHeader> headers) {
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
            case "path" -> path;
            case "httpVersion" -> "HTTP/1.1";
            case "httpService" -> service;
            case "bodyToString" -> "";
            case "headers" -> headers;
            case "headerValue" -> headers.stream().filter(h -> h.name().equalsIgnoreCase((String) a[0]))
                    .map(HttpHeader::value).findFirst().orElse(null);
            case "hasHeader" -> headers.stream().anyMatch(h -> h.name().equalsIgnoreCase((String) a[0]));
            case "withMethod" -> request(id, path, headers);
            case "withPath" -> request(id, (String) a[0], headers);
            case "withRemovedHeaders" -> request(id, path, List.of());
            case "withAddedHeaders" -> {
                List<HttpHeader> combined = new ArrayList<>(headers);
                @SuppressWarnings("unchecked") List<HttpHeader> added = (List<HttpHeader>) a[0];
                combined.addAll(added);
                yield request(id, path, combined);
            }
            case "withRemovedHeader" -> request(id, path,
                    headers.stream().filter(h -> !h.name().equalsIgnoreCase((String) a[0])).toList());
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private static HttpResponseReceived response(int id, HttpRequest request, String token) {
        return response(id, request, List.of(header("X-Token", token)));
    }

    private static HttpResponseReceived cookieResponse(int id, HttpRequest request, String token) {
        return response(id, request, List.of(header("Set-Cookie", "token=" + token + "; Path=/; HttpOnly")));
    }

    private static HttpResponseReceived response(int id, HttpRequest request, List<HttpHeader> headers) {
        return proxy(HttpResponseReceived.class, (p, m, a) -> switch (m.getName()) {
            case "messageId" -> id;
            case "initiatingRequest" -> request;
            case "toolSource" -> proxy(ToolSource.class,
                    (q, n, b) -> n.getName().equals("toolType") ? ToolType.PROXY : null);
            case "statusCode" -> (short) 200;
            case "httpVersion" -> "HTTP/1.1";
            case "headers" -> headers;
            case "bodyToString" -> "";
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private static HttpResponse loginResponse(String token) {
        return proxy(HttpResponse.class, (p, m, a) -> switch (m.getName()) {
            case "statusCode" -> (short) 200;
            case "reasonPhrase" -> "OK";
            case "httpVersion" -> "HTTP/1.1";
            case "headers" -> List.of(header("X-Token", token), header("Set-Cookie", "sid=" + token + "-sid"));
            case "bodyToString" -> "";
            case "toString" -> "HTTP/1.1 200 OK\r\nX-Token: " + token + "\r\nSet-Cookie: sid=" + token + "-sid\r\n\r\n";
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
