package dev.nquangit.corestra.flow.control;

/**
 * Enumerates control actions that can run after a flow step condition matches.
 */
public enum StepActionType {
    /**
     * Stops the current flow immediately and treats the stop as intentional.
     */
    BREAK_FLOW,

    /**
     * Sleeps for a configured number of seconds.
     */
    SLEEP,

    /**
     * Retries the current step from its stored request template.
     */
    RETRY_CURRENT_STEP,

    /**
     * Restarts the current flow from its first enabled step.
     */
    RETRY_FLOW_FROM_START,

    /**
     * Runs another persisted flow.
     */
    TRIGGER_FLOW
}
