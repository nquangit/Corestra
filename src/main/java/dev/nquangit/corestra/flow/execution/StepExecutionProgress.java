package dev.nquangit.corestra.flow.execution;

/** Per-attempt execution metadata for an observer; contains no HTTP messages or extracted values. */
public record StepExecutionProgress(String runId, String flowId, String flowName, String stepId, String stepName,
                                    Phase phase, int attempt, int statusCode, String message) {
    /** Identifies preparation/execution start, normal completion or execution failure. */
    public enum Phase { STARTED, COMPLETED, FAILED }
}
