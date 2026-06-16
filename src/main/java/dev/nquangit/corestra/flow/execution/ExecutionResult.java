package dev.nquangit.corestra.flow.execution;

import dev.nquangit.corestra.flow.control.StepActionExecutionRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
    private final List<StepActionExecutionRecord> actionRecords;
    private final String failureMessage;
    private final ExecutionContext finalContext;
    private final String completionReason;

    private ExecutionResult(
            String flowId,
            String flowName,
            boolean success,
            List<StepExecutionRecord> stepRecords,
            List<StepActionExecutionRecord> actionRecords,
            String failureMessage,
            ExecutionContext finalContext,
            String completionReason) {
        this.flowId = Objects.requireNonNull(flowId, "flowId");
        this.flowName = Objects.requireNonNull(flowName, "flowName");
        this.success = success;
        this.stepRecords = List.copyOf(Objects.requireNonNull(stepRecords, "stepRecords"));
        this.actionRecords = List.copyOf(Objects.requireNonNull(actionRecords, "actionRecords"));
        this.failureMessage = failureMessage;
        this.finalContext = finalContext;
        this.completionReason = completionReason == null ? "" : completionReason;
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
        return success(flowId, flowName, stepRecords, List.of(), null, "COMPLETED");
    }

    /**
     * Creates a successful execution result with action diagnostics and final context.
     *
     * @param flowId the flow definition identifier
     * @param flowName the flow display name
     * @param stepRecords ordered step execution records
     * @param actionRecords ordered action execution records
     * @param finalContext final execution context, or {@code null}
     * @param completionReason diagnostic completion reason
     * @return a successful {@code ExecutionResult}
     */
    public static ExecutionResult success(
            String flowId,
            String flowName,
            List<StepExecutionRecord> stepRecords,
            List<StepActionExecutionRecord> actionRecords,
            ExecutionContext finalContext,
            String completionReason) {
        return new ExecutionResult(flowId, flowName, true, stepRecords, actionRecords, null, finalContext, completionReason);
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
        return failure(flowId, flowName, stepRecords, List.of(), failureMessage, null);
    }

    /**
     * Creates a failed execution result with action diagnostics and final context.
     *
     * @param flowId the flow definition identifier
     * @param flowName the flow display name
     * @param stepRecords ordered step execution records
     * @param actionRecords ordered action execution records
     * @param failureMessage human-readable failure message
     * @param finalContext final execution context, or {@code null}
     * @return a failed {@code ExecutionResult}
     */
    public static ExecutionResult failure(
            String flowId,
            String flowName,
            List<StepExecutionRecord> stepRecords,
            List<StepActionExecutionRecord> actionRecords,
            String failureMessage,
            ExecutionContext finalContext) {
        return new ExecutionResult(flowId, flowName, false, stepRecords, actionRecords, failureMessage, finalContext, "FAILED");
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
     * Returns ordered action execution records.
     *
     * @return action execution records as an unmodifiable list
     */
    public List<StepActionExecutionRecord> actionRecords() {
        return actionRecords;
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
     * Returns the final execution context when available.
     *
     * @return the final execution context
     */
    public Optional<ExecutionContext> finalContext() {
        return Optional.ofNullable(finalContext);
    }

    /**
     * Returns a diagnostic completion reason.
     *
     * @return the completion reason, or an empty string
     */
    public String completionReason() {
        return completionReason;
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
                ", actionRecords=" + actionRecords +
                ", failureMessage='" + failureMessage + '\'' +
                ", completionReason='" + completionReason + '\'' +
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
                && Objects.equals(actionRecords, that.actionRecords)
                && Objects.equals(failureMessage, that.failureMessage)
                && Objects.equals(finalContext, that.finalContext)
                && Objects.equals(completionReason, that.completionReason);
    }

    /**
     * Returns the hash code for this result.
     *
     * @return the hash code for this result
     */
    @Override
    public int hashCode() {
        return Objects.hash(flowId, flowName, success, stepRecords, actionRecords, failureMessage, finalContext, completionReason);
    }
}
