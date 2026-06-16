package dev.nquangit.corestra.access.flow;

/**
 * Defines how an Access Testing flow target executes around its selected step.
 */
public enum FlowTargetRunMode {
    /**
     * Executes enabled flow steps from the beginning through the selected target step.
     */
    STOP_AT_STEP,

    /**
     * Executes the full enabled flow while evaluating the selected target step response.
     */
    FULL_FLOW_EVALUATE_STEP
}
