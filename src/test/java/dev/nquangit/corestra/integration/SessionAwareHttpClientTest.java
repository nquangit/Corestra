package dev.nquangit.corestra.integration;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.session.SessionAuthConfig;
import dev.nquangit.corestra.session.SessionGuard;
import dev.nquangit.corestra.session.SessionInjectionRule;
import dev.nquangit.corestra.session.SessionManager;
import dev.nquangit.corestra.session.SessionProfile;
import dev.nquangit.corestra.session.SessionProfileRepository;
import dev.nquangit.corestra.session.SessionSchedulerConfig;
import dev.nquangit.corestra.session.SessionScope;
import dev.nquangit.corestra.session.SessionScopeCriterion;
import dev.nquangit.corestra.session.SessionScopeMode;
import dev.nquangit.corestra.session.SessionState;
import dev.nquangit.corestra.flow.rule.InjectRule;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionAwareHttpClientTest {

    // ---------------------------------------------------------------------------
    // Guard cleanup — ensure the guard is always exited between tests
    // ---------------------------------------------------------------------------

    @AfterEach
    void clearGuard() {
        while (SessionGuard.isActive()) {
            SessionGuard.exit();
        }
    }

    // ---------------------------------------------------------------------------
    // Constructor guards
    // ---------------------------------------------------------------------------

    @Test
    void constructor_rejectsNullDelegate() {
        assertThrows(NullPointerException.class, () ->
                new SessionAwareHttpClient(null, () -> null, new InjectionService()));
    }

    @Test
    void constructor_rejectsNullSupplier() {
        HttpClient noop = request -> stubResponse();
        assertThrows(NullPointerException.class, () ->
                new SessionAwareHttpClient(noop, null, new InjectionService()));
    }

    @Test
    void constructor_rejectsNullInjectionService() {
        HttpClient noop = request -> stubResponse();
        assertThrows(NullPointerException.class, () ->
                new SessionAwareHttpClient(noop, () -> null, null));
    }

    // ---------------------------------------------------------------------------
    // Test 1: when SessionGuard is active — delegate directly without injection
    // ---------------------------------------------------------------------------

    @Test
    void whenGuardIsActive_delegatesDirectly() {
        List<HttpRequest> delegateCalls = new ArrayList<>();
        HttpResponse stubResp = stubResponse();
        HttpClient delegate = request -> {
            delegateCalls.add(request);
            return stubResp;
        };

        // Build a manager that would match (general profile, in-scope) — but guard prevents it.
        SessionManager manager = managerWithInjectionRule();
        SessionAwareHttpClient client = new SessionAwareHttpClient(delegate, () -> manager, new InjectionService());

        HttpRequest request = stubRequest("GET", "api.example", "/api");

        SessionGuard.enter();
        try {
            HttpResponse response = client.send(request);
            assertSame(stubResp, response);
            assertEquals(1, delegateCalls.size(), "Delegate should have been called exactly once");
            assertSame(request, delegateCalls.get(0), "Original unmodified request must be forwarded");
        } finally {
            SessionGuard.exit();
        }
    }

    // ---------------------------------------------------------------------------
    // Test 2: when manager supplier returns null — delegate directly, no NPE
    // ---------------------------------------------------------------------------

    @Test
    void whenManagerIsNull_delegatesDirectly() {
        List<HttpRequest> delegateCalls = new ArrayList<>();
        HttpResponse stubResp = stubResponse();
        HttpClient delegate = request -> {
            delegateCalls.add(request);
            return stubResp;
        };

        SessionAwareHttpClient client = new SessionAwareHttpClient(delegate, () -> null, new InjectionService());

        HttpRequest request = stubRequest("GET", "api.example", "/api");
        HttpResponse response = client.send(request);

        assertSame(stubResp, response);
        assertEquals(1, delegateCalls.size());
        assertSame(request, delegateCalls.get(0), "Original unmodified request must be forwarded");
    }

    // ---------------------------------------------------------------------------
    // Test 3: when no profile matches — delegate directly
    // ---------------------------------------------------------------------------

    @Test
    void whenNoProfileMatches_delegatesDirectly() {
        List<HttpRequest> delegateCalls = new ArrayList<>();
        HttpResponse stubResp = stubResponse();
        HttpClient delegate = request -> {
            delegateCalls.add(request);
            return stubResp;
        };

        // Create a manager that has no matching profile for the given host.
        // The general profile matches BURP_TARGET_SCOPE in-scope, so use out-of-scope.
        SessionProfileRepository repository = new SessionProfileRepository();
        // Override general so it has a custom scope that won't match our request host
        SessionProfile generalNoMatch = new SessionProfile(
                SessionProfile.GENERAL_ID,
                "General",
                true,
                true,
                new SessionScope(SessionScopeMode.CUSTOM, List.of(
                        new SessionScopeCriterion(
                                dev.nquangit.corestra.session.SessionCriterionType.HOST,
                                "",
                                "should-never-match\\.example"))),
                SessionAuthConfig.defaults(),
                SessionSchedulerConfig.disabled(),
                SessionState.empty(),
                List.of(),
                List.of());
        repository.saveProfile(generalNoMatch);
        SessionManager manager = manager(repository);

        SessionAwareHttpClient client = new SessionAwareHttpClient(delegate, () -> manager, new InjectionService());

        // Request to a host that doesn't match the custom scope.
        HttpRequest request = stubRequest("GET", "other.example", "/api");
        HttpResponse response = client.send(request);

        assertSame(stubResp, response);
        assertEquals(1, delegateCalls.size());
        assertSame(request, delegateCalls.get(0));
    }

    // ---------------------------------------------------------------------------
    // Test 4: injection path — guard is entered and exited correctly even when
    //         no rules are returned (profile matched but no inject rules).
    // ---------------------------------------------------------------------------

    @Test
    void whenProfileMatchesButNoRules_delegatesDirectlyAndGuardIsReleased() {
        HttpResponse stubResp = stubResponse();
        HttpClient delegate = request -> stubResp;

        // General profile with no injection rules; will match any in-scope request.
        SessionProfileRepository repository = new SessionProfileRepository();
        // Default general profile has no rules.
        SessionManager manager = manager(repository);

        SessionAwareHttpClient client = new SessionAwareHttpClient(delegate, () -> manager, new InjectionService());
        HttpRequest request = stubRequest("GET", "api.example", "/api");

        // Set request in-scope via stub that returns true for isInScope()
        HttpRequest inScopeRequest = stubRequest("GET", "api.example", "/api", true);
        HttpResponse response = client.send(inScopeRequest);

        assertSame(stubResp, response);
        assertFalse(SessionGuard.isActive(), "Guard must be released after send() returns");
    }

    // ---------------------------------------------------------------------------
    // Test 5: guard is released even if ensureAuthenticated throws
    // ---------------------------------------------------------------------------

    @Test
    void guardIsReleasedWhenDelegateSendThrows() {
        HttpClient throwingDelegate = request -> {
            throw new RuntimeException("network error");
        };

        // We need a manager that matches AND has injection rules so we reach delegate.send()
        SessionManager manager = managerWithInjectionRuleAndState();

        SessionAwareHttpClient client = new SessionAwareHttpClient(
                throwingDelegate, () -> manager, new InjectionService());

        HttpRequest request = stubRequest("GET", "api.example", "/api", true);

        assertThrows(RuntimeException.class, () -> client.send(request));
        assertFalse(SessionGuard.isActive(), "Guard must be released even when delegate throws");
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Builds a {@link SessionManager} backed by an in-memory repository that has the default
     * General profile with no injection rules. {@code selectProfile} will still return the
     * General profile for in-scope requests, but no rules are rendered.
     */
    private static SessionManager managerWithInjectionRule() {
        SessionProfileRepository repository = new SessionProfileRepository();
        // Default repository already has the General profile with no injection rules.
        return manager(repository);
    }

    /**
     * Builds a manager where the General profile has a STATIC injection rule so it can
     * reach delegate.send() — needed for guard-released-on-throw test.
     */
    private static SessionManager managerWithInjectionRuleAndState() {
        SessionProfileRepository repository = new SessionProfileRepository();
        SessionProfile general = SessionProfile.general().withConfig(
                "General",
                SessionScope.custom(List.of()),
                SessionAuthConfig.defaults(),
                SessionSchedulerConfig.disabled(),
                List.of(new SessionInjectionRule(
                        "inject-auth",
                        true,
                        InjectRule.Target.REQUEST_HEADER,
                        "Authorization",
                        "Bearer static-test-token",
                        InjectRule.WriteStrategy.OVERWRITE,
                        List.of("PROXY"))),
                List.of());
        repository.saveProfile(general);
        return manager(repository);
    }

    private static SessionManager manager(SessionProfileRepository repository) {
        ExtractionService extractionService = new ExtractionService();
        InjectionService injectionService = new InjectionService();
        FlowEngine flowEngine = new FlowEngine(extractionService, injectionService, new NoopHttpClient());
        return new SessionManager(
                repository,
                List::of,
                flowEngine,
                extractionService,
                Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC));
    }

    private static HttpResponse stubResponse() {
        return (HttpResponse) Proxy.newProxyInstance(
                HttpResponse.class.getClassLoader(),
                new Class<?>[]{HttpResponse.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "statusCode" -> (short) 200;
                    case "bodyToString" -> "";
                    case "toString" -> "StubResponse";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /**
     * Creates a stub {@link HttpRequest} that returns the given method, host, and path.
     * {@code isInScope()} returns {@code false}.
     */
    private static HttpRequest stubRequest(String method, String host, String path) {
        return stubRequest(method, host, path, false);
    }

    /**
     * Creates a stub {@link HttpRequest} that returns the given method, host, and path,
     * with a configurable in-scope flag.
     */
    private static HttpRequest stubRequest(String method, String host, String path, boolean inScope) {
        burp.api.montoya.http.HttpService service = stubService(host, 443, true);
        return (HttpRequest) Proxy.newProxyInstance(
                HttpRequest.class.getClassLoader(),
                new Class<?>[]{HttpRequest.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "method" -> method;
                    case "path" -> path;
                    case "bodyToString" -> "";
                    case "isInScope" -> inScope;
                    case "httpService" -> service;
                    case "headers" ->
                            List.of(stubHeader("Host", host), stubHeader("Accept", "*/*"));
                    case "toString" -> "StubRequest[" + method + " " + host + path + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static burp.api.montoya.http.HttpService stubService(String host, int port, boolean secure) {
        return (burp.api.montoya.http.HttpService) Proxy.newProxyInstance(
                burp.api.montoya.http.HttpService.class.getClassLoader(),
                new Class<?>[]{burp.api.montoya.http.HttpService.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "host" -> host;
                    case "port" -> port;
                    case "secure" -> secure;
                    case "toString" -> host + ":" + port;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static burp.api.montoya.http.message.HttpHeader stubHeader(String name, String value) {
        return (burp.api.montoya.http.message.HttpHeader) Proxy.newProxyInstance(
                burp.api.montoya.http.message.HttpHeader.class.getClassLoader(),
                new Class<?>[]{burp.api.montoya.http.message.HttpHeader.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "name" -> name;
                    case "value" -> value;
                    case "toString" -> name + ": " + value;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static final class NoopHttpClient implements HttpClient {
        @Override
        public HttpResponse send(HttpRequest request) {
            return stubResponse();
        }
    }
}
