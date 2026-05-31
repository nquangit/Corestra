package dev.nquangit.corestra.integration;

import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.StepTriggerRule.ExecutionScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutoTriggerStopIndexTest {

    private static FlowStep stepWithId(String id) {
        return new FlowStep(id, "name", true, "",
                new StoredRequestTemplate("GET", "https", "example.com", 443, "/", List.of(), ""),
                List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
    }

    @Test
    void matchedStepScopeReturnsMatchedIdx() {
        List<FlowStep> steps = List.of(stepWithId("a"), stepWithId("b"), stepWithId("c"));
        assertEquals(1, AutoTriggerHttpHandler.resolveStopIndex(steps, 1, ExecutionScope.MATCHED_STEP, ""));
    }

    @Test
    void fullFlowScopeReturnsLastIdx() {
        List<FlowStep> steps = List.of(stepWithId("a"), stepWithId("b"), stepWithId("c"));
        assertEquals(2, AutoTriggerHttpHandler.resolveStopIndex(steps, 0, ExecutionScope.FULL_FLOW, ""));
    }

    @Test
    void fullFlowScopeWithMatchedAtLastStepReturnsLastIdx() {
        List<FlowStep> steps = List.of(stepWithId("a"), stepWithId("b"));
        assertEquals(1, AutoTriggerHttpHandler.resolveStopIndex(steps, 1, ExecutionScope.FULL_FLOW, ""));
    }

    @Test
    void stopAtStepScopeResolvesTargetId() {
        List<FlowStep> steps = List.of(stepWithId("a"), stepWithId("b"), stepWithId("c"));
        assertEquals(2, AutoTriggerHttpHandler.resolveStopIndex(steps, 0, ExecutionScope.STOP_AT_STEP, "c"));
    }

    @Test
    void stopAtStepScopeFallsBackToMatchedWhenIdNotFound() {
        List<FlowStep> steps = List.of(stepWithId("a"), stepWithId("b"), stepWithId("c"));
        assertEquals(1, AutoTriggerHttpHandler.resolveStopIndex(steps, 1, ExecutionScope.STOP_AT_STEP, "missing-id"));
    }

    @Test
    void stopAtStepScopeFallsBackWhenIdReorderedBeforeMatch() {
        List<FlowStep> steps = List.of(stepWithId("a"), stepWithId("b"), stepWithId("c"));
        assertEquals(1, AutoTriggerHttpHandler.resolveStopIndex(steps, 1, ExecutionScope.STOP_AT_STEP, "a"));
    }

    @Test
    void stopAtStepScopeAllowsMatchedStepIdItself() {
        List<FlowStep> steps = List.of(stepWithId("a"), stepWithId("b"), stepWithId("c"));
        assertEquals(1, AutoTriggerHttpHandler.resolveStopIndex(steps, 1, ExecutionScope.STOP_AT_STEP, "b"));
    }
}
