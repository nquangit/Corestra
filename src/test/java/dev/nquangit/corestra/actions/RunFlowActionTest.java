package dev.nquangit.corestra.actions;

import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the pure helper logic in {@link RunFlowAction}.
 *
 * <p>The engine delegation in {@code run}/{@code runSingleStep} is exercised by
 * {@code FlowEngineTest}; here we verify only the new behaviour {@code RunFlowAction}
 * introduces — building the throwaway single-step flow used for debug runs.</p>
 */
class RunFlowActionTest {

    @Test
    void singleStepFlowContainsOnlyTheChosenStep() {
        FlowStep target = step("s2", "Profile", true);
        FlowDefinition flow = flowWith(
                step("s1", "Login", true),
                target,
                step("s3", "Logout", true));

        FlowDefinition single = RunFlowAction.singleStepFlow(flow, target);

        assertEquals(1, single.steps().size());
        assertEquals("s2", single.steps().get(0).id());
    }

    @Test
    void singleStepFlowPreservesParentIdentityButDisablesAggregation() {
        FlowStep target = step("s1", "Login", true);
        FlowDefinition flow = flowWith(target);

        FlowDefinition single = RunFlowAction.singleStepFlow(flow, target);

        assertEquals(flow.id(), single.id());
        assertEquals(flow.name(), single.name());
        assertFalse(single.aggregation().enabled());
    }

    @Test
    void singleStepFlowForceEnablesADisabledStep() {
        FlowStep disabled = step("s1", "Debug me", false);
        FlowDefinition flow = flowWith(disabled);

        FlowDefinition single = RunFlowAction.singleStepFlow(flow, disabled);

        assertTrue(single.steps().get(0).enabled(),
                "disabled step must be force-enabled so it actually runs");
    }

    @Test
    void singleStepFlowKeepsAnAlreadyEnabledStepInstance() {
        FlowStep enabled = step("s1", "Login", true);
        FlowDefinition flow = flowWith(enabled);

        FlowDefinition single = RunFlowAction.singleStepFlow(flow, enabled);

        assertSame(enabled, single.steps().get(0),
                "an already-enabled step should be reused without copying");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static FlowDefinition flowWith(FlowStep... steps) {
        return new FlowDefinition(
                "flow-1", "Test flow", "", true,
                List.of(steps),
                FlowRuleSet.empty(),
                aggregationEnabled(),
                FlowTriggerConfig.none());
    }

    private static AggregationPlan aggregationEnabled() {
        return new AggregationPlan(true, AggregationPlan.Mode.APPEND_RAW_RESPONSES, "s1", List.of());
    }

    private static FlowStep step(String id, String name, boolean enabled) {
        return new FlowStep(id, name, enabled, "",
                new StoredRequestTemplate("GET", "https", "example.com", 443, "/", List.of(), ""),
                List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
    }
}
