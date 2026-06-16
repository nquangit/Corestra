package dev.nquangit.corestra.access.race;

/**
 * Lifecycle status for a race run.
 */
public enum RaceRunStatus {
    /**
     * Race run has not started.
     */
    PENDING,

    /**
     * Race run is currently executing.
     */
    RUNNING,

    /**
     * Race run finished without runner-level failure.
     */
    COMPLETED,

    /**
     * Race run was stopped before completion.
     */
    STOPPED,

    /**
     * Race run attempted sends but at least one worker failed.
     */
    FAILED,

    /**
     * Race run configuration or preparation was invalid and no partial race was released.
     */
    INVALID
}
