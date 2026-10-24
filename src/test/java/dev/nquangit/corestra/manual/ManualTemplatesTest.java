package dev.nquangit.corestra.manual;

import dev.nquangit.corestra.flow.*;
import dev.nquangit.corestra.flow.control.*;
import dev.nquangit.corestra.flow.rule.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ManualTemplatesTest {
    static FlowStep step(String id, String path, String body, List<InjectRule> rules) {
        return new FlowStep(id,id,true,"notes",new StoredRequestTemplate("POST","https","api.test",443,path,List.of("Content-Type: application/json"),body),
                List.of(new ExtractRule("extract","Token",true,ExtractRule.SourceSide.RESPONSE,ExtractRule.ExtractorType.JSON_PATH,"token","token",List.of())),rules,List.of(),FlowStep.StepErrorStrategy.CONTINUE_FLOW);
    }
    static FlowDefinition flow(String id, FlowStep... steps) { return new FlowDefinition(id,id,"description",true,List.of(steps),null,null,null); }
    static InjectRule rule(String id, InjectRule.Target target, String selector, InjectRule.ValueSourceType type, String key, String value, List<String> transforms) {
        return new InjectRule(id,id,true,target,selector,type,key,value,InjectRule.WriteStrategy.OVERWRITE,transforms);
    }

    @Test void rendersSinglePassEmptyValuesExplicitEncodingAndLiteralBraces() {
        assertEquals("Bearer abc / a%2Fb / / {{token}} / {{literal}}", ManualRequestTemplate.render("Bearer {{token}} / {{path|urlEncode}} /{{empty}}/ {{nested}} / \\{{literal}}",
                Map.of("token","abc","path","a/b","empty"," ","nested","{{token}}")));
        assertEquals("Bearer abc",ManualRequestTemplate.render("{{token|prefix:Bearer }}",Map.of("token","abc")));
        assertEquals("abc",ManualRequestTemplate.render("{{token|trim|substring:0:3}}",Map.of("token"," abcdef ")));
    }
    @Test void unresolvedOrInvalidPlaceholdersAndTransformsFailWithoutEchoingInputValues() {
        for(String template:List.of("{{missing}}","{{}}","{{x","{{x|unknown}}","{{x|}}","{{a b}}"))
            assertThrows(IllegalArgumentException.class,()->ManualRequestTemplate.render(template,Map.of("x","secret")),template);
    }
    @Test void parsesEditsWhileKeepingRoutingIndependentFromHostAndPreservingUnrelatedQueryBytes() {
        var t=ManualRequestCodec.parse("https://api.test:8443","PATCH /?x={{x}}&path=/login.php HTTP/1.1\r\nHost: virtual.test\r\nX-Test: {{x}}\r\n\r\n{{body}}");
        var rendered=ManualRequestTemplate.render(t,Map.of("x","hello/world","body","payload"));
        assertEquals("api.test",rendered.host()); assertEquals(8443,rendered.port()); assertEquals("PATCH",rendered.method());
        assertEquals("/?x=hello/world&path=/login.php",rendered.pathAndQuery()); assertEquals("payload",rendered.body());
        assertTrue(rendered.headerLines().contains("Host: virtual.test")); assertEquals(t,ManualRequestCodec.parse(ManualRequestCodec.origin(t),ManualRequestCodec.format(t)));
        assertThrows(IllegalArgumentException.class,()->ManualRequestCodec.parse("https://api.test/path","GET / HTTP/1.1\r\n\r\n"));
        assertThrows(IllegalArgumentException.class,()->ManualRequestCodec.parse("https://api.test","GET / HTTP/1.1\r\nInvalid header\r\n\r\n"));
    }
    @Test void cloneTurnsQueryJsonAndBearerInjectionsIntoEditableTemplatesWithoutChangingSource() {
        var original=flow("source",step("one","/?id=old&path=/login.php","{}",List.of(
                rule("q",InjectRule.Target.REQUEST_QUERY_PARAMETER,"id",InjectRule.ValueSourceType.VARIABLE,"id","",List.of()),
                rule("json",InjectRule.Target.REQUEST_JSON_PATH,"token",InjectRule.ValueSourceType.VARIABLE,"token","",List.of()),
                rule("auth",InjectRule.Target.REQUEST_HEADER,"Authorization",InjectRule.ValueSourceType.TEMPLATE,"","Bearer {{token}}",List.of()))));
        var imported=new ManualFlowImporter().copy(original,List.of(original)); var copy=imported.flows().getFirst(); var s=copy.steps().getFirst();
        assertNotEquals(original.id(),copy.id());assertNotEquals("one",s.id()); assertTrue(s.injectRules().isEmpty()); assertTrue(imported.notices().isEmpty());
        assertEquals(original.steps().getFirst().extractRules(),s.extractRules());
        var rendered=ManualRequestTemplate.render(s.requestTemplate(),Map.of("id","a/b","token","a\"b"));
        assertEquals("/?id=a%2Fb&path=/login.php",rendered.pathAndQuery()); assertEquals("{\"token\":\"a\\\"b\"}",rendered.body());
        assertTrue(rendered.headerLines().contains("Authorization: Bearer a\"b"));
        assertEquals(3,original.steps().getFirst().injectRules().size()); assertEquals("{}",original.steps().getFirst().requestTemplate().body());
        assertFalse(s.triggerRule().enabled()); assertFalse(copy.triggerConfig().sessionHandlingIntegrationEnabled());
    }
    @Test void manualLiteralOverrideNoLongerNeedsTheOriginalVariable() {
        var original=flow("f",step("s","/","",List.of(rule("r",InjectRule.Target.REQUEST_RAW_BODY_REPLACE,"",InjectRule.ValueSourceType.VARIABLE,"token","",List.of()))));
        var t=new ManualFlowImporter().copy(original,List.of(original)).flows().getFirst().steps().getFirst().requestTemplate();
        assertEquals("{{token}}",t.body());
        var edited=new StoredRequestTemplate(t.method(),t.scheme(),t.host(),t.port(),t.pathAndQuery(),t.headerLines(),"manually-tampered");
        assertEquals("manually-tampered",ManualRequestTemplate.render(edited,Map.of()).body());
    }
    @Test void retainedComplexRulesKeepOrderingAndCapturedDoubleBracesStayLiteral() {
        var complex=rule("regex",InjectRule.Target.REQUEST_REGEX_REPLACEMENT,"old",InjectRule.ValueSourceType.STATIC,"","$1",List.of());
        var after=rule("later",InjectRule.Target.REQUEST_HEADER,"X-Later",InjectRule.ValueSourceType.VARIABLE,"value","",List.of());
        var original=flow("f",step("s","/","old {{literal}}",List.of(complex,after)));
        var result=new ManualFlowImporter().copy(original,List.of(original));var copy=result.flows().getFirst().steps().getFirst();
        assertEquals(List.of(complex,after),copy.injectRules());assertEquals(2,result.notices().size());
        assertEquals("old {{literal}}",ManualRequestTemplate.render(copy.requestTemplate().body(),Map.of()));
    }
    @Test void copiesChildrenAndCyclesOnceWithRemappedActionAndPreconditionReferences() {
        var trigger=new StepAction("a","Child",true,StepActionType.TRIGGER_FLOW,Map.of("flowId","child"));
        var condition=new StepConditionActionRule("rule","Always",true,ConditionMatchPolicy.ALL,List.of(),List.of(trigger));
        var one=step("one","/","",List.of()).withConditionActionRules(List.of(condition));
        var two=new FlowStep("two","two",true,"",one.requestTemplate(),List.of(),List.of(),
                List.of(new FlowStep.StepPrecondition(FlowStep.PreconditionType.STEP_COMPLETED,"one","before")),FlowStep.StepErrorStrategy.FAIL_FLOW);
        var root=flow("root",one,two);
        var back=new StepAction("back","Back",true,StepActionType.TRIGGER_FLOW,Map.of("flowId","root"));
        var child=flow("child",step("c","/child","",List.of()).withConditionActionRules(List.of(new StepConditionActionRule("r","r",true,ConditionMatchPolicy.ALL,List.of(),List.of(back)))));
        var copies=new ManualFlowImporter().copy(root,List.of(root,child)).flows();assertEquals(2,copies.size());
        assertEquals(copies.get(1).id(),copies.getFirst().steps().getFirst().conditionActionRules().getFirst().actions().getFirst().parameters().get("flowId"));
        assertEquals(copies.getFirst().id(),copies.get(1).steps().getFirst().conditionActionRules().getFirst().actions().getFirst().parameters().get("flowId"));
        assertEquals(copies.getFirst().steps().getFirst().id(),copies.getFirst().steps().get(1).preconditions().getFirst().subjectKey());
        assertThrows(IllegalArgumentException.class,()->new ManualFlowImporter().copy(root,List.of(root)));
    }
}
