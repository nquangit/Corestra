package dev.nquangit.corestra.flow.control;

/**
 * Records the outcome of executing a configured step action.
 */
public enum StepActionStatus {
    /**
     * The action was ignored because it was disabled or not applicable.
     */
    SKIPPED,

    /**
     * The action completed successfully.
     */
    COMPLETED,

    /**
     * The action failed because of invalid configuration or runtime failure.
     */
    FAILED
}
