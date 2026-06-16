package dev.nquangit.corestra.flow.control;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Records the result of one post-step action execution.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class StepActionExecutionRecord {
    private final String flowId;
    private final String stepId;
    private final String ruleId;
    private final String actionId;
    private final StepActionType actionType;
    private final StepActionStatus status;
    private final String message;
    private final long startedAtEpochMillis;
    private final long completedAtEpochMillis;

    /**
     * Creates an action execution record.
     *
     * @param flowId flow identifier
     * @param stepId step identifier
     * @param ruleId condition/action rule identifier
     * @param actionId action identifier
     * @param actionType action type
     * @param status action status
     * @param message diagnostic message
     * @param startedAtEpochMillis action start timestamp
     * @param completedAtEpochMillis action completion timestamp
     */
    @JsonCreator
    public StepActionExecutionRecord(
            @JsonProperty("flowId") String flowId,
            @JsonProperty("stepId") String stepId,
            @JsonProperty("ruleId") String ruleId,
            @JsonProperty("actionId") String actionId,
            @JsonProperty("actionType") StepActionType actionType,
            @JsonProperty("status") StepActionStatus status,
            @JsonProperty("message") String message,
            @JsonProperty("startedAtEpochMillis") long startedAtEpochMillis,
            @JsonProperty("completedAtEpochMillis") long completedAtEpochMillis) {
        this.flowId = normalizeRequiredText(flowId, "flowId");
        this.stepId = normalizeRequiredText(stepId, "stepId");
        this.ruleId = normalizeRequiredText(ruleId, "ruleId");
        this.actionId = normalizeRequiredText(actionId, "actionId");
        this.actionType = Objects.requireNonNull(actionType, "actionType");
        this.status = Objects.requireNonNull(status, "status");
        this.message = message == null ? "" : message;
        this.startedAtEpochMillis = validateEpochMillis(startedAtEpochMillis, "startedAtEpochMillis");
        this.completedAtEpochMillis = validateCompletedMillis(completedAtEpochMillis, this.startedAtEpochMillis);
    }

    /**
     * Returns the flow identifier.
     *
     * @return the flow identifier
     */
    public String flowId() {
        return flowId;
    }

    /**
     * Returns the step identifier.
     *
     * @return the step identifier
     */
    public String stepId() {
        return stepId;
    }

    /**
     * Returns the condition/action rule identifier.
     *
     * @return the condition/action rule identifier
     */
    public String ruleId() {
        return ruleId;
    }

    /**
     * Returns the action identifier.
     *
     * @return the action identifier
     */
    public String actionId() {
        return actionId;
    }

    /**
     * Returns the action type.
     *
     * @return the action type
     */
    public StepActionType actionType() {
        return actionType;
    }

    /**
     * Returns the action status.
     *
     * @return the action status
     */
    public StepActionStatus status() {
        return status;
    }

    /**
     * Returns the diagnostic message.
     *
     * @return the diagnostic message
     */
    public String message() {
        return message;
    }

    /**
     * Returns the action start timestamp.
     *
     * @return the action start timestamp
     */
    public long startedAtEpochMillis() {
        return startedAtEpochMillis;
    }

    /**
     * Returns the action completion timestamp.
     *
     * @return the action completion timestamp
     */
    public long completedAtEpochMillis() {
        return completedAtEpochMillis;
    }

    /**
     * Compares this record to another object.
     *
     * @param other the candidate object
     * @return {@code true} when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StepActionExecutionRecord that)) {
            return false;
        }
        return startedAtEpochMillis == that.startedAtEpochMillis
                && completedAtEpochMillis == that.completedAtEpochMillis
                && Objects.equals(flowId, that.flowId)
                && Objects.equals(stepId, that.stepId)
                && Objects.equals(ruleId, that.ruleId)
                && Objects.equals(actionId, that.actionId)
                && actionType == that.actionType
                && status == that.status
                && Objects.equals(message, that.message);
    }

    /**
     * Returns the hash code.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(flowId, stepId, ruleId, actionId, actionType, status, message, startedAtEpochMillis, completedAtEpochMillis);
    }

    /**
     * Returns a diagnostic string.
     *
     * @return a diagnostic string
     */
    @Override
    public String toString() {
        return "StepActionExecutionRecord{" +
                "flowId='" + flowId + '\'' +
                ", stepId='" + stepId + '\'' +
                ", ruleId='" + ruleId + '\'' +
                ", actionId='" + actionId + '\'' +
                ", actionType=" + actionType +
                ", status=" + status +
                ", message='" + message + '\'' +
                ", startedAtEpochMillis=" + startedAtEpochMillis +
                ", completedAtEpochMillis=" + completedAtEpochMillis +
                '}';
    }

    private static long validateEpochMillis(long value, String fieldName) {
        if (value < 0L) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
        return value;
    }

    private static long validateCompletedMillis(long completedAtEpochMillis, long startedAtEpochMillis) {
        if (completedAtEpochMillis < startedAtEpochMillis) {
            throw new IllegalArgumentException("completedAtEpochMillis must be greater than or equal to startedAtEpochMillis");
        }
        return completedAtEpochMillis;
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
