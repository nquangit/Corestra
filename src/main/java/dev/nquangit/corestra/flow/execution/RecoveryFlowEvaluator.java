package dev.nquangit.corestra.flow.execution;

import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.FlowTriggerConfig.FollowUpCondition;
import dev.nquangit.corestra.flow.FlowTriggerConfig.FollowUpFlowHook;

import java.util.Objects;

/**
 * Evaluates whether a recovery (follow-up) flow should be triggered after a flow execution.
 *
 * <p>Each {@link FollowUpFlowHook} in the {@link FlowTriggerConfig} declares a condition
 * ({@link FollowUpCondition}) that determines when it fires. This evaluator checks whether any
 * hook's condition is satisfied given the execution outcome.
 */
public final class RecoveryFlowEvaluator {

    /**
     * Returns {@code true} if at least one follow-up hook defined in {@code triggerConfig}
     * has its condition satisfied by {@code result}.
     *
     * @param result        the outcome of the flow execution; must not be {@code null}
     * @param context       the execution context at the time of evaluation; must not be {@code null}
     * @param triggerConfig the trigger configuration containing follow-up hooks; must not be {@code null}
     * @return {@code true} when any hook condition is met, otherwise {@code false}
     */
    public boolean shouldTriggerRecovery(
            ExecutionResult result,
            ExecutionContext context,
            FlowTriggerConfig triggerConfig) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(triggerConfig, "triggerConfig");

        if (triggerConfig.followUpHooks().isEmpty()) {
            return false;
        }

        for (FollowUpFlowHook hook : triggerConfig.followUpHooks()) {
            if (isConditionMet(hook.condition(), result)) {
                return true;
            }
        }

        return false;
    }

    private boolean isConditionMet(FollowUpCondition condition, ExecutionResult result) {
        return switch (condition) {
            case ALWAYS -> true;
            case ON_SUCCESS -> result.success();
            case ON_FAILURE -> !result.success();
        };
    }
}
