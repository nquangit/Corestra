package dev.nquangit.corestra.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nquangit.corestra.access.security.ExportMode;
import dev.nquangit.corestra.access.flow.AccessFlowTarget;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.access.persistence.AccessWorkspaceDocument;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RequestSendModePersistenceTest {
    private final FlowSerializer serializer = new FlowSerializer();

    @Test
    void oldFlowsInheritAndExplicitRoutesRoundTripInEachProjectExportMode() {
        for (HttpSendMode mode : new HttpSendMode[]{null, HttpSendMode.STANDARD, HttpSendMode.PROXY_PIPELINE}) {
            FlowDefinition flow = flow(step().withSendModeOverride(mode));
            String json = serializer.serialize(List.of(flow));
            assertEquals(mode, serializer.deserialize(json).getFirst().steps().getFirst().sendModeOverride());
            if (mode == null) assertFalse(json.contains("sendModeOverride"));
            ProjectSerializer projectSerializer = new ProjectSerializer(serializer);
            AccessFlowTarget target = new AccessFlowTarget("target", "Target", flow, flow.id(), flow.name(), "",
                    "step", true, List.of());
            ProjectDocument project = new ProjectDocument(1, ProjectMetadata.createNew(), List.of(flow),
                    new AccessWorkspaceDocument(AccessWorkspace.builder().flowTarget(target).build(), List.of()));
            for (ExportMode exportMode : List.of(ExportMode.FULL_EXPORT, ExportMode.SANITIZED_EXPORT, ExportMode.EXCLUDE_SECRETS)) {
                ProjectDocument imported = projectSerializer.deserializeProject(projectSerializer.serializeProject(project, exportMode));
                FlowStep restored = imported.flows().getFirst().steps().getFirst();
                assertEquals(mode, imported.accessWorkspace().flowTargets().get("target")
                        .flowSnapshot().steps().getFirst().sendModeOverride());
                assertEquals(mode, restored.sendModeOverride(), "Route survives " + exportMode);
            }
        }
    }

    @Test
    void unknownOverrideIsRejectedInsteadOfSilentlyChangingRoute() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = (ObjectNode) mapper.readTree(serializer.serialize(List.of(flow(step()))));
        ((ObjectNode) json.path("flows").get(0).path("steps").get(0)).put("sendModeOverride", "INVALID_MODE");
        assertThrows(IllegalArgumentException.class, () -> serializer.deserialize(json.toString()));
    }

    @Test
    void editingAndCapturingStepPreserveItsRouteAndRestoringInheritanceChangesEquality() {
        FlowStep routed = step().withSendModeOverride(HttpSendMode.PROXY_PIPELINE);
        List<FlowStep> edited = List.of(routed.withName("Renamed"), routed.withEnabled(false), routed.withNotes("Notes"),
                routed.withRequestTemplate(step().requestTemplate()), routed.withExtractRules(List.of()),
                routed.withInjectRules(List.of()), routed.withTriggerRule(null), routed.withLastResponseRaw("stored"),
                routed.withIncludeInAggregation(true), routed.withConditionActionRules(List.of()));
        for (FlowStep copy : edited) assertEquals(HttpSendMode.PROXY_PIPELINE, copy.sendModeOverride());
        assertNotEquals(step(), routed);
        assertEquals(step(), routed.withSendModeOverride(null));
    }

    private static FlowDefinition flow(FlowStep step) {
        return new FlowDefinition("flow", "Flow", "", true, List.of(step), null, null, null);
    }

    private static FlowStep step() {
        return new FlowStep("step", "Request", true, "",
                new StoredRequestTemplate("GET", "https", "example.test", 443, "/", List.of(), ""),
                List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
    }
}
