package dev.nquangit.corestra.access.teardown;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Redacted evidence captured for one teardown flow run.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class TeardownEvidence {
    private final String flowId;
    private final String flowName;
    private final boolean success;
    private final String reason;
    private final List<TeardownStepEvidence> steps;
    private final long capturedAtEpochMillis;

    /**
     * Creates teardown evidence.
     *
     * @param flowId teardown flow id
     * @param flowName teardown flow name
     * @param success whether teardown succeeded
     * @param reason teardown reason
     * @param steps redacted step evidence
     * @param capturedAtEpochMillis capture timestamp
     */
    @JsonCreator
    public TeardownEvidence(
            @JsonProperty("flowId") String flowId,
            @JsonProperty("flowName") String flowName,
            @JsonProperty("success") boolean success,
            @JsonProperty("reason") String reason,
            @JsonProperty("steps") List<TeardownStepEvidence> steps,
            @JsonProperty("capturedAtEpochMillis") long capturedAtEpochMillis) {
        this.flowId = normalizeRequiredText(flowId, "flowId");
        this.flowName = normalizeOptionalText(flowName);
        this.success = success;
        this.reason = normalizeOptionalText(reason);
        this.steps = copySteps(steps);
        this.capturedAtEpochMillis = capturedAtEpochMillis;
    }

    /** @return teardown flow id */
    public String flowId() {
        return flowId;
    }

    /** @return teardown flow name */
    public String flowName() {
        return flowName;
    }

    /** @return whether teardown succeeded */
    public boolean success() {
        return success;
    }

    /** @return teardown reason */
    public String reason() {
        return reason;
    }

    /** @return redacted step evidence */
    public List<TeardownStepEvidence> steps() {
        return steps;
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
        if (!(other instanceof TeardownEvidence that)) {
            return false;
        }
        return success == that.success
                && capturedAtEpochMillis == that.capturedAtEpochMillis
                && Objects.equals(flowId, that.flowId)
                && Objects.equals(flowName, that.flowName)
                && Objects.equals(reason, that.reason)
                && Objects.equals(steps, that.steps);
    }

    /**
     * Returns hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(flowId, flowName, success, reason, steps, capturedAtEpochMillis);
    }

    private static List<TeardownStepEvidence> copySteps(List<TeardownStepEvidence> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        ArrayList<TeardownStepEvidence> copied = new ArrayList<>();
        values.forEach(value -> copied.add(Objects.requireNonNull(value, "teardown step evidence")));
        return List.copyOf(copied);
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }

    private static String normalizeOptionalText(String value) {
        return value == null ? "" : value.strip();
    }
}
