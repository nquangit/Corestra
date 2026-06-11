package dev.nquangit.corestra.access.matrix;

/**
 * Defines how prerequisite setup should be obtained before an access test runs.
 */
public enum SetupMode {
    NONE,
    RUN_PREFIX,
    RUN_SETUP_FLOW,
    REUSE_SCENARIO
}
