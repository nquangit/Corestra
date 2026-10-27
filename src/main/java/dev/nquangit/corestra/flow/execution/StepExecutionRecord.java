package dev.nquangit.corestra.flow.execution;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Records the outcome of a single step execution attempt.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class StepExecutionRecord {
    private final String stepId;
    private final String renderedRequest;
    private final String receivedResponse;
    private final int responseStatusCode;
    private final boolean success;
    private final String errorMessage;
    private final long startedAtEpochMillis;
    private final long completedAtEpochMillis;
    private final Map<String, String> extractedVariables;
    private final ExecutionFailureType failureType;

    /**
     * Creates a step execution record.
     *
     * @param stepId the executed step identifier
     * @param renderedRequest the final rendered request sent for the step
     * @param receivedResponse the raw response received for the step
     * @param responseStatusCode the received response status code
     * @param success whether the step execution succeeded
     * @param errorMessage the optional error message when the execution failed
     * @param startedAtEpochMillis the step start timestamp in epoch milliseconds
     * @param completedAtEpochMillis the step completion timestamp in epoch milliseconds
     * @param extractedVariables the variables extracted during the step execution
     */
    public StepExecutionRecord(
            @JsonProperty("stepId") String stepId,
            @JsonProperty("renderedRequest") String renderedRequest,
            @JsonProperty("receivedResponse") String receivedResponse,
            @JsonProperty("responseStatusCode") int responseStatusCode,
            @JsonProperty("success") boolean success,
            @JsonProperty("errorMessage") String errorMessage,
            @JsonProperty("startedAtEpochMillis") long startedAtEpochMillis,
            @JsonProperty("completedAtEpochMillis") long completedAtEpochMillis,
            @JsonProperty("extractedVariables") Map<String, String> extractedVariables) {
        this(stepId, renderedRequest, receivedResponse, responseStatusCode, success, errorMessage,
                startedAtEpochMillis, completedAtEpochMillis, extractedVariables, null);
    }

    /** Restores a step outcome; old records without a category remain UNKNOWN on failure. */
    @JsonCreator
    public StepExecutionRecord(
            @JsonProperty("stepId") String stepId,
            @JsonProperty("renderedRequest") String renderedRequest,
            @JsonProperty("receivedResponse") String receivedResponse,
            @JsonProperty("responseStatusCode") int responseStatusCode,
            @JsonProperty("success") boolean success,
            @JsonProperty("errorMessage") String errorMessage,
            @JsonProperty("startedAtEpochMillis") long startedAtEpochMillis,
            @JsonProperty("completedAtEpochMillis") long completedAtEpochMillis,
            @JsonProperty("extractedVariables") Map<String, String> extractedVariables,
            @JsonProperty("failureType") ExecutionFailureType failureType) {
        this.stepId = normalizeRequiredText(stepId, "stepId");
        this.renderedRequest = renderedRequest == null ? "" : renderedRequest;
        this.receivedResponse = receivedResponse == null ? "" : receivedResponse;
        this.responseStatusCode = responseStatusCode;
        this.success = success;
        this.errorMessage = errorMessage == null ? "" : errorMessage;
        this.startedAtEpochMillis = validateEpochMillis(startedAtEpochMillis, "startedAtEpochMillis");
        this.completedAtEpochMillis = validateCompletedMillis(completedAtEpochMillis, this.startedAtEpochMillis);
        this.extractedVariables = copyStringMap(extractedVariables);
        this.failureType = success ? ExecutionFailureType.NONE
                : failureType == null || failureType == ExecutionFailureType.NONE ? ExecutionFailureType.UNKNOWN : failureType;
    }

    /** Returns the execution failure category; HTTP 403/429 alone are NONE. */
    public ExecutionFailureType failureType() { return failureType; }

    /**
     * Returns the executed step identifier.
     *
     * @return the executed step identifier
     */
    public String stepId() {
        return stepId;
    }

    /**
     * Returns the rendered request sent for the step.
     *
     * @return the rendered request sent for the step
     */
    public String renderedRequest() {
        return renderedRequest;
    }

    /**
     * Returns the raw response received for the step.
     *
     * @return the raw response received for the step
     */
    public String receivedResponse() {
        return receivedResponse;
    }

    /**
     * Returns the response status code.
     *
     * @return the response status code
     */
    public int responseStatusCode() {
        return responseStatusCode;
    }

    /**
     * Returns whether the step execution succeeded.
     *
     * @return whether the step execution succeeded
     */
    public boolean success() {
        return success;
    }

    /**
     * Returns the optional error message.
     *
     * @return the optional error message, or an empty string when none is recorded
     */
    public String errorMessage() {
        return errorMessage;
    }

    /**
     * Returns the step start timestamp.
     *
     * @return the step start timestamp in epoch milliseconds
     */
    public long startedAtEpochMillis() {
        return startedAtEpochMillis;
    }

    /**
     * Returns the step completion timestamp.
     *
     * @return the step completion timestamp in epoch milliseconds
     */
    public long completedAtEpochMillis() {
        return completedAtEpochMillis;
    }

    /**
     * Returns the variables extracted during the step execution.
     *
     * @return the variables extracted during the step execution
     */
    public Map<String, String> extractedVariables() {
        return extractedVariables;
    }

    /**
     * Compares this step execution record to another object.
     *
     * @param other the candidate object
     * @return {@code true} when the objects are equal, otherwise {@code false}
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StepExecutionRecord that)) {
            return false;
        }
        return responseStatusCode == that.responseStatusCode
                && success == that.success
                && startedAtEpochMillis == that.startedAtEpochMillis
                && completedAtEpochMillis == that.completedAtEpochMillis
                && Objects.equals(stepId, that.stepId)
                && Objects.equals(renderedRequest, that.renderedRequest)
                && Objects.equals(receivedResponse, that.receivedResponse)
                && Objects.equals(errorMessage, that.errorMessage)
                && failureType == that.failureType
                && Objects.equals(extractedVariables, that.extractedVariables);
    }

    /**
     * Returns the hash code for this step execution record.
     *
     * @return the hash code for this step execution record
     */
    @Override
    public int hashCode() {
        return Objects.hash(stepId, renderedRequest, receivedResponse, responseStatusCode, success, errorMessage, startedAtEpochMillis, completedAtEpochMillis, extractedVariables, failureType);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "StepExecutionRecord{" +
                "stepId='" + stepId + '\'' +
                ", responseStatusCode=" + responseStatusCode +
                ", success=" + success +
                ", errorMessage='" + errorMessage + '\'' +
                ", startedAtEpochMillis=" + startedAtEpochMillis +
                ", completedAtEpochMillis=" + completedAtEpochMillis +
                ", extractedVariables=" + extractedVariables +
                '}';
    }

    private static Map<String, String> copyStringMap(Map<String, String> values) {
        if (values == null) {
            return Map.of();
        }

        Map<String, String> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> copied.put(
                normalizeRequiredText(key, "extractedVariables key"),
                value == null ? "" : value));
        return Map.copyOf(copied);
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
