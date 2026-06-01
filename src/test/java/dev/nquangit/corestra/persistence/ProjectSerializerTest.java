package dev.nquangit.corestra.persistence;

import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.FlowStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectSerializerTest {

    private final FlowSerializer flowSerializer = new FlowSerializer();
    private final ProjectSerializer serializer = new ProjectSerializer(flowSerializer);

    @Test
    void serializeProjectRoundTrip() {
        ProjectMetadata meta = new ProjectMetadata("MyProj", "desc", "alice", "note", 1000L, 2000L);
        List<FlowDefinition> flows = List.of(sampleFlow("flow-1", "Login"));
        ProjectDocument doc = new ProjectDocument(1, meta, flows);

        String json = serializer.serializeProject(doc);
        ProjectDocument restored = serializer.deserializeProject(json);

        assertEquals(meta, restored.metadata());
        assertEquals(1, restored.flows().size());
        assertEquals("flow-1", restored.flows().get(0).id());
        assertEquals("Login", restored.flows().get(0).name());
        assertEquals(1, restored.schemaVersion());
    }

    @Test
    void serializeFlowsRoundTrip() {
        List<FlowDefinition> flows = List.of(sampleFlow("f1", "A"), sampleFlow("f2", "B"));

        String json = serializer.serializeFlows(flows);
        List<FlowDefinition> restored = serializer.deserializeFlows(json);

        assertEquals(2, restored.size());
        assertEquals("f1", restored.get(0).id());
        assertEquals("f2", restored.get(1).id());
    }

    @Test
    void deserializeFlowsAcceptsProjectFile() {
        ProjectMetadata meta = new ProjectMetadata("P", "", "", "", 1L, 1L);
        List<FlowDefinition> flows = List.of(sampleFlow("fx", "X"));
        String projectJson = serializer.serializeProject(new ProjectDocument(1, meta, flows));

        List<FlowDefinition> extracted = serializer.deserializeFlows(projectJson);

        assertEquals(1, extracted.size());
        assertEquals("fx", extracted.get(0).id());
    }

    @Test
    void deserializeProjectMissingMetadataThrows() {
        String json = "{\"schemaVersion\":1,\"flows\":[]}";
        assertThrows(IllegalArgumentException.class, () -> serializer.deserializeProject(json));
    }

    @Test
    void deserializeProjectUnsupportedVersionThrows() {
        String json = "{\"schemaVersion\":99,\"metadata\":{},\"flows\":[]}";
        assertThrows(IllegalArgumentException.class, () -> serializer.deserializeProject(json));
    }

    @Test
    void deserializeFlowsMissingArrayThrows() {
        String json = "{\"schemaVersion\":1}";
        assertThrows(IllegalArgumentException.class, () -> serializer.deserializeFlows(json));
    }

    @Test
    void deserializeMalformedJsonThrows() {
        assertThrows(IllegalArgumentException.class, () -> serializer.deserializeProject("not json"));
        assertThrows(IllegalArgumentException.class, () -> serializer.deserializeFlows("not json"));
    }

    private static FlowDefinition sampleFlow(String id, String name) {
        FlowStep step = new FlowStep(id + "-step", name + " Step", true, "",
                new StoredRequestTemplate("GET", "https", "example.com", 443,
                        "/path", List.of(), ""),
                List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
        return new FlowDefinition(id, name, "", true,
                List.of(step), FlowRuleSet.empty(),
                AggregationPlan.disabled(), FlowTriggerConfig.none());
    }
}
