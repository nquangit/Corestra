package dev.nquangit.corestra.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nquangit.corestra.access.security.ExportMode;
import dev.nquangit.corestra.flow.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TriggerInputBindingsPersistenceTest {
    @Test void optionalProfileReferencesRoundTripInAllExportModesWithoutCopyingSecrets() throws Exception {
        var binding = new TriggerInputBindings("actor", "data", List.of("inject-id"));
        var rule = new StepTriggerRule(true, List.of("SCANNER"), List.of(), StepTriggerRule.ExecutionScope.FULL_FLOW,
                "", true, List.of("https://api.test"), binding);
        var step = new FlowStep("step", "Step", true, "", new StoredRequestTemplate("GET", "https", "api.test",
                443, "/", List.of(), ""), List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW).withTriggerRule(rule);
        var flow = new FlowDefinition("flow", "Flow", "", true, List.of(step), null, null, null);
        var serializer = new FlowSerializer();
        assertEquals(rule, serializer.deserialize(serializer.serialize(List.of(flow))).getFirst().steps().getFirst().triggerRule());
        var projectSerializer = new ProjectSerializer(serializer);
        var document = new ProjectDocument(1, ProjectMetadata.createNew(), List.of(flow));
        for (var mode : List.of(ExportMode.FULL_EXPORT, ExportMode.SANITIZED_EXPORT, ExportMode.EXCLUDE_SECRETS)) {
            var exported = projectSerializer.serializeProject(document, mode);
            assertEquals(rule, projectSerializer.deserializeProject(exported).flows().getFirst().steps().getFirst().triggerRule());
            assertTrue(exported.contains("authProfileId"));
            assertFalse(exported.contains("credentials"));
        }
        ObjectNode json = (ObjectNode) new ObjectMapper().readTree(serializer.serialize(List.of(flow)));
        ((ObjectNode) json.path("flows").get(0).path("steps").get(0).path("triggerRule")).remove("inputBindings");
        assertEquals(TriggerInputBindings.none(), serializer.deserialize(json.toString()).getFirst().steps().getFirst().triggerRule().inputBindings());
        assertNotEquals(rule, new StepTriggerRule(true, List.of("SCANNER"), List.of(), StepTriggerRule.ExecutionScope.FULL_FLOW,
                "", true, List.of("https://api.test")));
    }
}
