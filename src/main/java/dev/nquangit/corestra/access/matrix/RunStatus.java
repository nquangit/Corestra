package dev.nquangit.corestra.access.matrix;

/**
 * Tracks the lifecycle state of an access test run.
 */
public enum RunStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
    INVALID
}
