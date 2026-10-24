package dev.nquangit.corestra.integration;

import burp.api.montoya.core.*;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.*;
import dev.nquangit.corestra.access.auth.*;
import dev.nquangit.corestra.access.auth.runtime.*;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.flow.*;
import dev.nquangit.corestra.flow.execution.*;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.persistence.HttpSendMode;
import dev.nquangit.corestra.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("MontoyaObjectFactory")
class SessionFlowBindingTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);
    private MontoyaObjectFactory previous;
    private final SessionProfileRepository repository = new SessionProfileRepository();
    private final InjectionService injections = new InjectionService();
    private final ExtractionService extractions = new ExtractionService();
    private final List<HttpRequest> sent = new CopyOnWriteArrayList<>();
    private final List<HttpSendMode> modes = Collections.synchronizedList(new ArrayList<>());
    private final AtomicReference<Consumer<HttpRequest>> onSend = new AtomicReference<>(request -> {});
    private SessionManager manager;
    private CorestraSessionHttpHandler sessions;
    private SessionFlowBindingService bindings;
    private SessionAwareHttpClient client;
    private FlowEngine engine;
    private AutoTriggerHttpHandler trigger;
    private final List<FlowDefinition> flows = new CopyOnWriteArrayList<>();

    @BeforeEach void setup() {
        previous = ObjectFactoryLocator.FACTORY;
        ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p,m,a) -> switch(m.getName()) {
            case "httpHeader" -> header((String)a[0], (String)a[1]);
            case "httpService" -> service((String)a[0], (int)a[1], (boolean)a[2]);
            case "httpRequest" -> fromRaw((HttpService)a[0], a[1].toString());
            case "requestResult" -> proxy(RequestToBeSentAction.class, (q,n,b) -> switch(n.getName()) {
                case "request" -> a.length == 4 ? a[1] : a[0];
                case "action" -> a.length == 4 ? a[0] : RequestAction.CONTINUE;
                default -> null;
            });
            case "responseResult" -> proxy(ResponseReceivedAction.class, (q,n,b) -> n.getName().equals("response") ? a[0] : null);
            default -> throw new UnsupportedOperationException(m.getName());
        });
        repository.saveProfile(SessionProfile.general().withEnabled(false));
        repository.saveProfile(profile("x", "x"));
        repository.saveProfile(profile("y", "y"));
        manager = new SessionManager(repository, () -> flows, new FlowEngine(extractions, injections, r -> response(0, r)), extractions, CLOCK);
        wire(manager, SessionSelectionConfig.disabled(), "x");
    }

    private void wire(SessionManager configured, SessionSelectionConfig config, String choice) {
        if (trigger != null) trigger.close();
        if (sessions != null) sessions.close();
        manager = configured;
        sessions = new CorestraSessionHttpHandler(manager, injections,
                new SessionSelectionService(manager, () -> config, (traffic, choices, timeout) -> Optional.of(choice)),
                message -> {}, traffic -> flows.stream().flatMap(flow -> flow.steps().stream()).map(FlowStep::triggerRule)
                        .anyMatch(rule -> rule.enabled() && rule.inheritSession() && rule.matches(traffic.toolTypeName(),
                                traffic.host(), traffic.path(), traffic.headers(), traffic.body())));
        bindings = new SessionFlowBindingService(manager, injections, sessions::takeSessionForRequest);
        HttpClient delegate = new HttpClient() {
            @Override public HttpResponse send(HttpRequest r) { return send(r, null); }
            @Override public HttpResponse send(HttpRequest r, HttpSendMode mode) {
                sent.add(r); modes.add(mode); onSend.get().accept(r); return response(0, r);
            }
        };
        client = new SessionAwareHttpClient(delegate, () -> manager, injections, () -> bindings);
        engine = new FlowEngine(extractions, injections, client, id -> flows.stream().filter(f -> f.id().equals(id)).findFirst(), millis -> {});
        trigger = new AutoTriggerHttpHandler(() -> flows, engine, extractions, injections, bindings);
    }

    @AfterEach void cleanup() {
        if (trigger != null) trigger.close();
        if (sessions != null) sessions.close();
        ObjectFactoryLocator.FACTORY = previous;
        assertFalse(SessionGuard.isActive());
    }

    @Test void bindsPrerequisitesLiveAndAsynchronousDownstreamWithOriginalToolAndMode() throws Exception {
        flows.add(flow(step("before", "api.test").withSendModeOverride(HttpSendMode.PROXY_PIPELINE), live(),
                step("after", "api.test").withSendModeOverride(HttpSendMode.STANDARD)));
        CountDownLatch after = new CountDownLatch(1);
        onSend.set(r -> { if (r.path().equals("/after")) after.countDown(); });
        RequestToBeSentAction action = dispatch(1, "x");
        assertEquals(RequestAction.CONTINUE, action.action());
        assertAuth(action.request(), "x");
        trigger.handleHttpResponseReceived(response(1, action.request()));
        assertTrue(after.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("/before", "/after"), sent.stream().map(HttpRequest::path).toList());
        sent.forEach(r -> { assertAuth(r, "x"); assertFalse(r.hasHeader("X-User")); });
        assertEquals(List.of(HttpSendMode.PROXY_PIPELINE, HttpSendMode.STANDARD), modes);
    }

    @Test void concurrentActorsRemainIsolatedEvenWhenResponsesArriveInReverseOrder() throws Exception {
        flows.add(flow(step("before", "api.test"), live(), step("after", "api.test")));
        CountDownLatch before = new CountDownLatch(2), release = new CountDownLatch(1), after = new CountDownLatch(2);
        onSend.set(r -> {
            if (r.path().equals("/before")) {
                before.countDown(); await(release);
            } else after.countDown();
        });
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<RequestToBeSentAction> x = workers.submit(() -> dispatch(10, "x"));
            Future<RequestToBeSentAction> y = workers.submit(() -> dispatch(11, "y"));
            assertTrue(before.await(5, TimeUnit.SECONDS)); release.countDown();
            var xr = x.get(5, TimeUnit.SECONDS); var yr = y.get(5, TimeUnit.SECONDS);
            assertAuth(xr.request(), "x"); assertAuth(yr.request(), "y");
            trigger.handleHttpResponseReceived(response(11, yr.request()));
            trigger.handleHttpResponseReceived(response(10, xr.request()));
            assertTrue(after.await(5, TimeUnit.SECONDS));
            assertEquals(2, sent.stream().filter(r -> r.headerValue("Authorization").equals("Bearer x")).count());
            assertEquals(2, sent.stream().filter(r -> r.headerValue("Authorization").equals("Bearer y")).count());
            assertEquals(0, trigger.pendingTriggerCount());
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test void ordinaryFlowInjectionCannotOverrideTheBoundActor() {
        InjectRule stale = new InjectRule("old", "Old", true, InjectRule.Target.REQUEST_HEADER, "Authorization",
                InjectRule.ValueSourceType.STATIC, "", "Bearer snapshot-user", InjectRule.WriteStrategy.OVERWRITE, List.of());
        flows.add(flow(step("before", "api.test").withInjectRules(List.of(stale)), live().withInjectRules(List.of(stale))));
        var action = dispatch(2, "y");
        assertEquals(RequestAction.CONTINUE, action.action()); assertAuth(action.request(), "y"); assertAuth(sent.getFirst(), "y");
    }

    @Test void explicitChooserAndStrippedSelectorHeaderStillBindTheChosenSession() {
        var config = new SessionSelectionConfig(true, null, List.of("REPEATER"), 30, "X-User-Selection");
        wire(manager, config, "y");
        var rule = new StepTriggerRule(true, List.of("REPEATER"), List.of(
                new StepTriggerRule.MatchCriterion(StepTriggerRule.MatchCriterion.MatchType.HEADER, "X-User-Selection", "yes")),
                StepTriggerRule.ExecutionScope.FULL_FLOW, "", true, List.of());
        flows.add(flow(step("before", "api.test"), live().withTriggerRule(rule)));
        HttpRequestToBeSent original = request(3, "api.test", 443, true, "/live", List.of(header("X-User", "x"), header("X-User-Selection", "yes")), "");
        var selected = (HttpRequestToBeSent)sessions.handleHttpRequestToBeSent(original).request();
        assertFalse(selected.hasHeader("X-User-Selection"));
        var action = trigger.handleHttpRequestToBeSent(selected);
        assertEquals(RequestAction.CONTINUE, action.action()); assertAuth(action.request(), "y"); assertAuth(sent.getFirst(), "y");
    }

    @Test void missingSessionFailsBeforeAnyPrerequisiteAndDoesNotUseSnapshotAuthentication() {
        flows.add(flow(step("before", "api.test"), live()));
        var action = trigger.handleHttpRequestToBeSent(request(4, "api.test", 443, true, "/live", List.of(), ""));
        assertEquals(RequestAction.DROP, action.action()); assertTrue(sent.isEmpty()); assertEquals(0, trigger.pendingTriggerCount());
    }

    @Test void foreignDestinationIsRejectedBeforePrerequisitesButExplicitOriginAllowsIt() {
        flows.add(flow(step("before", "api.test"), live(), step("foreign", "files.test")));
        assertEquals(RequestAction.DROP, dispatch(5, "x").action()); assertTrue(sent.isEmpty());
        flows.clear();
        var allowed = new StepTriggerRule(true, List.of("REPEATER"), live().triggerRule().criteria(),
                StepTriggerRule.ExecutionScope.FULL_FLOW, "", true, List.of("https://files.test"));
        flows.add(flow(step("foreign", "files.test"), live().withTriggerRule(allowed)));
        assertEquals(RequestAction.CONTINUE, dispatch(6, "x").action()); assertAuth(sent.getFirst(), "x");
        assertEquals("files.test", sent.getFirst().httpService().host());
    }

    @Test void tokenChangedDuringPrerequisiteStopsLiveRequestInsteadOfSwitchingActors() {
        flows.add(flow(step("before", "api.test"), live()));
        onSend.set(r -> repository.saveProfile(profile("x", "new-x")));
        assertEquals(RequestAction.DROP, dispatch(7, "x").action()); assertEquals(1, sent.size());
        assertAuth(sent.getFirst(), "x"); assertEquals(0, trigger.pendingTriggerCount());
    }

    @Test void disabledExpiredAndIncompleteSessionsAreRejectedWithoutAuthentication() {
        ExecutionContext context = bind("x");
        repository.saveProfile(repository.profile("x").orElseThrow().withEnabled(false));
        assertThrows(SessionBindingException.class, () -> bindings.apply(context, internal()));
        repository.saveProfile(profile("x", "x").withState(new SessionState(Map.of("token", "x"), 100, 999, 100)));
        assertThrows(SessionBindingException.class, () -> bindings.apply(context, internal()));
        repository.saveProfile(profile("x", "x").withState(new SessionState(Map.of("other", "x"), 100, 5000, 100)));
        assertThrows(SessionBindingException.class, () -> bindings.apply(context, internal()));
        assertTrue(sent.isEmpty());
    }

    @Test void harmlessTtlUpdateDoesNotBreakBindingAndValidationAlsoRunsImmediatelyBeforeSend() {
        ExecutionContext context = bind("x");
        repository.saveProfile(profile("x", "x").withState(new SessionState(Map.of("token", "x"), 1001, 6000, 100)));
        HttpRequest prepared = client.prepareForExecution(internal(), context);
        assertAuth(prepared, "x");
        repository.saveProfile(profile("x", "changed"));
        assertThrows(SessionBindingException.class, () -> client.send(prepared, HttpSendMode.STANDARD, context));
        assertTrue(sent.isEmpty());
    }

    @Test void failClosedEvenWithContinueErrorStrategyOrMissingVariables() {
        ExecutionContext context = bind("x");
        var bad = step("bad", "other.test");
        var next = step("next", "api.test");
        var result = engine.execute(flow(bad, next), context);
        assertFalse(result.success()); assertTrue(SessionBindingException.isAborted(result.failureMessage())); assertTrue(sent.isEmpty());
        InjectRule missing = new InjectRule("missing", "Missing", true, InjectRule.Target.REQUEST_HEADER, "X-Required",
                InjectRule.ValueSourceType.VARIABLE, "missing", "", InjectRule.WriteStrategy.OVERWRITE, List.of());
        flows.add(flow(step("before", "api.test").withInjectRules(List.of(missing)), live()));
        assertEquals(RequestAction.DROP, dispatch(8, "x").action()); assertTrue(sent.isEmpty());
    }

    @Test void rawAppendAndRegexSessionPlansAreRejectedAsNonRepeatable() {
        for (var target : List.of(InjectRule.Target.REQUEST_RAW_BODY_APPEND, InjectRule.Target.REQUEST_REGEX_REPLACEMENT)) {
            var base = profile("unsafe", "x");
            repository.saveProfile(new SessionProfile(base.id(), base.name(), true, false, base.scope(), base.authConfig(),
                    base.scheduler(), base.state(), List.of(new SessionInjectionRule("append", true, target,
                    target == InjectRule.Target.REQUEST_REGEX_REPLACEMENT ? ".*" : "", "{{session.token}}",
                    InjectRule.WriteStrategy.OVERWRITE, List.of("REPEATER"))), List.of()));
            assertThrows(SessionBindingException.class, () -> manager.prepareBoundSession("unsafe", traffic("x")));
        }
    }

    @Test void handoffIsConsumedOnceKeepsResponseMarkerAndOmitsLargeBody() {
        flows.add(flow(live()));
        var original = request(9, "api.test", 443, true, "/live", List.of(header("X-User", "x")), "large-sensitive-body");
        var action = sessions.handleHttpRequestToBeSent(original);
        var source = sessions.takeSessionForRequest(9).orElseThrow();
        assertEquals("", source.originalTraffic().body()); assertTrue(sessions.takeSessionForRequest(9).isEmpty());
        sessions.handleHttpResponseReceived(response(9, action.request()));
    }

    @Test void failedLoginInSharedAuthManagerInvalidatesBindingEvenWithUnchangedState() {
        InMemoryAccessRepository access = new InMemoryAccessRepository();
        var injection = new AuthInjectionRule("header", "Auth", true, AuthInjectionLocation.HEADER, "Authorization",
                "Bearer {{auth.token}}", AuthInjectionMode.OVERRIDE);
        var provider = new AuthProvider("provider", "Provider", "", true, "login", "", "", List.of(injection),
                List.of(), List.of(), AuthFallbackPolicy.LOGIN_ONLY, AuthRetryPolicy.NO_RETRY);
        access.saveAuthProvider(provider);
        access.saveAuthProfile(new AuthProfile("actor", "Actor", "", provider.id(), Map.of(),
                new AuthState(Map.of("token", "x"), Map.of(), Map.of(), 5000, 100), true));
        var auth = new AuthManager(access, (f,a,p,e,purpose,mode) ->
                AuthFlowRunResult.executed(false, "Failed", 500, Map.of(), "", List.of()), new AuthRuleEvaluator(), CLOCK);
        repository.saveProfile(profile("x", "x").withLinkedAuthProfileId("actor"));
        var linked = new SessionManager(repository, () -> flows, engine, extractions, CLOCK, access, auth, new AuthInjectionRuleRenderer());
        wire(linked, SessionSelectionConfig.disabled(), "x");
        ExecutionContext context = bind("x");
        var state = access.authProfile("actor").orElseThrow().state();
        assertEquals(0, auth.authenticationEpoch("actor"));
        assertTrue(auth.forceAuth("actor", "", Map.of(), RenderMode.STRICT).failed());
        assertTrue(auth.authenticationEpoch("actor") > 0);
        assertEquals(state, access.authProfile("actor").orElseThrow().state());
        assertThrows(SessionBindingException.class, () -> client.prepareForExecution(internal(), context));
        assertTrue(sent.isEmpty());
    }

    @Test void directContextSendAlsoAppliesTheBindingRatherThanTrustingTheCaller() {
        client.send(internal(), HttpSendMode.STANDARD, bind("y"));
        assertAuth(sent.getFirst(), "y");
    }

    @Test void childFlowsInheritBindingAndCannotEscapeDestinationRestriction() {
        var action = new dev.nquangit.corestra.flow.control.StepAction("child", "Child", true,
                dev.nquangit.corestra.flow.control.StepActionType.TRIGGER_FLOW, Map.of("flowId", "child"));
        var rule = new dev.nquangit.corestra.flow.control.StepConditionActionRule("always", "Always", true,
                dev.nquangit.corestra.flow.control.ConditionMatchPolicy.ALL, List.of(), List.of(action));
        var parent = flow(step("parent", "api.test").withConditionActionRules(List.of(rule)), step("next", "api.test"));
        var foreign = step("foreign", "other.test");
        flows.add(new FlowDefinition("child", "Child", "", true, List.of(foreign), null, null, null));
        var result = engine.execute(parent, bind("x"));
        assertFalse(result.success()); assertTrue(SessionBindingException.isAborted(result.failureMessage()));
        assertEquals(List.of("/parent"), sent.stream().map(HttpRequest::path).toList()); assertAuth(sent.getFirst(), "x");
    }

    @Test void changedSessionAfterLiveResponseStopsAllDownstreamSends() throws Exception {
        flows.add(flow(live(), step("after", "api.test")));
        var action = dispatch(15, "x");
        repository.saveProfile(profile("x", "changed"));
        var field = AutoTriggerHttpHandler.class.getDeclaredField("phase3Executor"); field.setAccessible(true);
        ExecutorService executor = (ExecutorService)field.get(trigger);
        trigger.handleHttpResponseReceived(response(15, action.request()));
        executor.shutdown(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(sent.isEmpty()); assertEquals(0, trigger.pendingTriggerCount());
    }

    @Test void concurrentKeepAliveWaitsBrieflyAndPreservesBindingWhenAuthenticationIsUnchanged() throws Exception {
        InMemoryAccessRepository access = new InMemoryAccessRepository();
        var injection = new AuthInjectionRule("header", "Auth", true, AuthInjectionLocation.HEADER, "Authorization",
                "Bearer {{auth.token}}", AuthInjectionMode.OVERRIDE);
        var alive = new AuthSuccessRule("alive", "Alive", true, AuthRuleType.STATUS_CODE, "200", "");
        var provider = new AuthProvider("provider", "Provider", "", true, "login", "", "", List.of(injection),
                List.of(), List.of(), AuthFallbackPolicy.LOGIN_ONLY, AuthRetryPolicy.NO_RETRY, 300L,
                new AuthKeepAliveConfig("keepalive", List.of(alive), List.of()));
        access.saveAuthProvider(provider);
        access.saveAuthProfile(new AuthProfile("actor", "Actor", "", provider.id(), Map.of(),
                new AuthState(Map.of("token", "x"), Map.of(), Map.of(), 5000, 100), true));
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        var auth = new AuthManager(access, (f,a,p,e,purpose,mode) -> {
            assertEquals(AuthFlowPurpose.KEEP_ALIVE, purpose); started.countDown(); await(release);
            return AuthFlowRunResult.executed(true, "OK", 200, Map.of(), "", List.of());
        }, new AuthRuleEvaluator(), CLOCK);
        repository.saveProfile(profile("x", "x").withLinkedAuthProfileId("actor"));
        wire(new SessionManager(repository, () -> flows, engine, extractions, CLOCK, access, auth, new AuthInjectionRuleRenderer()),
                SessionSelectionConfig.disabled(), "x");
        ExecutionContext context = bind("x");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> maintenance = workers.submit(() -> auth.maintainAuth("actor", "", Map.of(), RenderMode.STRICT));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Future<HttpRequest> prepared = workers.submit(() -> client.prepareForExecution(internal(), context));
            release.countDown(); maintenance.get(5, TimeUnit.SECONDS);
            assertAuth(prepared.get(5, TimeUnit.SECONDS), "x"); assertEquals(0, auth.authenticationEpoch("actor"));
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test void triggerMatchesThePreparedAuthHeaderAndKeepsStrippedSelectorAsFallback() {
        wire(manager, new SessionSelectionConfig(true, null, List.of("REPEATER"), 30, "X-User-Selection"), "y");
        var criteria = List.of(new StepTriggerRule.MatchCriterion(StepTriggerRule.MatchCriterion.MatchType.HEADER,
                "X-User-Selection", "yes"), new StepTriggerRule.MatchCriterion(StepTriggerRule.MatchCriterion.MatchType.HEADER,
                "Authorization", "^Bearer y$"));
        flows.add(flow(step("before", "api.test"), live().withTriggerRule(new StepTriggerRule(true, List.of("REPEATER"),
                criteria, StepTriggerRule.ExecutionScope.FULL_FLOW, "", true, List.of()))));
        var original = request(16, "api.test", 443, true, "/live", List.of(header("X-User", "x"),
                header("X-User-Selection", "yes"), header("Authorization", "Bearer snapshot")), "");
        var prepared = (HttpRequestToBeSent)sessions.handleHttpRequestToBeSent(original).request();
        var action = trigger.handleHttpRequestToBeSent(prepared);
        assertEquals(RequestAction.CONTINUE, action.action()); assertAuth(action.request(), "y");
        assertEquals(1, sent.size()); assertAuth(sent.getFirst(), "y");
    }

    @Test void requiredSessionMutationFailureCannotSendTheOldSnapshot() {
        var base = profile("x", "x");
        repository.saveProfile(new SessionProfile(base.id(), base.name(), true, false, base.scope(), base.authConfig(),
                base.scheduler(), base.state(), List.of(new SessionInjectionRule("json", true, InjectRule.Target.REQUEST_JSON_PATH,
                "token", "{{session.token}}", InjectRule.WriteStrategy.OVERWRITE, List.of("REPEATER"))), List.of()));
        assertThrows(SessionBindingException.class, () -> client.send(internal(), HttpSendMode.STANDARD, bind("x")));
        assertTrue(sent.isEmpty());
    }

    @Test void relinkingAnotherActorInvalidatesBindingEvenWithIdenticalTokensAndDelimiterIds() {
        InMemoryAccessRepository access = new InMemoryAccessRepository();
        var injection = new AuthInjectionRule("header", "Auth", true, AuthInjectionLocation.HEADER, "Authorization",
                "Bearer {{auth.token}}", AuthInjectionMode.OVERRIDE);
        for (String id : List.of("provider", "a:provider")) {
            access.saveAuthProvider(new AuthProvider(id, id, "", true, "login", "", "", List.of(injection),
                    List.of(), List.of(), AuthFallbackPolicy.LOGIN_ONLY, AuthRetryPolicy.NO_RETRY));
        }
        var state = new AuthState(Map.of("token", "same"), Map.of(), Map.of(), 5000, 100);
        access.saveAuthProfile(new AuthProfile("actor:a", "X", "", "provider", Map.of(), state, true));
        access.saveAuthProfile(new AuthProfile("actor", "Y", "", "a:provider", Map.of(), state, true));
        var auth = new AuthManager(access, (f,a,p,e,purpose,mode) -> {
            fail("Binding must not authenticate"); return null;
        }, new AuthRuleEvaluator(), CLOCK);
        repository.saveProfile(profile("x", "same").withLinkedAuthProfileId("actor:a"));
        wire(new SessionManager(repository, () -> flows, engine, extractions, CLOCK, access, auth, new AuthInjectionRuleRenderer()),
                SessionSelectionConfig.disabled(), "x");
        ExecutionContext context = bind("x");
        repository.saveProfile(repository.profile("x").orElseThrow().withLinkedAuthProfileId("actor"));
        assertThrows(SessionBindingException.class, () -> client.send(internal(), HttpSendMode.STANDARD, context));
        assertTrue(sent.isEmpty());
    }

    private ExecutionContext bind(String actor) {
        var source = new SessionRequestSession(manager.prepareExistingSession(actor, traffic(actor)), traffic(actor));
        return bindings.bind(ExecutionContext.start(UUID.randomUUID().toString(), "flow", Map.of(), 1000), source, live().triggerRule());
    }
    private static SessionTrafficContext traffic(String actor) { return new SessionTrafficContext("REPEATER", true, "GET", "api.test", "/live", List.of("X-User: " + actor), "", "https", 443); }
    private HttpRequest internal() { return request(-1, "api.test", 443, true, "/internal", List.of(), ""); }
    private RequestToBeSentAction dispatch(int id, String actor) {
        var original = request(id, "api.test", 443, true, "/live", List.of(header("X-User", actor), header("Authorization", "Bearer snapshot")), "");
        var action = sessions.handleHttpRequestToBeSent(original);
        assertEquals(RequestAction.CONTINUE, action.action());
        return trigger.handleHttpRequestToBeSent((HttpRequestToBeSent)action.request());
    }
    private static SessionProfile profile(String id, String token) {
        return new SessionProfile(id, id, true, false, SessionScope.custom(List.of(
                new SessionScopeCriterion(SessionCriterionType.HEADER, "X-User", "^" + id + "$"))),
                SessionAuthConfig.defaults(), SessionSchedulerConfig.disabled(), new SessionState(Map.of("token", token), 100, 5000, 100),
                List.of(new SessionInjectionRule("header", true, InjectRule.Target.REQUEST_HEADER, "Authorization", "Bearer {{session.token}}", InjectRule.WriteStrategy.OVERWRITE, List.of("REPEATER")),
                        new SessionInjectionRule("cookie", true, InjectRule.Target.REQUEST_COOKIE, "token", "{{session.token}}", InjectRule.WriteStrategy.OVERWRITE, List.of("REPEATER"))), List.of());
    }
    private static FlowStep step(String id, String host) {
        return new FlowStep(id, id, true, "", new StoredRequestTemplate("GET", "https", host, 443, "/" + id,
                List.of("Authorization: Bearer snapshot", "Cookie: token=snapshot"), ""), List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.CONTINUE_FLOW);
    }
    private static FlowStep live() {
        return step("live", "api.test").withTriggerRule(new StepTriggerRule(true, List.of("REPEATER"),
                List.of(new StepTriggerRule.MatchCriterion(StepTriggerRule.MatchCriterion.MatchType.PATH, "", "^/live$")),
                StepTriggerRule.ExecutionScope.FULL_FLOW, "", true, List.of()));
    }
    private static FlowDefinition flow(FlowStep... steps) { return new FlowDefinition("flow", "Flow", "", true, List.of(steps), null, null, null); }
    private static void assertAuth(HttpRequest r, String actor) {
        assertEquals("Bearer " + actor, r.headerValue("Authorization")); assertTrue(r.headerValue("Cookie").contains("token=" + actor));
        assertFalse(r.headerValue("Cookie").contains("snapshot"));
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    private static HttpRequestToBeSent fromRaw(HttpService service, String raw) {
        String[] parts = raw.split("\r\n\r\n", 2), lines = parts[0].split("\r\n");
        List<HttpHeader> headers = Arrays.stream(lines).skip(1).map(line -> {
            int colon = line.indexOf(':'); return header(line.substring(0, colon), line.substring(colon + 1).stripLeading());
        }).toList();
        return request(-1, service.host(), service.port(), service.secure(), lines[0].split(" ")[1], headers, parts.length == 2 ? parts[1] : "");
    }
    private static HttpRequestToBeSent request(int id, String host, int port, boolean secure, String path, List<HttpHeader> headers, String body) {
        return proxy(HttpRequestToBeSent.class, (p,m,a) -> switch(m.getName()) {
            case "messageId" -> id;
            case "toolSource" -> proxy(ToolSource.class, (q,n,b) -> n.getName().equals("toolType") ? ToolType.REPEATER : null);
            case "isInScope" -> true;
            case "method" -> "GET";
            case "path" -> path;
            case "httpVersion" -> "HTTP/1.1";
            case "httpService" -> service(host, port, secure);
            case "headers" -> headers;
            case "bodyToString" -> body;
            case "headerValue" -> headers.stream().filter(h -> h.name().equalsIgnoreCase((String)a[0])).map(HttpHeader::value).findFirst().orElse(null);
            case "hasHeader" -> headers.stream().anyMatch(h -> h.name().equalsIgnoreCase((String)a[0]));
            case "withMethod" -> request(id, host, port, secure, path, headers, body);
            case "withPath" -> request(id, host, port, secure, (String)a[0], headers, body);
            case "withRemovedHeaders" -> request(id, host, port, secure, path, List.of(), body);
            case "withAddedHeaders" -> {
                List<HttpHeader> updated = new ArrayList<>(headers); updated.addAll((List<HttpHeader>)a[0]);
                yield request(id, host, port, secure, path, updated, body);
            }
            case "withRemovedHeader" -> request(id, host, port, secure, path, headers.stream().filter(h -> !h.name().equalsIgnoreCase((String)a[0])).toList(), body);
            case "withBody" -> request(id, host, port, secure, path, headers, (String)a[0]);
            case "toString" -> "GET " + path + " HTTP/1.1\r\n" + String.join("\r\n", headers.stream().map(Object::toString).toList()) + "\r\n\r\n" + body;
            case "equals" -> p == a[0];
            case "hashCode" -> System.identityHashCode(p);
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }
    private static HttpResponseReceived response(int id, HttpRequest request) {
        return proxy(HttpResponseReceived.class, (p,m,a) -> switch(m.getName()) {
            case "messageId" -> id;
            case "initiatingRequest" -> request;
            case "toolSource" -> proxy(ToolSource.class, (q,n,b) -> n.getName().equals("toolType") ? ToolType.REPEATER : null);
            case "statusCode" -> (short)200;
            case "httpVersion" -> "HTTP/1.1";
            case "reasonPhrase" -> "OK";
            case "headers" -> List.of();
            case "bodyToString" -> "OK";
            case "toString" -> "HTTP/1.1 200 OK\r\n\r\nOK";
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }
    private static HttpService service(String host, int port, boolean secure) { return proxy(HttpService.class, (p,m,a) -> switch(m.getName()) { case "host" -> host; case "port" -> port; case "secure" -> secure; default -> null; }); }
    private static HttpHeader header(String name, String value) { return proxy(HttpHeader.class, (p,m,a) -> switch(m.getName()) { case "name" -> name; case "value" -> value; case "toString" -> name + ": " + value; default -> null; }); }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler)); }
}
