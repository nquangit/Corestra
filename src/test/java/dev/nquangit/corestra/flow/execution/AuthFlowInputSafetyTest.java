package dev.nquangit.corestra.flow.execution;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.auth.AuthRuleType;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.auth.AuthSuccessRule;
import dev.nquangit.corestra.access.auth.runtime.AuthEnsureResult;
import dev.nquangit.corestra.access.auth.runtime.AuthEnsureStatus;
import dev.nquangit.corestra.access.auth.runtime.AuthFlowExecutor;
import dev.nquangit.corestra.access.auth.runtime.AuthManager;
import dev.nquangit.corestra.access.auth.runtime.AuthRuleEvaluator;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.control.ConditionMatchPolicy;
import dev.nquangit.corestra.flow.control.StepAction;
import dev.nquangit.corestra.flow.control.StepActionType;
import dev.nquangit.corestra.flow.control.StepConditionActionRule;
import dev.nquangit.corestra.flow.control.StepConditionEvaluator;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.persistence.FlowRepository;
import dev.nquangit.corestra.persistence.FlowSerializer;
import dev.nquangit.corestra.persistence.PersistenceMigrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuthFlowInputSafetyTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC);

    @ParameterizedTest
    @ValueSource(strings = {"missing", "", "   "})
    void missingRefreshStateCannotSendCapturedTokenAndFallsBackToLogin(String token) {
        Fixture fixture = new Fixture(List.of(refresh(variable("auth.refresh_token")), login()));
        Map<String, String> state = token.equals("missing") ? Map.of() : Map.of("refresh_token", token);

        AuthEnsureResult result = fixture.ensure(state);

        assertEquals(AuthEnsureStatus.LOGGED_IN, result.status());
        assertEquals(List.of("POST /login HTTP/1.1"), fixture.sentLines);
        assertFalse(fixture.sentBodies.stream().anyMatch(body -> body.contains("captured-other-user")));
        assertEquals("alice-refresh", result.updatedProfile().orElseThrow().state().variables().get("refresh_token"));
        assertTrue(result.warnings().stream().anyMatch(message -> message.contains("auth.refresh_token")));
    }

    @Test
    void firstLoginMakesTokenAvailableForSubsequentForcedRefresh() {
        Fixture fixture = new Fixture(List.of(refresh(variable("auth.refresh_token")), login()));
        assertEquals(AuthEnsureStatus.LOGGED_IN, fixture.ensure(Map.of()).status());
        AuthEnsureResult refresh = fixture.manager.forceAuth("alice", "", Map.of(), RenderMode.STRICT);
        assertEquals(AuthEnsureStatus.REFRESHED, refresh.status());
        assertEquals(List.of("POST /login HTTP/1.1", "POST /refresh HTTP/1.1"), fixture.sentLines);
        assertEquals("alice-refresh", fixture.sentBodies.get(1));
    }

    @Test
    void anotherActorsStateCannotSatisfyMissingRefreshInput() {
        Fixture fixture = new Fixture(List.of(refresh(variable("auth.refresh_token")), login()));
        fixture.access.saveAuthProfile(new AuthProfile("bob", "Bob", "USER", fixture.provider.id(), Map.of(),
                new AuthState(Map.of("refresh_token", "bob-refresh"), Map.of(), Map.of(), 5_000, 500), true));
        assertEquals(AuthEnsureStatus.LOGGED_IN, fixture.ensure(Map.of()).status());
        assertEquals(List.of("POST /login HTTP/1.1"), fixture.sentLines);
        assertEquals("bob-refresh", fixture.access.authProfile("bob").orElseThrow().state().variables().get("refresh_token"));
    }

    @Test
    void failedJsonMutationCannotSendCapturedBody() {
        InjectRule json = new InjectRule("json", "Token", true, InjectRule.Target.REQUEST_JSON_PATH,
                "refresh_token", InjectRule.ValueSourceType.VARIABLE, "auth.refresh_token", "",
                InjectRule.WriteStrategy.OVERWRITE);
        Fixture fixture = new Fixture(List.of(refresh(json), login()));
        assertEquals(AuthEnsureStatus.LOGGED_IN, fixture.ensure(Map.of("refresh_token", "alice-old")).status());
        assertEquals(List.of("POST /login HTTP/1.1"), fixture.sentLines);
    }

    @Test
    void unmatchedRegexCannotSilentlyKeepCapturedToken() {
        InjectRule regex = new InjectRule("regex", "Token", true, InjectRule.Target.REQUEST_REGEX_REPLACEMENT,
                "token-not-present=.+", InjectRule.ValueSourceType.VARIABLE, "auth.refresh_token", "",
                InjectRule.WriteStrategy.OVERWRITE);
        Fixture fixture = new Fixture(List.of(refresh(regex), login()));
        assertEquals(AuthEnsureStatus.LOGGED_IN, fixture.ensure(Map.of("refresh_token", "alice-old")).status());
        assertEquals(List.of("POST /login HTTP/1.1"), fixture.sentLines);
    }

    @Test
    void newExtractorOutputReplacesBothFlatAndScopedOldState() {
        Fixture fixture = new Fixture(List.of(flow("refresh",
                step("producer", List.of(), List.of(extract("refresh_token", "refresh_token"))),
                step("refresh", List.of(variable("auth.refresh_token")), List.of())), login()));
        assertEquals(AuthEnsureStatus.REFRESHED, fixture.ensure(Map.of("refresh_token", "alice-old")).status());
        assertEquals("alice-refresh", fixture.sentBodies.get(1));
    }

    @Test
    void availableRefreshStateUsesActorValueAndSkipsLogin() {
        Fixture fixture = new Fixture(List.of(refresh(variable("auth.refresh_token")), login()));

        AuthEnsureResult result = fixture.ensure(Map.of("refresh_token", "alice-old-refresh"));

        assertEquals(AuthEnsureStatus.REFRESHED, result.status());
        assertEquals(List.of("POST /refresh HTTP/1.1"), fixture.sentLines);
        assertEquals(List.of("alice-old-refresh"), fixture.sentBodies);
    }

    @Test
    void missingTemplateInputAlsoPreventsRefresh() {
        InjectRule template = new InjectRule("inject", "Refresh token", true,
                InjectRule.Target.REQUEST_HEADER, "Authorization", InjectRule.ValueSourceType.TEMPLATE,
                "", "Bearer {{auth.refresh_token}}", InjectRule.WriteStrategy.OVERWRITE, List.of());
        Fixture fixture = new Fixture(List.of(refresh(template), login()));
        assertEquals(AuthEnsureStatus.LOGGED_IN, fixture.ensure(Map.of()).status());
        assertEquals(List.of("POST /login HTTP/1.1"), fixture.sentLines);
    }

    @Test
    void missingLoginCredentialsCannotFallBackToCapturedCredentials() {
        Fixture fixture = new Fixture(List.of(refresh(variable("auth.refresh_token")),
                flow("login", step("login", List.of(variable("credentials.username")), List.of()))));
        AuthEnsureResult result = fixture.ensure(Map.of());
        assertTrue(result.failed());
        assertTrue(result.reason().contains("credentials.username"));
        assertTrue(fixture.sentLines.isEmpty());
    }

    @Test
    void failedEarlierExtractorBlocksDependentRequestEvenWithContinueStrategy() {
        FlowStep producer = step("producer", List.of(), List.of(extract("nonexistent", "refresh_token")));
        Fixture fixture = new Fixture(List.of(flow("refresh", producer,
                step("refresh", List.of(variable("auth.refresh_token")), List.of())), login()));
        AuthEnsureResult result = fixture.ensure(Map.of());
        assertEquals(AuthEnsureStatus.LOGGED_IN, result.status());
        assertEquals(List.of("POST /producer HTTP/1.1", "POST /login HTTP/1.1"), fixture.sentLines);
    }

    @Test
    void earlierExtractorCanSupplyScopedInputWithinSameAuthFlow() {
        Fixture fixture = new Fixture(List.of(flow("refresh",
                step("producer", List.of(), List.of(extract("refresh_token", "refresh_token"))),
                step("refresh", List.of(variable("auth.refresh_token")), List.of())), login()));
        assertEquals(AuthEnsureStatus.REFRESHED, fixture.ensure(Map.of()).status());
        assertEquals(List.of("POST /producer HTTP/1.1", "POST /refresh HTTP/1.1"), fixture.sentLines);
        assertEquals("alice-refresh", fixture.sentBodies.get(1));
    }

    @Test
    void inputCannotBeSatisfiedByExtractorOnSameStep() {
        Fixture fixture = new Fixture(List.of(flow("refresh", step("refresh",
                List.of(variable("auth.refresh_token")), List.of(extract("refresh_token", "refresh_token")))), login()));
        assertEquals(AuthEnsureStatus.LOGGED_IN, fixture.ensure(Map.of()).status());
        assertEquals(List.of("POST /login HTTP/1.1"), fixture.sentLines);
    }

    @Test
    void triggeredFlowCanProduceRequiredInputBeforeDependentStep() {
        FlowStep start = triggerProducer();
        Fixture fixture = new Fixture(List.of(flow("refresh", start,
                step("refresh", List.of(variable("auth.refresh_token")), List.of())),
                flow("child", step("producer", List.of(), List.of(extract("refresh_token", "refresh_token")))), login()));
        assertEquals(AuthEnsureStatus.REFRESHED, fixture.ensure(Map.of()).status());
        assertEquals(List.of("POST /start HTTP/1.1", "POST /producer HTTP/1.1", "POST /refresh HTTP/1.1"), fixture.sentLines);
        assertEquals("alice-refresh", fixture.sentBodies.get(2));
    }

    @Test
    void triggeredFlowWithoutOutputCannotBypassRuntimeInputCheck() {
        Fixture fixture = new Fixture(List.of(flow("refresh", triggerProducer(),
                step("refresh", List.of(variable("auth.refresh_token")), List.of())),
                flow("child", step("producer", List.of(), List.of(extract("nonexistent", "refresh_token")))), login()));
        assertEquals(AuthEnsureStatus.LOGGED_IN, fixture.ensure(Map.of()).status());
        assertEquals(List.of("POST /start HTTP/1.1", "POST /producer HTTP/1.1", "POST /login HTTP/1.1"), fixture.sentLines);
    }

    private static FlowStep triggerProducer() {
        StepAction action = new StepAction("trigger", "Produce token", true, StepActionType.TRIGGER_FLOW, Map.of("flowId", "child"));
        StepConditionActionRule rule = new StepConditionActionRule("condition", "Always", true,
                ConditionMatchPolicy.ALL, List.of(), List.of(action));
        return step("start", List.of(), List.of()).withConditionActionRules(List.of(rule));
    }

    private static InjectRule variable(String key) {
        return new InjectRule("inject", "Required value", true, InjectRule.Target.REQUEST_RAW_BODY_REPLACE,
                "", InjectRule.ValueSourceType.VARIABLE, key, "", InjectRule.WriteStrategy.OVERWRITE, List.of());
    }

    private static ExtractRule extract(String selector, String key) {
        return new ExtractRule("extract", "Token", true, ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.JSON_PATH, selector, key, List.of());
    }

    private static FlowDefinition login() {
        return flow("login", step("login", List.of(), List.of(extract("refresh_token", "refresh_token"))));
    }

    private static FlowDefinition refresh(InjectRule rule) {
        return flow("refresh", step("refresh", List.of(rule), List.of(extract("refresh_token", "refresh_token"))));
    }

    private static FlowStep step(String id, List<InjectRule> rules, List<ExtractRule> extractors) {
        StoredRequestTemplate request = new StoredRequestTemplate("POST", "https", "example.test", 443,
                "/" + id, id.equals("login") ? List.of("Content-Type: application/json")
                        : List.of("Authorization: Bearer captured-other-user"),
                id.equals("login") ? "alice-credentials" : "captured-other-user");
        return new FlowStep(id, id, true, "", request, extractors, rules, List.of(), FlowStep.StepErrorStrategy.CONTINUE_FLOW);
    }

    private static FlowDefinition flow(String id, FlowStep... steps) {
        return new FlowDefinition(id, id, "", true, List.of(steps), null, null, null);
    }

    private static final class Fixture {
        final List<String> sentLines = new ArrayList<>();
        final List<String> sentBodies = new ArrayList<>();
        final InMemoryAccessRepository access = new InMemoryAccessRepository();
        final AuthProvider provider = new AuthProvider("provider", "Provider", "", true,
                "login", "refresh", "", List.of(),
                List.of(new AuthSuccessRule("ok", "200", true, AuthRuleType.STATUS_CODE, "200", "")),
                List.of(), AuthFallbackPolicy.REFRESH_THEN_LOGIN, AuthRetryPolicy.NO_RETRY);
        final AuthManager manager;

        Fixture(List<FlowDefinition> flows) {
            Map<String, String> storage = new HashMap<>();
            PersistedObject data = proxy(PersistedObject.class, (method, args) -> switch (method) {
                case "getString" -> storage.get(args[0]);
                case "setString" -> { storage.put((String) args[0], (String) args[1]); yield null; }
                case "deleteString" -> { storage.remove(args[0]); yield null; }
                default -> null;
            });
            Persistence persistence = proxy(Persistence.class, (method, args) -> data);
            FlowRepository repository = new FlowRepository(persistence, new FlowSerializer(), new PersistenceMigrationService());
            repository.saveAll(flows);
            StepExecutor executor = new StepExecutor(new ExtractionService(), new InjectionService()) {
                @Override
                HttpRequest buildMontoyaRequest(StoredRequestTemplate template, String line, List<String> headers, String body) {
                    sentLines.add(line);
                    sentBodies.add(body);
                    return proxy(HttpRequest.class, (method, args) -> null);
                }
            };
            FlowEngine engine = new FlowEngine(executor, request -> response(),
                    id -> flows.stream().filter(flow -> flow.id().equals(id)).findFirst(),
                    new ThreadSleeper(), new StepConditionEvaluator());
            manager = new AuthManager(access, new AuthFlowExecutor(repository, engine, CLOCK), new AuthRuleEvaluator(), CLOCK);
            access.saveAuthProvider(provider);
        }

        AuthEnsureResult ensure(Map<String, String> variables) {
            access.saveAuthProfile(new AuthProfile("alice", "Alice", "USER", provider.id(), Map.of(),
                    new AuthState(variables, Map.of(), Map.of(), 0, 0), true));
            return manager.ensureAuth("alice", "", Map.of(), RenderMode.STRICT);
        }
    }

    private static HttpResponse response() {
        String body = "{\"refresh_token\":\"alice-refresh\"}";
        return proxy(HttpResponse.class, (method, args) -> switch (method) {
            case "statusCode" -> (short) 200;
            case "httpVersion" -> "HTTP/1.1";
            case "reasonPhrase" -> "OK";
            case "headers" -> List.of();
            case "bodyToString" -> body;
            case "toString" -> "HTTP/1.1 200 OK\r\n\r\n" + body;
            default -> null;
        });
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (object, method, args) -> invocation.invoke(method.getName(), args)));
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] args);
    }
}
