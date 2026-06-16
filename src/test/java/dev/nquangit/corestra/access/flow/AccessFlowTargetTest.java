package dev.nquangit.corestra.access.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccessFlowTargetTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void missingRunModeDefaultsToStopAtStepForOldSavedTargets() throws Exception {
        ObjectNode json = mapper.valueToTree(flowTarget(FlowTargetRunMode.FULL_FLOW_EVALUATE_STEP));
        json.remove("runMode");

        AccessFlowTarget restored = mapper.treeToValue(json, AccessFlowTarget.class);

        assertEquals(FlowTargetRunMode.STOP_AT_STEP, restored.runMode());
    }

    @Test
    void runModeRoundTripsThroughJson() throws Exception {
        AccessFlowTarget target = flowTarget(FlowTargetRunMode.FULL_FLOW_EVALUATE_STEP);

        AccessFlowTarget restored = mapper.readValue(mapper.writeValueAsString(target), AccessFlowTarget.class);

        assertEquals(FlowTargetRunMode.FULL_FLOW_EVALUATE_STEP, restored.runMode());
        assertEquals(target, restored);
    }

    private static AccessFlowTarget flowTarget(FlowTargetRunMode runMode) {
        FlowDefinition flow = flow();
        return new AccessFlowTarget(
                "flowtarget-1",
                "Access Flow Target",
                flow,
                flow.id(),
                flow.name(),
                "hash-flow",
                "step-1",
                runMode,
                true,
                List.of());
    }

    private static FlowDefinition flow() {
        return new FlowDefinition(
                "flow-1",
                "Flow",
                "",
                true,
                List.of(new FlowStep(
                        "step-1",
                        "Step",
                        true,
                        "",
                        new StoredRequestTemplate(
                                "GET",
                                "https",
                                "example.test",
                                443,
                                "/accounts",
                                List.of(),
                                ""),
                        List.of(),
                        List.of(),
                        List.of(),
                        FlowStep.StepErrorStrategy.FAIL_FLOW)),
                FlowRuleSet.empty(),
                AggregationPlan.disabled(),
                FlowTriggerConfig.none());
    }
}
