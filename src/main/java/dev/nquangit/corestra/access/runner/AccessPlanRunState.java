package dev.nquangit.corestra.access.runner;

/**
 * Lifecycle state for a sequential access plan run.
 */
public enum AccessPlanRunState {
    IDLE,
    RUNNING,
    STOPPING,
    FINISHED
}
