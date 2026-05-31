package dev.nquangit.corestra.flow.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents the final outcome of running a complete flow.
 *
 * <p>Use {@link #success(String, String, List)} when all steps completed without a fatal
 * failure, or {@link #failure(String, String, List, String)} when the flow was stopped
 * early due to a fatal step error.
 *
 * <p>Instances are immutable.
 */
public final class ExecutionResult {

    private final String flowId;
    private final String flowName;
    private final boolean success;
    private final List<StepExecutionRecord> stepRecords;
    private final String failureMessage;

    private ExecutionResult(
            String flowId,
            String flowName,
            boolean success,
            List<StepExecutionRecord> stepRecords,
            String failureMessage) {
        this.flowId = Objects.requireNonNull(flowId, "flowId");
        this.flowName = Objects.requireNonNull(flowName, "flowName");
        this.success = success;
        this.stepRecords = List.copyOf(Objects.requireNonNull(stepRecords, "stepRecords"));
        this.failureMessage = failureMessage;
    }

    /**
     * Creates a successful execution result.
     *
     * @param flowId      the flow definition identifier
     * @param flowName    the flow display name
     * @param stepRecords the ordered list of step execution records collected during the run
     * @return a successful {@code ExecutionResult}
     */
    public static ExecutionResult success(String flowId, String flowName, List<StepExecutionRecord> stepRecords) {
        return new ExecutionResult(flowId, flowName, true, stepRecords, null);
    }

    /**
     * Creates a failed execution result.
     *
     * @param flowId         the flow definition identifier
     * @param flowName       the flow display name
     * @param stepRecords    the ordered list of step execution records collected before the failure
     * @param failureMessage a human-readable description of why the flow failed
     * @return a failed {@code ExecutionResult}
     */
    public static ExecutionResult failure(
            String flowId,
            String flowName,
            List<StepExecutionRecord> stepRecords,
            String failureMessage) {
        return new ExecutionResult(flowId, flowName, false, stepRecords, failureMessage);
    }

    /**
     * Returns the flow definition identifier.
     *
     * @return the flow definition identifier
     */
    public String flowId() {
        return flowId;
    }

    /**
     * Returns the flow display name.
     *
     * @return the flow display name
     */
    public String flowName() {
        return flowName;
    }

    /**
     * Returns whether the flow execution succeeded.
     *
     * @return {@code true} when all enabled steps ran without a fatal failure
     */
    public boolean success() {
        return success;
    }

    /**
     * Returns the ordered list of step execution records.
     *
     * @return the step execution records as an unmodifiable list
     */
    public List<StepExecutionRecord> stepRecords() {
        return stepRecords;
    }

    /**
     * Returns the failure message, or {@code null} when the flow succeeded.
     *
     * @return the failure message, or {@code null}
     */
    public String failureMessage() {
        return failureMessage;
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "ExecutionResult{" +
                "flowId='" + flowId + '\'' +
                ", flowName='" + flowName + '\'' +
                ", success=" + success +
                ", stepRecords=" + stepRecords +
                ", failureMessage='" + failureMessage + '\'' +
                '}';
    }

    /**
     * Compares this result to another object.
     *
     * @param other the candidate object
     * @return {@code true} when the objects are equal, otherwise {@code false}
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExecutionResult that)) {
            return false;
        }
        return success == that.success
                && Objects.equals(flowId, that.flowId)
                && Objects.equals(flowName, that.flowName)
                && Objects.equals(stepRecords, that.stepRecords)
                && Objects.equals(failureMessage, that.failureMessage);
    }

    /**
     * Returns the hash code for this result.
     *
     * @return the hash code for this result
     */
    @Override
    public int hashCode() {
        return Objects.hash(flowId, flowName, success, stepRecords, failureMessage);
    }
}
