package dev.nquangit.corestra.access.teardown;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Redacted evidence for one teardown flow step.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class TeardownStepEvidence {
    private final String stepId;
    private final String redactedRequestRaw;
    private final String redactedResponseRaw;
    private final int responseStatusCode;
    private final boolean success;
    private final String reason;
    private final long capturedAtEpochMillis;

    /**
     * Creates step evidence.
     *
     * @param stepId executed step id
     * @param redactedRequestRaw redacted request
     * @param redactedResponseRaw redacted response
     * @param responseStatusCode response status code
     * @param success whether the step succeeded
     * @param reason step reason or failure message
     * @param capturedAtEpochMillis capture timestamp
     */
    @JsonCreator
    public TeardownStepEvidence(
            @JsonProperty("stepId") String stepId,
            @JsonProperty("redactedRequestRaw") String redactedRequestRaw,
            @JsonProperty("redactedResponseRaw") String redactedResponseRaw,
            @JsonProperty("responseStatusCode") int responseStatusCode,
            @JsonProperty("success") boolean success,
            @JsonProperty("reason") String reason,
            @JsonProperty("capturedAtEpochMillis") long capturedAtEpochMillis) {
        this.stepId = normalizeRequiredText(stepId, "stepId");
        this.redactedRequestRaw = Objects.requireNonNullElse(redactedRequestRaw, "");
        this.redactedResponseRaw = Objects.requireNonNullElse(redactedResponseRaw, "");
        this.responseStatusCode = responseStatusCode;
        this.success = success;
        this.reason = Objects.requireNonNullElse(reason, "");
        this.capturedAtEpochMillis = capturedAtEpochMillis;
    }

    /** @return executed step id */
    public String stepId() {
        return stepId;
    }

    /** @return redacted request */
    public String redactedRequestRaw() {
        return redactedRequestRaw;
    }

    /** @return redacted response */
    public String redactedResponseRaw() {
        return redactedResponseRaw;
    }

    /** @return response status code */
    public int responseStatusCode() {
        return responseStatusCode;
    }

    /** @return whether step succeeded */
    public boolean success() {
        return success;
    }

    /** @return step reason */
    public String reason() {
        return reason;
    }

    /** @return capture timestamp */
    public long capturedAtEpochMillis() {
        return capturedAtEpochMillis;
    }

    /**
     * Compares this evidence to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TeardownStepEvidence that)) {
            return false;
        }
        return responseStatusCode == that.responseStatusCode
                && success == that.success
                && capturedAtEpochMillis == that.capturedAtEpochMillis
                && Objects.equals(stepId, that.stepId)
                && Objects.equals(redactedRequestRaw, that.redactedRequestRaw)
                && Objects.equals(redactedResponseRaw, that.redactedResponseRaw)
                && Objects.equals(reason, that.reason);
    }

    /**
     * Returns hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(stepId, redactedRequestRaw, redactedResponseRaw,
                responseStatusCode, success, reason, capturedAtEpochMillis);
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
