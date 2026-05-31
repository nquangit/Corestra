package dev.nquangit.corestra.http.extract;

import java.util.Objects;

/**
 * Represents the outcome of a single extraction attempt.
 *
 * <p>Use {@link #success(String, String)} and {@link #failure(String, String)} to construct
 * instances, then call {@link #isSuccess()} to distinguish between outcomes.
 */
public final class ExtractionResult {

    private final String targetVariable;
    private final String value;
    private final String failureReason;
    private final boolean success;

    private ExtractionResult(String targetVariable, String value, String failureReason, boolean success) {
        this.targetVariable = Objects.requireNonNull(targetVariable, "targetVariable");
        this.value = value;
        this.failureReason = failureReason;
        this.success = success;
    }

    /**
     * Creates a successful extraction result carrying an extracted value.
     *
     * @param targetVariable the name of the variable the value will be stored in
     * @param value          the extracted string value
     * @return a successful {@code ExtractionResult}
     */
    public static ExtractionResult success(String targetVariable, String value) {
        Objects.requireNonNull(value, "value");
        return new ExtractionResult(targetVariable, value, null, true);
    }

    /**
     * Creates a failed extraction result carrying a human-readable reason.
     *
     * @param targetVariable the name of the variable that could not be populated
     * @param reason         a human-readable description of why extraction failed
     * @return a failed {@code ExtractionResult}
     */
    public static ExtractionResult failure(String targetVariable, String reason) {
        Objects.requireNonNull(reason, "reason");
        return new ExtractionResult(targetVariable, null, reason, false);
    }

    /**
     * Returns {@code true} when extraction succeeded.
     *
     * @return {@code true} on success, {@code false} on failure
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Returns the name of the target variable this result is associated with.
     *
     * @return the target variable name
     */
    public String targetVariable() {
        return targetVariable;
    }

    /**
     * Returns the extracted value, or {@code null} when extraction failed.
     *
     * @return the extracted value, or {@code null}
     */
    public String value() {
        return value;
    }

    /**
     * Returns the reason for failure, or {@code null} when extraction succeeded.
     *
     * @return the failure reason, or {@code null}
     */
    public String failureReason() {
        return failureReason;
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        if (success) {
            return "ExtractionResult{success, targetVariable='" + targetVariable + "', value='" + value + "'}";
        }
        return "ExtractionResult{failure, targetVariable='" + targetVariable + "', reason='" + failureReason + "'}";
    }
}
