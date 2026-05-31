package dev.nquangit.corestra.flow.execution;

import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.FlowTriggerConfig.FollowUpCondition;
import dev.nquangit.corestra.flow.FlowTriggerConfig.FollowUpFlowHook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RecoveryFlowEvaluator}.
 */
class RecoveryFlowEvaluatorTest {

    private RecoveryFlowEvaluator evaluator;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        evaluator = new RecoveryFlowEvaluator();
        context = ExecutionContext.start("run-1", "flow-1", Map.of(), 1_000L);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ExecutionResult successResult() {
        return ExecutionResult.success("flow-1", "Flow 1", List.of());
    }

    private static ExecutionResult failureResult() {
        return ExecutionResult.failure("flow-1", "Flow 1", List.of(), "step failed");
    }

    private static FlowTriggerConfig triggerWithHooks(List<FollowUpFlowHook> hooks) {
        return new FlowTriggerConfig(List.of(), List.of(), null, false, hooks);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void noHooksReturnsFalse() {
        FlowTriggerConfig triggerConfig = FlowTriggerConfig.none();

        assertFalse(evaluator.shouldTriggerRecovery(successResult(), context, triggerConfig),
                "No hooks should always return false");
        assertFalse(evaluator.shouldTriggerRecovery(failureResult(), context, triggerConfig),
                "No hooks should always return false");
    }

    @Test
    void alwaysConditionReturnsTrueRegardlessOfOutcome() {
        FlowTriggerConfig triggerConfig = triggerWithHooks(List.of(
                new FollowUpFlowHook(FollowUpCondition.ALWAYS, "recovery-flow")));

        assertTrue(evaluator.shouldTriggerRecovery(successResult(), context, triggerConfig),
                "ALWAYS condition should trigger on success");
        assertTrue(evaluator.shouldTriggerRecovery(failureResult(), context, triggerConfig),
                "ALWAYS condition should trigger on failure");
    }

    @Test
    void onSuccessConditionReturnsTrueOnlyForSuccessfulResult() {
        FlowTriggerConfig triggerConfig = triggerWithHooks(List.of(
                new FollowUpFlowHook(FollowUpCondition.ON_SUCCESS, "follow-up-flow")));

        assertTrue(evaluator.shouldTriggerRecovery(successResult(), context, triggerConfig),
                "ON_SUCCESS condition should trigger on success");
        assertFalse(evaluator.shouldTriggerRecovery(failureResult(), context, triggerConfig),
                "ON_SUCCESS condition should not trigger on failure");
    }

    @Test
    void onFailureConditionReturnsTrueOnlyForFailedResult() {
        FlowTriggerConfig triggerConfig = triggerWithHooks(List.of(
                new FollowUpFlowHook(FollowUpCondition.ON_FAILURE, "recovery-flow")));

        assertFalse(evaluator.shouldTriggerRecovery(successResult(), context, triggerConfig),
                "ON_FAILURE condition should not trigger on success");
        assertTrue(evaluator.shouldTriggerRecovery(failureResult(), context, triggerConfig),
                "ON_FAILURE condition should trigger on failure");
    }

    @Test
    void multipleHooksReturnsTrueWhenAnyConditionIsMet() {
        // Mix: ON_SUCCESS won't match on failure, but ON_FAILURE will
        FlowTriggerConfig triggerConfig = triggerWithHooks(List.of(
                new FollowUpFlowHook(FollowUpCondition.ON_SUCCESS, "success-flow"),
                new FollowUpFlowHook(FollowUpCondition.ON_FAILURE, "failure-flow")));

        assertTrue(evaluator.shouldTriggerRecovery(failureResult(), context, triggerConfig),
                "Should return true when at least one hook condition is met");
    }

    @Test
    void noMatchingHooksReturnsFalse() {
        // Only ON_SUCCESS hook, but result is failure
        FlowTriggerConfig triggerConfig = triggerWithHooks(List.of(
                new FollowUpFlowHook(FollowUpCondition.ON_SUCCESS, "success-flow")));

        assertFalse(evaluator.shouldTriggerRecovery(failureResult(), context, triggerConfig),
                "No matching hook conditions should return false");
    }
}
