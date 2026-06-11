package dev.nquangit.corestra.access.teardown;

/**
 * Defines whether an access test should run a cleanup flow after execution.
 */
public enum TeardownMode {
    /**
     * Do not run a teardown flow.
     */
    NONE,

    /**
     * Run a configured flow as teardown.
     */
    RUN_FLOW
}
