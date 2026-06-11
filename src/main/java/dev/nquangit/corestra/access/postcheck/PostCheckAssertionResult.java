package dev.nquangit.corestra.access.postcheck;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Captures the outcome of one post-check assertion.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class PostCheckAssertionResult {
    private final String assertionId;
    private final String name;
    private final boolean passed;
    private final String reason;

    /**
     * Creates a post-check assertion result.
     *
     * @param assertionId source assertion id
     * @param name assertion display name
     * @param passed whether the assertion passed
     * @param reason evaluation reason
     */
    @JsonCreator
    public PostCheckAssertionResult(
            @JsonProperty("assertionId") String assertionId,
            @JsonProperty("name") String name,
            @JsonProperty("passed") boolean passed,
            @JsonProperty("reason") String reason) {
        this.assertionId = normalizeRequiredText(assertionId, "assertionId");
        this.name = normalizeRequiredText(name, "name");
        this.passed = passed;
        this.reason = normalizeOptionalText(reason);
    }

    /**
     * Returns the source assertion id.
     *
     * @return assertion id
     */
    public String assertionId() {
        return assertionId;
    }

    /**
     * Returns the assertion display name.
     *
     * @return display name
     */
    public String name() {
        return name;
    }

    /**
     * Returns whether the assertion passed.
     *
     * @return true when passed
     */
    public boolean passed() {
        return passed;
    }

    /**
     * Returns the evaluation reason.
     *
     * @return reason, or an empty string
     */
    public String reason() {
        return reason;
    }

    /**
     * Compares this result to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PostCheckAssertionResult that)) {
            return false;
        }
        return passed == that.passed
                && Objects.equals(assertionId, that.assertionId)
                && Objects.equals(name, that.name)
                && Objects.equals(reason, that.reason);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(assertionId, name, passed, reason);
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
