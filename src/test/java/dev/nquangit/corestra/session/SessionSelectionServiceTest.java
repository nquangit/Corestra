package dev.nquangit.corestra.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.access.auth.*;
import dev.nquangit.corestra.access.auth.runtime.*;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.flow.execution.TestFlowEngineFactory;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.interaction.InteractionAbortedException;
import dev.nquangit.corestra.persistence.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SessionSelectionServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);
    private static final SessionTrafficContext TRAFFIC = new SessionTrafficContext("REPEATER", true, "GET",
            "api.test", "/api", List.of("X-User-Selection: yes"), "", "https", 443);
    private static final SessionSelectionConfig CONFIG = new SessionSelectionConfig(true, null, List.of("REPEATER"), 30, null);
    private final SessionProfileRepository repository = new SessionProfileRepository();
    private final SessionManager manager = new SessionManager(repository, List::of,
            TestFlowEngineFactory.withHttpClient(request -> { fail("Choice must never authenticate"); return null; }),
            new ExtractionService(), CLOCK);

    private static SessionProfile profile(String id, SessionState state) {
        return new SessionProfile(id, id, true, false,
                SessionScope.custom(List.of(new SessionScopeCriterion(SessionCriterionType.HOST, "", "^other-host$"))),
                SessionAuthConfig.defaults(), SessionSchedulerConfig.disabled(), state,
                List.of(new SessionInjectionRule("cookie", true, InjectRule.Target.REQUEST_COOKIE, "token",
                        "{{session.token}}", InjectRule.WriteStrategy.OVERWRITE, List.of("REPEATER"))), List.of());
    }

    private static SessionState valid(String token) { return new SessionState(Map.of("token", token), 100, 5000, 100); }

    @Test void selectionUsesOnlyUsableEnabledSessionsAndDoesNotAuthenticate() {
        repository.saveProfile(profile("valid", valid("alice")));
        repository.saveProfile(profile("disabled", valid("bob")).withEnabled(false));
        repository.saveProfile(profile("expired", new SessionState(Map.of("token", "old"), 100, 999, 100)));
        repository.saveProfile(profile("empty", SessionState.empty()));
        repository.saveProfile(profile("missing-token", new SessionState(Map.of("other", "value"), 100, 5000, 100)));
        var selector = new SessionSelectionService(manager, () -> CONFIG, (context, choices, timeout) -> {
            assertEquals(List.of("valid"), choices.stream().map(SessionChoice::profileId).toList());
            assertEquals(30, timeout); return Optional.of("valid");
        });
        SessionInjectionPlan plan = selector.choose(TRAFFIC, CONFIG);
        assertEquals("valid", plan.profileId());
        assertEquals("alice", plan.rules().getFirst().staticValue());
        assertFalse(repository.profile("valid").orElseThrow().scope().matches(TRAFFIC), "Explicit choice overrides ordinary scope");
        assertTrue(manager.existingSessionChoices(new SessionTrafficContext("PROXY", true, "GET", "api.test", "/api", List.of(), "")).isEmpty());
    }

    @Test void partialRulesAndStrategiesThatKeepOriginalAuthenticationAreNotOffered() {
        var complete = profile("valid", valid("alice"));
        for (InjectRule.WriteStrategy strategy : List.of(InjectRule.WriteStrategy.APPEND, InjectRule.WriteStrategy.SKIP_IF_PRESENT)) {
            repository.saveProfile(new SessionProfile(strategy.name(), strategy.name(), true, false, complete.scope(),
                    SessionAuthConfig.defaults(), SessionSchedulerConfig.disabled(), complete.state(),
                    List.of(new SessionInjectionRule("cookie", true, InjectRule.Target.REQUEST_COOKIE, "token",
                            "{{session.token}}", strategy, List.of("REPEATER"))), List.of()));
        }
        repository.saveProfile(new SessionProfile("partial", "Partial", true, false, complete.scope(),
                SessionAuthConfig.defaults(), SessionSchedulerConfig.disabled(), complete.state(),
                List.of(complete.injectionRules().getFirst(), new SessionInjectionRule("missing", true,
                        InjectRule.Target.REQUEST_HEADER, "Authorization", "Bearer {{session.missing}}",
                        InjectRule.WriteStrategy.OVERWRITE, List.of("REPEATER"))), List.of()));
        assertTrue(manager.existingSessionChoices(TRAFFIC).isEmpty());
    }

    @Test void cancellationUnknownIdDisabledOrChangedConfigurationWhileWaitingAbort() {
        repository.saveProfile(profile("valid", valid("alice")));
        assertThrows(InteractionAbortedException.class, () -> new SessionSelectionService(manager, () -> CONFIG,
                (ctx, choices, timeout) -> Optional.empty()).choose(TRAFFIC, CONFIG));
        assertThrows(InteractionAbortedException.class, () -> new SessionSelectionService(manager, () -> CONFIG,
                (ctx, choices, timeout) -> Optional.of("not-offered")).choose(TRAFFIC, CONFIG));
        AtomicReference<SessionSelectionConfig> config = new AtomicReference<>(CONFIG);
        assertThrows(InteractionAbortedException.class, () -> new SessionSelectionService(manager, config::get,
                (ctx, choices, timeout) -> { config.set(SessionSelectionConfig.disabled()); return Optional.of("valid"); })
                .choose(TRAFFIC, CONFIG));
        assertThrows(InteractionAbortedException.class, () -> new SessionSelectionService(manager, () -> CONFIG,
                (ctx, choices, timeout) -> {
                    repository.saveProfile(repository.profile("valid").orElseThrow().withEnabled(false));
                    return Optional.of("valid");
                }).choose(TRAFFIC, CONFIG));
    }

    @Test void providerAndActorMustBeEnabledWithCompleteResolvableInjectionRules() {
        InMemoryAccessRepository access = new InMemoryAccessRepository();
        AuthInjectionRule cookie = new AuthInjectionRule("provider-cookie", "Token", true,
                AuthInjectionLocation.COOKIE, "token", "{{auth.token}}", AuthInjectionMode.OVERRIDE);
        AuthProvider provider = new AuthProvider("provider", "Provider", "", true, "login", "", "",
                List.of(cookie), List.of(), List.of(), AuthFallbackPolicy.LOGIN_ONLY, AuthRetryPolicy.NO_RETRY);
        access.saveAuthProvider(provider);
        AuthProfile actor = new AuthProfile("actor", "Alice", "admin", provider.id(), Map.of(),
                new AuthState(Map.of("token", "alice"), Map.of(), Map.of(), 5000, 100), true);
        access.saveAuthProfile(actor);
        repository.saveProfile(SessionProfile.general().withLinkedAuthProfileId(actor.id()));
        SessionManager linked = new SessionManager(repository, List::of, TestFlowEngineFactory.withHttpClient(request -> null),
                new ExtractionService(), CLOCK, access,
                new AuthManager(access, (flow, p, providerConfig, env, purpose, mode) -> {
                    fail("Selecting an existing actor must not authenticate"); return null;
                }, new AuthRuleEvaluator(), CLOCK), new AuthInjectionRuleRenderer());
        assertEquals(1, linked.existingSessionChoices(TRAFFIC).size());
        assertEquals("alice", linked.prepareExistingSession(SessionProfile.GENERAL_ID, TRAFFIC).rules().getFirst().staticValue());
        access.saveAuthProfile(new AuthProfile(actor.id(), actor.name(), actor.role(), actor.providerId(), Map.of(), actor.state(), false));
        assertTrue(linked.existingSessionChoices(TRAFFIC).isEmpty());
        access.saveAuthProfile(new AuthProfile(actor.id(), actor.name(), actor.role(), actor.providerId(), Map.of(), AuthState.empty(), true));
        assertTrue(linked.existingSessionChoices(TRAFFIC).isEmpty());
        access.saveAuthProfile(actor);
        access.saveAuthProvider(new AuthProvider("provider", "Provider", "", false, "login", "", "",
                List.of(cookie), List.of(), List.of(), AuthFallbackPolicy.LOGIN_ONLY, AuthRetryPolicy.NO_RETRY));
        assertTrue(linked.existingSessionChoices(TRAFFIC).isEmpty());
    }

    @Test void concurrentAuthenticationIsExcludedAndChoiceDoesNotWaitOnItsLock() throws Exception {
        InMemoryAccessRepository access = new InMemoryAccessRepository();
        AuthProvider provider = new AuthProvider("provider", "Provider", "", true, "login", "", "",
                List.of(), List.of(), List.of(), AuthFallbackPolicy.LOGIN_ONLY, AuthRetryPolicy.NO_RETRY);
        access.saveAuthProvider(provider);
        access.saveAuthProfile(new AuthProfile("actor", "Alice", "", provider.id(), Map.of(),
                new AuthState(Map.of(), Map.of("Authorization", "Bearer old"), Map.of(), 5000, 100), true));
        repository.saveProfile(SessionProfile.general().withLinkedAuthProfileId("actor"));
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        AuthManager auth = new AuthManager(access, (flow, actor, configured, env, purpose, mode) -> {
            started.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return AuthFlowRunResult.executed(true, "OK", 200, Map.of("token", "new"), "", List.of());
        }, new AuthRuleEvaluator(), CLOCK);
        SessionManager linked = new SessionManager(repository, List::of, TestFlowEngineFactory.withHttpClient(request -> null),
                new ExtractionService(), CLOCK, access, auth, new AuthInjectionRuleRenderer());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> job = executor.submit(() -> auth.forceAuth("actor", "", Map.of(), RenderMode.STRICT));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(linked.existingSessionChoices(TRAFFIC).isEmpty());
            assertThrows(InteractionAbortedException.class, () -> linked.prepareExistingSession(SessionProfile.GENERAL_ID, TRAFFIC));
            release.countDown(); job.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void configMatchesToolsHeaderAndScopeAndRejectsInvalidSettings() throws Exception {
        assertTrue(CONFIG.matches(TRAFFIC));
        assertFalse(SessionSelectionConfig.disabled().matches(TRAFFIC));
        assertFalse(CONFIG.matches(new SessionTrafficContext("PROXY", true, "GET", "api.test", "/api", TRAFFIC.headers(), "")));
        assertFalse(CONFIG.matches(new SessionTrafficContext("REPEATER", true, "GET", "api.test", "/api", List.of(), "")));
        assertThrows(IllegalArgumentException.class, () -> new SessionSelectionConfig(true, null, List.of(), 30, ""));
        assertThrows(IllegalArgumentException.class, () -> new SessionSelectionConfig(true, null, List.of("EXTENSIONS"), 30, ""));
        assertThrows(IllegalArgumentException.class, () -> new SessionSelectionConfig(true, null, null, 3601, ""));
        assertThrows(IllegalArgumentException.class, () -> new SessionSelectionConfig(true, null, null, 30, "bad\r\nheader"));
        assertThrows(IllegalArgumentException.class, () -> new SessionSelectionConfig(true, SessionScope.custom(List.of(
                new SessionScopeCriterion(SessionCriterionType.HOST, "", "["))), null, 30, ""));
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(CONFIG, mapper.readValue(mapper.writeValueAsString(CONFIG), SessionSelectionConfig.class));
    }

    @Test void settingsRoundTripAndProjectExportPreserveSelectionAndLegacyDefaults() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ProjectSettings settings = ProjectSettings.defaults().withSessionSelection(CONFIG);
        assertEquals(settings, mapper.readValue(mapper.writeValueAsString(settings), ProjectSettings.class));
        assertEquals(CONFIG, settings.withSendMode(HttpSendMode.PROXY_PIPELINE).withProxyHost("localhost").withProxyPort(9090).sessionSelection());
        assertFalse(mapper.readValue("{\"sendMode\":\"STANDARD\",\"proxyPort\":8080}", ProjectSettings.class).sessionSelection().enabled());
        var serializer = new ProjectSerializer(new FlowSerializer());
        var doc = new ProjectDocument(1, ProjectMetadata.createNew(), settings, List.of(),
                dev.nquangit.corestra.access.workspace.AccessWorkspace.empty(), List.of());
        assertEquals(CONFIG, serializer.deserializeProject(serializer.serializeProject(doc)).settings().sessionSelection());
    }
}
