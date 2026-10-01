package dev.nquangit.corestra.session;

import burp.api.montoya.http.message.responses.HttpResponse;
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
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkedSessionAuthTest {
    @Test
    void linkedSessionReusesActorCredentialsStateAndProviderInjection() {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC);
        InMemoryAccessRepository accessRepository = new InMemoryAccessRepository();
        AuthProvider provider = provider();
        accessRepository.saveAuthProvider(provider);
        accessRepository.saveAuthProfile(new AuthProfile(
                "alice", "Alice", "USER", provider.id(),
                Map.of("username", "alice", "password", "secret"), AuthState.empty(), true));
        AtomicInteger loginRuns = new AtomicInteger();
        AuthManager authManager = new AuthManager(
                accessRepository,
                (flowId, profile, ignoredProvider, env, purpose, mode) -> {
                    loginRuns.incrementAndGet();
                    assertEquals("alice", profile.credentials().get("username"));
                    return AuthFlowRunResult.executed(
                            true, "ok", 200, Map.of("accessToken", "token-alice"), "", List.of());
                },
                new AuthRuleEvaluator(),
                clock);

        SessionProfileRepository sessionRepository = new SessionProfileRepository();
        SessionProfile binding = new SessionProfile(
                "proxy-user", "Proxy user", true, false, SessionScope.custom(List.of()),
                "alice", SessionAuthConfig.defaults(), SessionSchedulerConfig.disabled(),
                SessionState.empty(), List.of(), List.of());
        sessionRepository.saveProfile(binding);
        SessionManager manager = manager(sessionRepository, accessRepository, authManager, clock);

        SessionProfile authenticated = manager.ensureAuthenticated(binding, context());
        List<InjectRule> rules = manager.renderedInjectionRules(authenticated, "PROXY");
        manager.ensureAuthenticated(authenticated, context());

        assertEquals(1, loginRuns.get(), "provider TTL should prevent a second login");
        assertEquals(1, rules.size());
        assertEquals(InjectRule.Target.REQUEST_HEADER, rules.getFirst().target());
        assertEquals("Authorization", rules.getFirst().targetSelector());
        assertEquals("Bearer token-alice", rules.getFirst().staticValue());
        assertEquals("token-alice", accessRepository.authProfile("alice").orElseThrow()
                .state().variables().get("accessToken"));
        assertTrue(authenticated.state().values().isEmpty(), "session binding must not duplicate actor secrets");

        manager.forceAuthenticate(binding.id());
        assertEquals(2, loginRuns.get(), "manual/scheduled refresh must bypass the freshness check");
    }

    @Test
    void clearingLinkedSessionStatePreservesCredentials() {
        Clock clock = Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC);
        InMemoryAccessRepository accessRepository = new InMemoryAccessRepository();
        AuthProvider provider = provider();
        accessRepository.saveAuthProvider(provider);
        accessRepository.saveAuthProfile(new AuthProfile(
                "alice", "Alice", "USER", provider.id(), Map.of("username", "alice"),
                new AuthState(Map.of("accessToken", "token"), Map.of(), Map.of(), 10_000L, 1_000L), true));
        AuthManager authManager = new AuthManager(accessRepository,
                (flowId, profile, ignoredProvider, env, purpose, mode) -> AuthFlowRunResult.executed(
                        true, "ok", 200, Map.of(), "", List.of()), new AuthRuleEvaluator(), clock);
        SessionProfileRepository sessionRepository = new SessionProfileRepository();
        SessionProfile binding = new SessionProfile(
                "proxy-user", "Proxy user", true, false, SessionScope.custom(List.of()),
                "alice", SessionAuthConfig.defaults(), SessionSchedulerConfig.disabled(),
                SessionState.empty(), List.of(), List.of());
        sessionRepository.saveProfile(binding);
        SessionManager manager = manager(sessionRepository, accessRepository, authManager, clock);

        manager.clearState(binding.id());

        AuthProfile cleared = accessRepository.authProfile("alice").orElseThrow();
        assertEquals(Map.of("username", "alice"), cleared.credentials());
        assertTrue(cleared.state().variables().isEmpty());
    }

    private static SessionManager manager(
            SessionProfileRepository sessionRepository,
            InMemoryAccessRepository accessRepository,
            AuthManager authManager,
            Clock clock) {
        ExtractionService extraction = new ExtractionService();
        FlowEngine flowEngine = new FlowEngine(extraction, new InjectionService(), request -> noopResponse());
        return new SessionManager(sessionRepository, List::of, flowEngine, extraction, clock,
                accessRepository, authManager, new AuthInjectionRuleRenderer());
    }

    private static AuthProvider provider() {
        return new AuthProvider(
                "provider", "Web login", "", true, "login-flow", "", "",
                List.of(new AuthInjectionRule(
                        "bearer", "Bearer", true, AuthInjectionLocation.HEADER,
                        "Authorization", "Bearer {{auth.accessToken}}", AuthInjectionMode.OVERRIDE)),
                List.of(), List.of(), AuthFallbackPolicy.LOGIN_ONLY, AuthRetryPolicy.NO_RETRY, 300L);
    }

    private static SessionTrafficContext context() {
        return new SessionTrafficContext("PROXY", true, "GET", "example.com", "/api", List.of(), "");
    }

    private static HttpResponse noopResponse() {
        return (HttpResponse) Proxy.newProxyInstance(
                HttpResponse.class.getClassLoader(), new Class<?>[]{HttpResponse.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "statusCode" -> (short) 200;
                    case "bodyToString" -> "";
                    case "toString" -> "NoopResponse";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
