package dev.nquangit.corestra.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nquangit.corestra.access.security.ExportMode;
import dev.nquangit.corestra.flow.*;
import dev.nquangit.corestra.flow.rule.InjectRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManualInjectionGatePersistenceTest {
    private final FlowSerializer serializer = new FlowSerializer();

    @Test void gateAndIndividualRuleFlagsSurviveAllProjectExportModesAndOldDocumentsDefaultToEnabled() throws Exception {
        FlowStep original = step();
        FlowStep paused = original.withManualInjectRulesEnabled(false);
        FlowDefinition flow = flow(paused);
        assertEquals(paused, serializer.deserialize(serializer.serialize(List.of(flow))).getFirst().steps().getFirst());
        var project = new ProjectDocument(1, ProjectMetadata.createNew(), List.of()).withManualFlows(List.of(flow));
        var projects = new ProjectSerializer(serializer);
        for (var mode : List.of(ExportMode.FULL_EXPORT, ExportMode.SANITIZED_EXPORT, ExportMode.EXCLUDE_SECRETS)) {
            var restored = projects.deserializeProject(projects.serializeProject(project, mode)).manualFlows().getFirst().steps().getFirst();
            assertFalse(restored.manualInjectRulesEnabled());
            assertEquals(List.of(true, false), restored.injectRules().stream().map(InjectRule::enabled).toList());
        }
        ObjectNode json = (ObjectNode) new ObjectMapper().readTree(serializer.serialize(List.of(flow)));
        ((ObjectNode) json.path("flows").get(0).path("steps").get(0)).remove("manualInjectRulesEnabled");
        assertTrue(serializer.deserialize(json.toString()).getFirst().steps().getFirst().manualInjectRulesEnabled());
        assertTrue(original.manualInjectRulesEnabled());
        assertNotEquals(original, paused);
        assertEquals(original, paused.withManualInjectRulesEnabled(true));
        assertEquals(original.injectRules(), paused.injectRules());
    }

    @Test void stepEditsRetainTheGateIncludingCaptureAndTransportChanges() {
        FlowStep paused = step().withManualInjectRulesEnabled(false);
        List<FlowStep> edits = List.of(paused.withName("Other"), paused.withEnabled(false), paused.withNotes("Notes"),
                paused.withRequestTemplate(step().requestTemplate()), paused.withExtractRules(List.of()),
                paused.withInjectRules(paused.injectRules()), paused.withTriggerRule(null), paused.withLastResponseRaw("raw"),
                paused.withIncludeInAggregation(true), paused.withConditionActionRules(List.of()), paused.withSendModeOverride(HttpSendMode.PROXY_PIPELINE));
        for (FlowStep edited : edits) assertFalse(edited.manualInjectRulesEnabled());
    }

    private static FlowDefinition flow(FlowStep step) { return new FlowDefinition("flow", "Flow", "", true, List.of(step), null, null, null); }
    private static FlowStep step() {
        return new FlowStep("step", "Step", true, "", new StoredRequestTemplate("GET", "https", "api.test", 443, "/", List.of(), ""),
                List.of(), List.of(rule("active", true), rule("disabled", false)), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
    }
    private static InjectRule rule(String id, boolean enabled) {
        return new InjectRule(id, id, enabled, InjectRule.Target.REQUEST_HEADER, "X-" + id,
                InjectRule.ValueSourceType.STATIC, "", "value", InjectRule.WriteStrategy.OVERWRITE);
    }
}
