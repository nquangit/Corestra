package dev.nquangit.corestra.session;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowStep.StepErrorStrategy;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.flow.execution.TestFlowEngineFactory;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.integration.HttpClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerTest {

    @Test
    void selectsMultiHostProfileOnlyWithTheRequiredPortProtocolAndUserHeader() {
        var rules = List.of(
                new SessionScopeRule("API", List.of(new SessionScopeCriterion(SessionCriterionType.HOST, "", "^api\\.example$"))),
                new SessionScopeRule("Admin", List.of(new SessionScopeCriterion(SessionCriterionType.HOST, "", "^admin\\.example$"))));
        var scope = new SessionScope(SessionScopeMode.CUSTOM, List.of(
                new SessionScopeCriterion(SessionCriterionType.PORT, "", "^8443$"),
                new SessionScopeCriterion(SessionCriterionType.PROTOCOL, "", "^https$"),
                new SessionScopeCriterion(SessionCriterionType.HEADER, "X-User", "^Alice$")), rules);
        var manager = managerWithProfiles(List.of(profile("alice", "Alice", scope)));
        for (String host : List.of("api.example", "admin.example")) {
            var context = new SessionTrafficContext("PROXY", false, "GET", host, "/", List.of("X-User: Alice"), "", "https", 8443);
            assertEquals("alice", manager.selectProfile(context).profile().orElseThrow().id());
            assertTrue(manager.selectProfile(new SessionTrafficContext("PROXY", false, "GET", host, "/",
                    List.of("X-User: Bob"), "", "https", 8443)).profile().isEmpty());
            assertTrue(manager.selectProfile(new SessionTrafficContext("PROXY", false, "GET", host, "/",
                    List.of("X-User: Alice"), "", "https", 443)).profile().isEmpty());
        }
    }

    @Test
    void noMatchingProfileFallsBackToGeneralWhenEnabledAndInScope() {
        SessionManager manager = managerWithProfiles(List.of(
                profile("owner", "Owner", SessionScope.custom(List.of(
                        new SessionScopeCriterion(SessionCriterionType.HOST, "", "owner\\.example"))))));

        SessionProfileSelection selection = manager.selectProfile(context("PROXY", true, "api.example"));

        assertEquals(SessionProfile.GENERAL_ID, selection.profile().orElseThrow().id());
    }

    @Test
    void multipleMatchingProfilesUseFirstAndWarn() {
        SessionManager manager = managerWithProfiles(List.of(
                profile("a", "A", SessionScope.custom(List.of(
                        new SessionScopeCriterion(SessionCriterionType.HOST, "", "api\\.example")))),
                profile("b", "B", SessionScope.custom(List.of(
                        new SessionScopeCriterion(SessionCriterionType.HOST, "", "api\\.example"))))));

        SessionProfileSelection selection = manager.selectProfile(context("PROXY", true, "api.example"));

        assertEquals("a", selection.profile().orElseThrow().id());
        assertEquals(1, selection.warnings().size());
    }

    @Test
    void extractionUpdatesGlobalStateAndInjectionUsesLatestValueAcrossTools() {
        SessionProfileRepository repository = new SessionProfileRepository();
        SessionProfile general = SessionProfile.general().withConfig(
                "General",
                SessionScope.custom(List.of()),
                SessionAuthConfig.defaults(),
                SessionSchedulerConfig.disabled(),
                List.of(new SessionInjectionRule(
                        "inject-1",
                        true,
                        InjectRule.Target.REQUEST_HEADER,
                        "Authorization",
                        "Bearer {{session.accessToken}}",
                        InjectRule.WriteStrategy.OVERWRITE,
                        List.of("REPEATER"))),
                List.of(new SessionExtractionRule(
                        "extract-1",
                        true,
                        ExtractRule.SourceSide.RESPONSE,
                        ExtractRule.ExtractorType.HEADER,
                        "X-Token",
                        "accessToken",
                        List.of("PROXY"))));
        repository.saveProfile(general);
        SessionManager manager = manager(repository);

        manager.applyExtractionRules(
                general,
                context("PROXY", true, "api.example"),
                requestSnapshot(),
                responseSnapshot("old-token"));
        SessionProfile updated = repository.profile(SessionProfile.GENERAL_ID).orElseThrow();
        assertEquals("old-token", updated.state().values().get("accessToken"));

        manager.applyExtractionRules(
                updated,
                context("PROXY", true, "api.example"),
                requestSnapshot(),
                responseSnapshot("new-token"));
        updated = repository.profile(SessionProfile.GENERAL_ID).orElseThrow();
        assertEquals("new-token", updated.state().values().get("accessToken"));

        manager.applyExtractionRules(
                updated,
                context("PROXY", true, "api.example"),
                requestSnapshot(),
                new HttpMessageSnapshot("HTTP/1.1 200", List.of(), ""));
        updated = repository.profile(SessionProfile.GENERAL_ID).orElseThrow();
        assertEquals("new-token", updated.state().values().get("accessToken"));

        List<InjectRule> repeaterRules = manager.renderedInjectionRules(updated, "REPEATER");
        assertEquals(1, repeaterRules.size());
        assertEquals("Bearer new-token", repeaterRules.getFirst().staticValue());
        assertTrue(manager.renderedInjectionRules(updated, "PROXY").isEmpty());
    }

    @Test
    void extractionStateChangesEmitEventsButEmptyResultsDoNot() {
        SessionProfileRepository repository = new SessionProfileRepository();
        SessionProfile general = generalWithHeaderExtraction();
        repository.saveProfile(general);
        SessionManager manager = manager(repository);
        ArrayList<SessionProfileChangeEvent> events = new ArrayList<>();
        manager.addProfileChangeListener(events::add);

        SessionProfile updated = manager.applyExtractionRules(
                general,
                context("PROXY", true, "api.example"),
                requestSnapshot(),
                responseSnapshot("old-token"));
        updated = manager.applyExtractionRules(
                updated,
                context("PROXY", true, "api.example"),
                requestSnapshot(),
                responseSnapshot("new-token"));
        manager.applyExtractionRules(
                updated,
                context("PROXY", true, "api.example"),
                requestSnapshot(),
                new HttpMessageSnapshot("HTTP/1.1 200", List.of(), ""));

        assertEquals(2, events.size());
        assertEquals(SessionProfile.GENERAL_ID, events.get(0).profileId());
        assertEquals(SessionProfileChangeType.STATE_UPDATED, events.get(0).type());
        assertEquals(SessionProfileChangeType.STATE_UPDATED, events.get(1).type());
        assertEquals("new-token", repository.profile(SessionProfile.GENERAL_ID).orElseThrow()
                .state().values().get("accessToken"));
    }

    @Test
    void clearStateEmitsStateClearedEvent() {
        SessionProfileRepository repository = new SessionProfileRepository();
        repository.saveProfile(SessionProfile.general().withState(
                new SessionState(Map.of("accessToken", "token"), 0L, 0L, 1000L)));
        SessionManager manager = manager(repository);
        ArrayList<SessionProfileChangeEvent> events = new ArrayList<>();
        manager.addProfileChangeListener(events::add);

        manager.clearState(SessionProfile.GENERAL_ID);

        assertEquals(1, events.size());
        assertEquals(SessionProfile.GENERAL_ID, events.getFirst().profileId());
        assertEquals(SessionProfileChangeType.STATE_CLEARED, events.getFirst().type());
        assertTrue(repository.profile(SessionProfile.GENERAL_ID).orElseThrow().state().values().isEmpty());
    }

    @Test
    void clearAllProfilesRestoresOnlyFreshGeneralProfile() {
        SessionProfileRepository repository = new SessionProfileRepository();
        repository.saveProfile(profile("custom", "Custom", SessionScope.custom(List.of()))
                .withState(new SessionState(Map.of("token", "secret"), 1L, 9_000L, 1L)));
        SessionManager manager = manager(repository);

        manager.clearAllProfiles();

        assertEquals(1, manager.profiles().size());
        assertEquals(SessionProfile.GENERAL_ID, manager.profiles().getFirst().id());
        assertTrue(manager.profiles().getFirst().state().values().isEmpty());
    }

    @Test
    void listenerFailuresDoNotPreventStatePersistence() {
        SessionProfileRepository repository = new SessionProfileRepository();
        SessionProfile general = generalWithHeaderExtraction();
        repository.saveProfile(general);
        SessionManager manager = manager(repository);
        manager.addProfileChangeListener(event -> {
            throw new IllegalStateException("boom");
        });

        manager.applyExtractionRules(
                general,
                context("PROXY", true, "api.example"),
                requestSnapshot(),
                responseSnapshot("token-after-listener-error"));

        assertEquals("token-after-listener-error", repository.profile(SessionProfile.GENERAL_ID).orElseThrow()
                .state().values().get("accessToken"));
    }

    @Test
    void runAuthFlow_setsSessionGuardDuringFlowExecution() {
        // Capturing HttpClient that records whether SessionGuard is active at send time
        AtomicBoolean guardActiveAtSend = new AtomicBoolean(false);
        HttpClient capturingClient = request -> {
            guardActiveAtSend.set(SessionGuard.isActive());
            return noopResponse();
        };

        // TestFlowEngineFactory creates an engine with an overridden StepExecutor that
        // bypasses the Montoya HttpRequest factory (which needs a real Burp runtime)
        FlowEngine flowEngine = TestFlowEngineFactory.withHttpClient(capturingClient);

        // Create a profile with an auth flow configured
        String authFlowId = "auth-flow-1";
        SessionProfileRepository repository = new SessionProfileRepository();
        SessionProfile profile = new SessionProfile(
                "profile-1",
                "Test Profile",
                true,
                false,
                SessionScope.custom(List.of()),
                new SessionAuthConfig(authFlowId, 3600L, List.of()),
                SessionSchedulerConfig.disabled(),
                SessionState.empty(),
                List.of(),
                List.of());
        repository.saveProfile(profile);

        // Create a flow with one enabled step so the engine actually calls the HTTP client
        StoredRequestTemplate template = new StoredRequestTemplate(
                "GET", "http", "example.com", 80, "/auth", List.of("Host: example.com"), "");
        FlowStep step = new FlowStep(
                "step-1", "Auth Step", true, "", template,
                List.of(), List.of(), List.of(), StepErrorStrategy.FAIL_FLOW);
        FlowDefinition authFlow = new FlowDefinition(
                authFlowId, "Auth Flow", "", true, List.of(step), null, null, null);

        ExtractionService extractionService = new ExtractionService();
        SessionManager manager = new SessionManager(
                repository,
                () -> List.of(authFlow),
                flowEngine,
                extractionService,
                Clock.fixed(Instant.ofEpochMilli(1000L), ZoneOffset.UTC));

        // Guard must be inactive before the auth flow runs
        assertFalse(SessionGuard.isActive(), "guard must be inactive before forceAuthenticate");

        manager.forceAuthenticate("profile-1");

        // Guard must have been active when the flow engine sent the HTTP request
        assertTrue(guardActiveAtSend.get(), "SessionGuard.isActive() must be true during flow execution");

        // Guard must be inactive after forceAuthenticate returns
        assertFalse(SessionGuard.isActive(), "guard must be inactive after forceAuthenticate");
    }

    @Test
    void staleResponseMergesIntoLatestStateWithoutUndoingProfileEdits() {
        SessionProfileRepository repository = new SessionProfileRepository();
        SessionProfile stale = generalWithHeaderExtraction();
        repository.saveProfile(stale);
        SessionManager manager = manager(repository);
        SessionProfile latest = stale.withEnabled(false).withState(
                new SessionState(Map.of("refreshToken", "new-refresh"), 900L, 9000L, 900L));
        repository.saveProfile(latest);

        manager.applyExtractionRules(stale, context("PROXY", true, "api.example"),
                requestSnapshot(), responseSnapshot("new-access"));

        SessionProfile saved = repository.profile(stale.id()).orElseThrow();
        assertFalse(saved.enabled());
        assertEquals("new-refresh", saved.state().values().get("refreshToken"));
        assertEquals("new-access", saved.state().values().get("accessToken"));
        assertEquals(900L, saved.state().lastAuthenticatedAtEpochMillis());
        assertEquals(9000L, saved.state().expiresAtEpochMillis());
    }

    @Test
    void lateResponseDoesNotRecreateDeletedProfile() {
        SessionProfileRepository repository = new SessionProfileRepository();
        SessionProfile base = generalWithHeaderExtraction();
        SessionProfile profile = new SessionProfile("custom", "Custom", true, false,
                base.scope(), base.authConfig(), base.scheduler(), base.state(),
                base.injectionRules(), base.extractionRules());
        repository.saveProfile(profile);
        SessionManager manager = manager(repository);
        repository.deleteProfile(profile.id());

        manager.applyExtractionRules(profile, context("PROXY", true, "api.example"),
                requestSnapshot(), responseSnapshot("late-token"));

        assertTrue(repository.profile(profile.id()).isEmpty());
    }

    @Test
    void responseFromPreviousAuthenticationGenerationCannotOverwriteFreshState() {
        SessionProfileRepository repository = new SessionProfileRepository();
        SessionState oldState = new SessionState(
                Map.of("accessToken", "old-token"), 900L, 9_000L, 900L);
        SessionProfile profile = SessionProfile.general().withConfig(
                "General",
                SessionScope.custom(List.of()),
                new SessionAuthConfig("login", 180L, List.of()),
                SessionSchedulerConfig.disabled(),
                List.of(new SessionInjectionRule(
                        "inject-token", true, InjectRule.Target.REQUEST_HEADER,
                        "Authorization", "Bearer {{session.accessToken}}",
                        InjectRule.WriteStrategy.OVERWRITE, List.of("PROXY"))),
                List.of(new SessionExtractionRule(
                        "extract-token", true, ExtractRule.SourceSide.RESPONSE,
                        ExtractRule.ExtractorType.HEADER, "X-Token", "accessToken",
                        List.of("PROXY")))).withState(oldState);
        repository.saveProfile(profile);

        FlowStep loginStep = new FlowStep(
                "login-step", "Login", true, "",
                new StoredRequestTemplate("POST", "https", "example.com", 443,
                        "/login", List.of("Host: example.com"), "deviceid=fixed"),
                List.of(), List.of(), List.of(), StepErrorStrategy.FAIL_FLOW);
        FlowDefinition login = new FlowDefinition(
                "login", "Login", "", true, List.of(loginStep), null, null, null);
        ExtractionService extractionService = new ExtractionService();
        SessionManager manager = new SessionManager(
                repository,
                () -> List.of(login),
                TestFlowEngineFactory.withHttpClient(request -> noopResponse()),
                extractionService,
                Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneOffset.UTC));

        SessionInjectionPlan oldPlan = manager.prepareInjectionRules(
                repository.profile(SessionProfile.GENERAL_ID).orElseThrow(),
                context("PROXY", true, "api.example"));
        assertEquals(0L, oldPlan.authenticationGeneration());

        manager.forceAuthenticate(SessionProfile.GENERAL_ID);
        manager.applyExtractionRulesIfCurrent(
                oldPlan.profileId(), oldPlan.authenticationGeneration(),
                context("PROXY", true, "api.example"),
                requestSnapshot(), responseSnapshot("stale-token"));

        assertEquals("old-token", repository.profile(SessionProfile.GENERAL_ID).orElseThrow()
                .state().values().get("accessToken"));
    }

    private static SessionManager managerWithProfiles(List<SessionProfile> profiles) {
        SessionProfileRepository repository = new SessionProfileRepository();
        repository.saveProfiles(profiles);
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
                Clock.fixed(Instant.ofEpochMilli(1000L), ZoneOffset.UTC));
    }

    private static SessionProfile profile(String id, String name, SessionScope scope) {
        return new SessionProfile(
                id,
                name,
                true,
                false,
                scope,
                SessionAuthConfig.defaults(),
                SessionSchedulerConfig.disabled(),
                SessionState.empty(),
                List.of(),
                List.of());
    }

    private static SessionProfile generalWithHeaderExtraction() {
        return SessionProfile.general().withConfig(
                "General",
                SessionScope.custom(List.of()),
                SessionAuthConfig.defaults(),
                SessionSchedulerConfig.disabled(),
                List.of(),
                List.of(new SessionExtractionRule(
                        "extract-1",
                        true,
                        ExtractRule.SourceSide.RESPONSE,
                        ExtractRule.ExtractorType.HEADER,
                        "X-Token",
                        "accessToken",
                        List.of("PROXY"))));
    }

    private static SessionTrafficContext context(String tool, boolean inScope, String host) {
        return new SessionTrafficContext(tool, inScope, "GET", host, "/api", List.of(), "");
    }

    private static HttpMessageSnapshot requestSnapshot() {
        return new HttpMessageSnapshot("GET /api HTTP/1.1", List.of("Host: api.example"), "");
    }

    private static HttpMessageSnapshot responseSnapshot(String token) {
        return new HttpMessageSnapshot("HTTP/1.1 200", List.of("X-Token: " + token), "");
    }

    private static HttpResponse noopResponse() {
        return (HttpResponse) Proxy.newProxyInstance(
                HttpResponse.class.getClassLoader(),
                new Class<?>[]{HttpResponse.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "statusCode" -> (short) 200;
                    case "reasonPhrase" -> "OK";
                    case "httpVersion" -> "HTTP/1.1";
                    case "headers" -> List.of();
                    case "bodyToString" -> "";
                    case "toString" -> "NoopResponse";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class NoopHttpClient implements HttpClient {
        @Override
        public HttpResponse send(HttpRequest request) {
            return (HttpResponse) Proxy.newProxyInstance(
                    HttpResponse.class.getClassLoader(),
                    new Class<?>[]{HttpResponse.class},
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
}
