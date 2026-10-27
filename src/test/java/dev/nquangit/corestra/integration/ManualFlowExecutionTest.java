package dev.nquangit.corestra.integration;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.internal.*;
import dev.nquangit.corestra.flow.*;
import dev.nquangit.corestra.flow.control.*;
import dev.nquangit.corestra.flow.execution.*;
import dev.nquangit.corestra.flow.rule.*;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.manual.*;
import dev.nquangit.corestra.persistence.HttpSendMode;
import dev.nquangit.corestra.session.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import static dev.nquangit.corestra.integration.ManualTestMessages.*;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("MontoyaObjectFactory")
class ManualFlowExecutionTest {
    private MontoyaObjectFactory previous;
    private final List<HttpRequest> sent=new ArrayList<>();
    private final List<HttpSendMode> modes=new ArrayList<>();
    private final List<FlowDefinition> catalog=new ArrayList<>();
    private final SessionProfileRepository profiles=new SessionProfileRepository();
    private final FlowExecutionLog log=new FlowExecutionLog();
    private final AtomicInteger prompts=new AtomicInteger();
    private final AtomicReference<String> choice=new AtomicReference<>("x");
    private final AtomicReference<Function<HttpRequest,HttpResponse>> respond=new AtomicReference<>(r->response("{\"token\":\"from-response\"}"));
    private ManualTestRunner runner;
    private ManualSessionHttpClient transport;
    private FlowEngine engine;
    private SessionManager manager;
    private SessionSelectionConfig config=SessionSelectionConfig.disabled();
    @BeforeEach void setup(){
        previous=ObjectFactoryLocator.FACTORY;ObjectFactoryLocator.FACTORY=factory(); profiles.saveProfile(SessionProfile.general().withEnabled(false));
        profiles.saveProfile(profile("x"));profiles.saveProfile(profile("y"));
        var extraction=new ExtractionService();var injection=new InjectionService();
        manager=new SessionManager(profiles,List::of,new FlowEngine(extraction,injection,r->{fail("Must not login");return null;}),extraction,Clock.fixed(Instant.ofEpochMilli(1000),ZoneOffset.UTC));
        var selection=new SessionSelectionService(manager,()->config,(ctx,choices,timeout)->{prompts.incrementAndGet();return Optional.ofNullable(choice.get());});
        transport=new ManualSessionHttpClient(new HttpClient(){
            public HttpResponse send(HttpRequest request){return send(request,null);}
            public HttpResponse send(HttpRequest request,HttpSendMode mode){sent.add(request);modes.add(mode);return respond.get().apply(request);}
        },manager,selection,injection);
        engine=new FlowEngine(extraction,injection,transport,id->catalog.stream().filter(f->f.id().equals(id)).findFirst(),ms->{});
        engine.addExecutionListener(log::record);runner=new ManualTestRunner(engine,transport);
    }
    @AfterEach void cleanup(){ObjectFactoryLocator.FACTORY=previous;assertFalse(SessionGuard.isActive());}
    private static SessionProfile profile(String id){return new SessionProfile(id,id,true,false,SessionScope.custom(List.of(new SessionScopeCriterion(SessionCriterionType.HEADER,"X-User","^"+id+"$"))),
            SessionAuthConfig.defaults(),SessionSchedulerConfig.disabled(),new SessionState(Map.of("token",id),100,5000,100),List.of(new SessionInjectionRule("cookie",true,InjectRule.Target.REQUEST_COOKIE,"token","{{session.token}}",InjectRule.WriteStrategy.OVERWRITE,List.of("REPEATER"))),List.of());}
    private static FlowStep step(String id,String path,String body){return new FlowStep(id,id,true,"",new StoredRequestTemplate("POST","https","api.test",443,path,List.of("Host: virtual.test","Authorization: snapshot","Cookie: token=old"),body),List.of(),List.of(),List.of(),FlowStep.StepErrorStrategy.CONTINUE_FLOW);}
    private static FlowDefinition flow(FlowStep...steps){return new FlowDefinition("flow","Flow","",true,List.of(steps),null,null,null);}
    private static ManualRunOptions fixed(String id){return new ManualRunOptions(ManualRunOptions.Mode.FIXED,id,"REPEATER",List.of());}
    private ManualTestRunner.Outcome run(FlowDefinition f,Map<String,String> input,ManualRunOptions options){return runner.run(f,0,f.steps().size()-1,false,input,ManualTestRunner.State.empty(),options,new AtomicBoolean());}

    @Test void extractsFromEarlierResponseRendersTemplatesAndAllowsLiteralOverride(){
        var extract=new ExtractRule("e","Token",true,ExtractRule.SourceSide.RESPONSE,ExtractRule.ExtractorType.JSON_PATH,"token","token",List.of());
        var f=flow(step("one","/one","").withExtractRules(List.of(extract)),step("two","/two","{{token}}"));
        var out=run(f,Map.of(),new ManualRunOptions(ManualRunOptions.Mode.NONE,"","REPEATER",List.of()));assertTrue(out.result().success());
        assertEquals("from-response",sent.get(1).bodyToString()); assertEquals(1,sent.get(1).headers().stream().filter(h->h.name().equalsIgnoreCase("Host")).count());
        assertEquals("virtual.test",sent.get(1).headerValue("Host"));assertEquals("api.test",sent.get(1).httpService().host());
        assertEquals("from-response",out.state().variables().get("token"));
        var edited=flow(f.steps().getFirst(),step("two","/two","tampered"));sent.clear();assertTrue(run(edited,Map.of(),new ManualRunOptions(ManualRunOptions.Mode.NONE,"","REPEATER",List.of())).result().success());
        assertEquals("tampered",sent.get(1).bodyToString());
    }
    @Test void filteredJsonPathExtractionFeedsTheNextRequestWithoutPickingAnotherActor() {
        respond.set(request -> response("{\"tokens\":[{\"role\":\"guest\",\"value\":\"guest-token\"},{\"role\":\"admin\",\"value\":\"admin-token\"}]}"));
        var extract = new ExtractRule("json", "Admin token", true, ExtractRule.SourceSide.RESPONSE, ExtractRule.ExtractorType.JSON_PATH,
                "$.tokens[?(@.role == 'admin')].value", "selectedToken", List.of());
        var outcome = run(flow(step("one", "/one", "").withExtractRules(List.of(extract)),
                step("two", "/two", "{{selectedToken}}")), Map.of(), fixed("x"));
        assertTrue(outcome.result().success(), outcome.result().failureMessage());
        assertEquals("admin-token", sent.getLast().bodyToString());
        assertEquals("token=x", sent.getLast().headerValue("Cookie"));
    }
    @Test void missingInlineVariableStopsEvenWhenContinueOnErrorIsConfigured(){
        var out=run(flow(step("bad","/bad","{{missing}}"),step("next","/next","")),Map.of(),ManualRunOptions.defaults());
        assertFalse(out.result().success());assertTrue(sent.isEmpty());assertTrue(out.result().failureMessage().contains("Missing manual variable"));
    }
    @Test void injectionGateSkipsMissingAndInteractiveInputsButKeepsTemplatesExtractionAndSessionActive() {
        var missing = new InjectRule("input", "Input", true, InjectRule.Target.REQUEST_HEADER, "X-Input",
                InjectRule.ValueSourceType.VARIABLE, "absent", "", null);
        var interactive = new InjectRule("otp", "OTP", true, InjectRule.Target.REQUEST_HEADER, "X-OTP",
                InjectRule.ValueSourceType.INTERACTIVE, "", "", null);
        var extract = new ExtractRule("token", "Token", true, ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.JSON_PATH, "token", "extracted", List.of());
        var step = step("one", "/one", "{{body}}").withInjectRules(List.of(missing, interactive))
                .withExtractRules(List.of(extract)).withManualInjectRulesEnabled(false);
        var outcome = run(flow(step), Map.of("body", "edited"), fixed("x"));
        assertTrue(outcome.result().success(), outcome.result().failureMessage());
        assertEquals("edited", sent.getFirst().bodyToString());
        assertEquals("token=x", sent.getFirst().headerValue("Cookie"));
        assertFalse(sent.getFirst().hasHeader("X-Input")); assertFalse(sent.getFirst().hasHeader("X-OTP"));
        assertEquals("from-response", outcome.state().variables().get("extracted"));
        assertTrue(step.injectRules().stream().allMatch(InjectRule::enabled));
        sent.clear();
        assertFalse(run(flow(step.withManualInjectRulesEnabled(true)), Map.of("body", "edited"), fixed("x")).result().success());
        assertTrue(sent.isEmpty());
        assertFalse(run(flow(step), Map.of(), fixed("x")).result().success(), "Pausing injections must not bypass missing inline variables");
        assertTrue(sent.isEmpty());
    }

    @Test void restoringInjectionGateRunsOnlyOriginallyEnabledRulesAndDoesNotAffectOrdinaryFlowExecution() {
        var active = new InjectRule("active", "Active", true, InjectRule.Target.REQUEST_HEADER, "X-Active",
                InjectRule.ValueSourceType.VARIABLE, "value", "", null);
        var disabled = new InjectRule("disabled", "Disabled", false, InjectRule.Target.REQUEST_HEADER, "X-Disabled",
                InjectRule.ValueSourceType.STATIC, "", "disabled", null);
        var step = step("one", "/one", "").withInjectRules(List.of(active, disabled));
        var paused = step.withManualInjectRulesEnabled(false);
        assertTrue(run(flow(paused), Map.of(), fixed("x")).result().success());
        assertFalse(sent.getLast().hasHeader("X-Active"));
        assertTrue(run(flow(paused.withManualInjectRulesEnabled(true)), Map.of("value", "restored"), fixed("x")).result().success());
        assertEquals("restored", sent.getLast().headerValue("X-Active")); assertFalse(sent.getLast().hasHeader("X-Disabled"));
        assertEquals(List.of(active, disabled), paused.injectRules());
        var ordinary = new FlowEngine(new ExtractionService(), new InjectionService(), request -> { sent.add(request); return response("ok"); });
        assertTrue(ordinary.execute(flow(paused), ExecutionContext.start("ordinary", "flow", Map.of(), 1).withVariable("value", "ordinary")).success());
        assertEquals("ordinary", sent.getLast().headerValue("X-Active"));
    }

    @Test void childFlowsHonorTheirOwnManualInjectionGate() {
        var action = new StepAction("a", "Child", true, StepActionType.TRIGGER_FLOW, Map.of("flowId", "child"));
        var parent = step("one", "/one", "").withConditionActionRules(List.of(new StepConditionActionRule("r", "r", true,
                ConditionMatchPolicy.ALL, List.of(), List.of(action))));
        var missing = new InjectRule("missing", "Missing", true, InjectRule.Target.REQUEST_HEADER, "X-Missing",
                InjectRule.ValueSourceType.VARIABLE, "not-provided", "", null);
        catalog.add(new FlowDefinition("child", "Child", "", true, List.of(step("child-step", "/child", "{{value}}")
                .withInjectRules(List.of(missing)).withManualInjectRulesEnabled(false)), null, null, null));
        assertTrue(run(flow(parent), Map.of("value", "child-body"), fixed("x")).result().success());
        assertEquals(2, sent.size()); assertEquals("child-body", sent.getLast().bodyToString());
        assertFalse(sent.getLast().hasHeader("X-Missing")); assertEquals("token=x", sent.getLast().headerValue("Cookie"));
    }
    @Test void stepModeRetainsEditableVariablesAndSuppressesActionsUntilRangeRun(){
        var extract=new ExtractRule("e","Token",true,ExtractRule.SourceSide.RESPONSE,ExtractRule.ExtractorType.JSON_PATH,"token","token",List.of());
        var f=flow(step("one","/one","").withExtractRules(List.of(extract)),step("two","/two","{{token}}"));
        var first=runner.run(f,0,0,true,Map.of(),ManualTestRunner.State.empty(),fixed("x"),new AtomicBoolean());
        var next=runner.run(f,1,1,true,Map.of("token","edited-between-steps"),first.state(),fixed("x"),new AtomicBoolean());
        assertTrue(next.result().success());assertEquals("edited-between-steps",sent.get(1).bodyToString());assertEquals(2,next.state().history().size());
        assertTrue(next.state().history().values().stream().flatMap(List::stream).allMatch(r->r.renderedRequest().isEmpty()&&r.receivedResponse().isEmpty()));
    }
    @Test void fixedSessionOverridesSnapshotForAllStepsRetainsModeAndIsRecordedAsSent(){
        var f=flow(step("one","/one","").withSendModeOverride(HttpSendMode.PROXY_PIPELINE),step("two","/two","").withSendModeOverride(HttpSendMode.STANDARD));
        var out=run(f,Map.of(),fixed("y"));assertTrue(out.result().success());assertEquals(2,sent.size());sent.forEach(r->assertEquals("token=y",r.headerValue("Cookie")));
        assertEquals(List.of(HttpSendMode.PROXY_PIPELINE,HttpSendMode.STANDARD),modes);
        assertTrue(out.result().stepRecords().stream().allMatch(r->r.renderedRequest().contains("token=y")));
    }
    @Test void scopedSelectionUsesConfiguredHeaderStripsItAndAsksOnlyOnce(){
        config=new SessionSelectionConfig(true,null,List.of("REPEATER"),30,"X-User-Selection");choice.set("y");
        var one=step("one","/one","").withRequestTemplate(new StoredRequestTemplate("GET","https","api.test",443,"/one",List.of("X-User-Selection: ask"),""));
        var out=run(flow(one,step("two","/two","")),Map.of(),ManualRunOptions.defaults());
        assertTrue(out.result().success());assertEquals(1,prompts.get());sent.forEach(r->{assertEquals("token=y",r.headerValue("Cookie"));assertFalse(r.hasHeader("X-User-Selection"));});
    }
    @Test void explicitChoiceCancellationDisabledProfileAndUnknownContextsFailClosed(){
        choice.set(null);var ask=new ManualRunOptions(ManualRunOptions.Mode.ASK,"","REPEATER",List.of());
        assertFalse(run(flow(step("one","/one","")),Map.of(),ask).result().success());assertTrue(sent.isEmpty());
        profiles.saveProfile(profile("x").withEnabled(false));assertFalse(run(flow(step("one","/one","")),Map.of(),fixed("x")).result().success());assertTrue(sent.isEmpty());
        assertThrows(SessionBindingException.class,()->transport.send(parse(service("api.test",443,true),"GET / HTTP/1.1\r\n\r\n")));
    }
    @Test void sessionEpochChangesBetweenStepRunsRequireResetRatherThanRebindingSilently(){
        var f=flow(step("one","/one",""),step("two","/two",""));
        var first=runner.run(f,0,0,true,Map.of(),ManualTestRunner.State.empty(),fixed("x"),new AtomicBoolean());
        manager.clearState("x");profiles.saveProfile(profile("x"));
        var second=runner.run(f,1,1,true,Map.of(),first.state(),fixed("x"),new AtomicBoolean());
        assertFalse(second.result().success());assertEquals(1,sent.size());
    }
    @Test void pinnedSessionRejectsOtherOriginAndAllowsExplicitAdditionalOrigin(){
        var foreign=step("two","/two","").withRequestTemplate(new StoredRequestTemplate("GET","https","files.test",443,"/two",List.of(),""));
        assertThrows(SessionBindingException.class,()->run(flow(step("one","/one",""),foreign),Map.of(),fixed("x")));assertTrue(sent.isEmpty());
        assertTrue(run(flow(step("one","/one",""),foreign),Map.of(),new ManualRunOptions(ManualRunOptions.Mode.FIXED,"x","REPEATER",List.of("https://files.test"))).result().success());assertEquals(2,sent.size());
    }
    @Test void cancellationAfterFirstSendStopsRemainingRequestsAndPreservesTheCompletedResponse(){
        AtomicBoolean cancelled=new AtomicBoolean();respond.set(r->{cancelled.set(true);return response("done");});
        var f=flow(step("one","/one",""),step("two","/two",""));
        var out=runner.run(f,0,1,false,Map.of(),ManualTestRunner.State.empty(),ManualRunOptions.defaults(),cancelled);
        assertFalse(out.result().success());assertEquals(1,sent.size());assertTrue(out.result().stepRecords().getFirst().receivedResponse().contains("done"));
    }
    @Test void binaryResponseUsesExactBodyBytesBeforeBase64AndPrefixTemplate(){
        byte[] binary={0,1,(byte)0xff,(byte)0x80,13,10};respond.set(r->binaryResponse(binary));
        var extract=new ExtractRule("image","Image",true,ExtractRule.SourceSide.RESPONSE,ExtractRule.ExtractorType.BODY_RAW,"","image",List.of("base64Encode"));
        var out=run(flow(step("image","/image","").withExtractRules(List.of(extract)),step("use","/use","data:image/png;base64,{{image}}")),Map.of(),ManualRunOptions.defaults());
        assertTrue(out.result().success());assertEquals("data:image/png;base64,"+Base64.getEncoder().encodeToString(binary),sent.get(1).bodyToString());
    }
    @Test void scopedModePreservesStaticRegexAndAppendStrategiesWithoutRequiringAPinnedSession() {
        var profile = new SessionProfile("static", "Static", true, false, SessionScope.custom(List.of()), SessionAuthConfig.defaults(),
                SessionSchedulerConfig.disabled(), SessionState.empty(), List.of(
                new SessionInjectionRule("regex", true, InjectRule.Target.REQUEST_REGEX_REPLACEMENT, "old", "replaced", InjectRule.WriteStrategy.OVERWRITE, List.of("REPEATER")),
                new SessionInjectionRule("header", true, InjectRule.Target.REQUEST_HEADER, "X-Extra", "added", InjectRule.WriteStrategy.APPEND, List.of("REPEATER"))), List.of());
        profiles.saveProfile(profile);
        var s = step("one", "/one", "old").withRequestTemplate(new StoredRequestTemplate("POST", "https", "api.test", 443, "/one", List.of("X-Extra: original"), "old"));
        assertTrue(run(flow(s), Map.of(), ManualRunOptions.defaults()).result().success());
        assertEquals("replaced", sent.getFirst().bodyToString());
        assertEquals(List.of("original", "added"), sent.getFirst().headers().stream().filter(h -> h.name().equalsIgnoreCase("X-Extra")).map(h -> h.value()).toList());
    }
    @Test void scopedSessionMutationFailureIsTerminalEvenWithContinueStrategy() {
        profiles.saveProfile(new SessionProfile("json", "Json", true, false, SessionScope.custom(List.of()), SessionAuthConfig.defaults(),
                SessionSchedulerConfig.disabled(), SessionState.empty(), List.of(new SessionInjectionRule("json", true, InjectRule.Target.REQUEST_JSON_PATH,
                "token", "literal", InjectRule.WriteStrategy.OVERWRITE, List.of("REPEATER"))), List.of()));
        var out = run(flow(step("one", "/one", "not-json"), step("two", "/two", "{}")), Map.of(), ManualRunOptions.defaults());
        assertFalse(out.result().success()); assertTrue(sent.isEmpty());
    }
    @Test void ordinaryEngineLeavesCapturedBraceTextUntouchedUnlessManualTemplatesAreEnabled() {
        var legacy = new FlowEngine(new ExtractionService(), new InjectionService(), r -> { sent.add(r); return response("OK"); });
        assertTrue(legacy.execute(flow(step("one", "/one", "{{literal}}")), ExecutionContext.start("legacy", "flow", Map.of(), 1)).success());
        assertEquals("{{literal}}", sent.getFirst().bodyToString());
    }
    @Test void childFlowUsesManualTemplatesSessionAndHasItsOwnBoundedLogEntry(){
        var action=new StepAction("a","Child",true,StepActionType.TRIGGER_FLOW,Map.of("flowId","child"));
        var one=step("one","/one","").withConditionActionRules(List.of(new StepConditionActionRule("r","r",true,ConditionMatchPolicy.ALL,List.of(),List.of(action))));
        catalog.add(new FlowDefinition("child","Child","",true,List.of(step("childstep","/child","{{input}}")),null,null,null));
        assertTrue(run(flow(one),Map.of("input","value"),fixed("x")).result().success());assertEquals(2,sent.size());
        assertEquals("value",sent.get(1).bodyToString());assertEquals("token=x",sent.get(1).headerValue("Cookie"));
        assertTrue(log.snapshot().stream().anyMatch(e->e.flowId().equals("child")));
        sent.clear();
        var selected=runner.run(flow(one),0,0,true,Map.of(),ManualTestRunner.State.empty(),fixed("x"),new AtomicBoolean());assertTrue(selected.result().success());assertEquals(1,sent.size());
    }
    @Test void manualProgressStartsBeforeSendingAndKeepsChildIdentityAndRunsIsolated() {
        var action = new StepAction("a", "Child", true, StepActionType.TRIGGER_FLOW, Map.of("flowId", "child"));
        var one = step("one", "/one", "").withConditionActionRules(List.of(new StepConditionActionRule("r", "r", true, ConditionMatchPolicy.ALL, List.of(), List.of(action))));
        catalog.add(new FlowDefinition("child", "Child", "", true, List.of(step("childstep", "/child", "")), null, null, null));
        List<StepExecutionProgress> events = new ArrayList<>();
        respond.set(request -> { assertEquals(StepExecutionProgress.Phase.STARTED, events.getLast().phase()); return response("ok"); });
        var result = runner.run(flow(one), 0, 0, false, Map.of(), ManualTestRunner.State.empty(), fixed("x"), new AtomicBoolean(), events::add);
        assertTrue(result.result().success());
        assertEquals(List.of("flow:one:STARTED", "flow:one:COMPLETED", "child:childstep:STARTED", "child:childstep:COMPLETED"),
                events.stream().map(e -> e.flowId() + ":" + e.stepId() + ":" + e.phase()).toList());
        assertEquals(1, events.stream().map(StepExecutionProgress::runId).distinct().count());
        String firstRun = events.getFirst().runId(); events.clear();
        runner.run(flow(one), 0, 0, true, Map.of(), ManualTestRunner.State.empty(), fixed("x"), new AtomicBoolean(), events::add);
        assertEquals(2, events.size()); assertNotEquals(firstRun, events.getFirst().runId());
    }
}
