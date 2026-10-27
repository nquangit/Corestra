package dev.nquangit.corestra.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nquangit.corestra.access.security.ExportMode;
import dev.nquangit.corestra.flow.*;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JsonPathResultModePersistenceTest {
    @Test void queryModesSurviveSafeProjectExportsAndOldRulesDefaultToSingle() throws Exception {
        var serializer = new FlowSerializer();
        for (var mode : ExtractRule.JsonResultMode.values()) {
            var rule = new ExtractRule("json", "JSON", true, ExtractRule.SourceSide.RESPONSE, ExtractRule.ExtractorType.JSON_PATH,
                    "$.items[?(@.enabled == true)].id", "ids", List.of("trim")).withJsonResultMode(mode);
            var step = new FlowStep("s", "Step", true, "", new StoredRequestTemplate("GET", "https", "api.test", 443, "/", List.of(), ""),
                    List.of(rule), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
            var flow = new FlowDefinition("f", "Flow", "", true, List.of(step), null, null, null);
            assertEquals(rule, serializer.deserialize(serializer.serialize(List.of(flow))).getFirst().steps().getFirst().extractRules().getFirst());
            var projects = new ProjectSerializer(serializer);
            var project = new ProjectDocument(1, ProjectMetadata.createNew(), List.of(flow)).withManualFlows(List.of(flow));
            for (var export : List.of(ExportMode.FULL_EXPORT, ExportMode.SANITIZED_EXPORT, ExportMode.EXCLUDE_SECRETS)) {
                var restored = projects.deserializeProject(projects.serializeProject(project, export));
                assertEquals(rule, restored.flows().getFirst().steps().getFirst().extractRules().getFirst());
                assertEquals(rule, restored.manualFlows().getFirst().steps().getFirst().extractRules().getFirst());
            }
            ObjectNode json = (ObjectNode) new ObjectMapper().readTree(serializer.serialize(List.of(flow)));
            ((ObjectNode) json.path("flows").get(0).path("steps").get(0).path("extractRules").get(0)).remove("jsonResultMode");
            assertEquals(ExtractRule.JsonResultMode.SINGLE, serializer.deserialize(json.toString()).getFirst().steps().getFirst().extractRules().getFirst().jsonResultMode());
        }
    }
}
