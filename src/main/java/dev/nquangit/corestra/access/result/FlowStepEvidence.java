package dev.nquangit.corestra.access.result;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Redacted evidence for one executed step in an Access flow target.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class FlowStepEvidence {
    private final String stepId;
    private final String stepName;
    private final boolean targetStep;
    private final String redactedRequestRaw;
    private final String redactedResponseRaw;
    private final int responseStatusCode;
    private final boolean success;
    private final String reason;
    private final long capturedAtEpochMillis;

    /**
     * Creates flow step evidence.
     *
     * @param stepId flow step id
     * @param stepName flow step name
     * @param targetStep whether this is the selected target step
     * @param redactedRequestRaw redacted request
     * @param redactedResponseRaw redacted response
     * @param responseStatusCode response status code, or 0 when not sent
     * @param success whether the step completed successfully
     * @param reason human-readable step reason
     * @param capturedAtEpochMillis capture timestamp
     */
    @JsonCreator
    public FlowStepEvidence(
            @JsonProperty("stepId") String stepId,
            @JsonProperty("stepName") String stepName,
            @JsonProperty("targetStep") boolean targetStep,
            @JsonProperty("redactedRequestRaw") String redactedRequestRaw,
            @JsonProperty("redactedResponseRaw") String redactedResponseRaw,
            @JsonProperty("responseStatusCode") int responseStatusCode,
            @JsonProperty("success") boolean success,
            @JsonProperty("reason") String reason,
            @JsonProperty("capturedAtEpochMillis") long capturedAtEpochMillis) {
        this.stepId = normalizeRequiredText(stepId, "stepId");
        this.stepName = normalizeRequiredText(stepName, "stepName");
        this.targetStep = targetStep;
        this.redactedRequestRaw = Objects.requireNonNullElse(redactedRequestRaw, "");
        this.redactedResponseRaw = Objects.requireNonNullElse(redactedResponseRaw, "");
        this.responseStatusCode = responseStatusCode;
        this.success = success;
        this.reason = Objects.requireNonNullElse(reason, "");
        this.capturedAtEpochMillis = capturedAtEpochMillis;
    }

    /**
     * Returns the flow step id.
     *
     * @return flow step id
     */
    public String stepId() {
        return stepId;
    }

    /**
     * Returns the flow step name.
     *
     * @return flow step name
     */
    public String stepName() {
        return stepName;
    }

    /**
     * Returns whether this evidence belongs to the selected target step.
     *
     * @return true for the selected target step
     */
    public boolean targetStep() {
        return targetStep;
    }

    /**
     * Returns the redacted request.
     *
     * @return redacted request
     */
    public String redactedRequestRaw() {
        return redactedRequestRaw;
    }

    /**
     * Returns the redacted response.
     *
     * @return redacted response
     */
    public String redactedResponseRaw() {
        return redactedResponseRaw;
    }

    /**
     * Returns the response status code.
     *
     * @return response status code, or 0 when no response exists
     */
    public int responseStatusCode() {
        return responseStatusCode;
    }

    /**
     * Returns whether the step completed successfully.
     *
     * @return true when successful
     */
    public boolean success() {
        return success;
    }

    /**
     * Returns the step reason.
     *
     * @return reason
     */
    public String reason() {
        return reason;
    }

    /**
     * Returns the capture timestamp.
     *
     * @return capture timestamp in epoch milliseconds
     */
    public long capturedAtEpochMillis() {
        return capturedAtEpochMillis;
    }

    /**
     * Compares this evidence entry to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FlowStepEvidence that)) {
            return false;
        }
        return targetStep == that.targetStep
                && responseStatusCode == that.responseStatusCode
                && success == that.success
                && capturedAtEpochMillis == that.capturedAtEpochMillis
                && Objects.equals(stepId, that.stepId)
                && Objects.equals(stepName, that.stepName)
                && Objects.equals(redactedRequestRaw, that.redactedRequestRaw)
                && Objects.equals(redactedResponseRaw, that.redactedResponseRaw)
                && Objects.equals(reason, that.reason);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(stepId, stepName, targetStep, redactedRequestRaw, redactedResponseRaw,
                responseStatusCode, success, reason, capturedAtEpochMillis);
    }

    /**
     * Returns a diagnostic string.
     *
     * @return diagnostic string
     */
    @Override
    public String toString() {
        return "FlowStepEvidence{" +
                "stepId='" + stepId + '\'' +
                ", stepName='" + stepName + '\'' +
                ", targetStep=" + targetStep +
                ", responseStatusCode=" + responseStatusCode +
                ", success=" + success +
                '}';
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
