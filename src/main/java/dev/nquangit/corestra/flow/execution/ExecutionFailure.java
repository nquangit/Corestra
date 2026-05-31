package dev.nquangit.corestra.flow.execution;

import java.util.Objects;

/**
 * Describes why a specific step or the engine itself failed during flow execution.
 *
 * <p>When {@link #stepId()} is {@code null} the failure originates from the engine itself
 * rather than from an individual step (e.g. an unexpected exception before any step ran).
 *
 * <p>Instances are immutable.
 */
public final class ExecutionFailure {

    private final String stepId;
    private final String reason;
    private final Throwable cause;

    /**
     * Creates an {@code ExecutionFailure}.
     *
     * @param stepId the identifier of the step that failed, or {@code null} for an engine-level failure
     * @param reason a human-readable description of why the failure occurred; must not be {@code null}
     * @param cause  the underlying throwable that caused the failure, or {@code null} if unavailable
     */
    public ExecutionFailure(String stepId, String reason, Throwable cause) {
        this.stepId = stepId;
        this.reason = Objects.requireNonNull(reason, "reason");
        this.cause = cause;
    }

    /**
     * Returns the identifier of the step that failed, or {@code null} for an engine-level failure.
     *
     * @return the step identifier, or {@code null}
     */
    public String stepId() {
        return stepId;
    }

    /**
     * Returns a human-readable description of why the failure occurred.
     *
     * @return the failure reason
     */
    public String reason() {
        return reason;
    }

    /**
     * Returns the underlying throwable that caused the failure, or {@code null} if unavailable.
     *
     * @return the cause, or {@code null}
     */
    public Throwable cause() {
        return cause;
    }

    /**
     * Returns {@code true} when the failure originated from the engine rather than a specific step.
     *
     * @return {@code true} for engine-level failures
     */
    public boolean isEngineLevelFailure() {
        return stepId == null;
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "ExecutionFailure{" +
                "stepId='" + stepId + '\'' +
                ", reason='" + reason + '\'' +
                ", cause=" + cause +
                '}';
    }

    /**
     * Compares this failure to another object.
     *
     * @param other the candidate object
     * @return {@code true} when the objects are equal, otherwise {@code false}
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExecutionFailure that)) {
            return false;
        }
        return Objects.equals(stepId, that.stepId)
                && Objects.equals(reason, that.reason)
                && Objects.equals(cause, that.cause);
    }

    /**
     * Returns the hash code for this failure.
     *
     * @return the hash code for this failure
     */
    @Override
    public int hashCode() {
        return Objects.hash(stepId, reason, cause);
    }
}
