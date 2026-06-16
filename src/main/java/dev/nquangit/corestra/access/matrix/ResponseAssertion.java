package dev.nquangit.corestra.access.matrix;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Describes an assertion to evaluate against an access-test response in a later PR.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ResponseAssertion {
    private final String id;
    private final String name;
    private final AssertionType type;
    private final String expression;
    private final String expectedValue;
    private final boolean enabled;

    /**
     * Creates a response assertion.
     *
     * @param id stable assertion identifier
     * @param name display name
     * @param type assertion type
     * @param expression selector or expression used by the assertion
     * @param expectedValue expected value used by the assertion
     * @param enabled whether this assertion is enabled
     */
    @JsonCreator
    public ResponseAssertion(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("type") AssertionType type,
            @JsonProperty("expression") String expression,
            @JsonProperty("expectedValue") String expectedValue,
            @JsonProperty("enabled") boolean enabled) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.type = Objects.requireNonNullElse(type, AssertionType.CUSTOM);
        this.expression = normalizeOptionalText(expression);
        this.expectedValue = normalizeOptionalText(expectedValue);
        this.enabled = enabled;
    }

    /**
     * Returns the stable assertion identifier.
     *
     * @return stable assertion identifier
     */
    public String id() {
        return id;
    }

    /**
     * Returns the display name.
     *
     * @return display name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the assertion type.
     *
     * @return assertion type
     */
    public AssertionType type() {
        return type;
    }

    /**
     * Returns the selector or expression used by this assertion.
     *
     * @return expression, or an empty string
     */
    public String expression() {
        return expression;
    }

    /**
     * Returns the expected value used by this assertion.
     *
     * @return expected value, or an empty string
     */
    public String expectedValue() {
        return expectedValue;
    }

    /**
     * Returns whether this assertion is enabled.
     *
     * @return whether this assertion is enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Compares this assertion to another object.
     *
     * @param other the candidate object
     * @return true when the objects are equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ResponseAssertion that)) {
            return false;
        }
        return enabled == that.enabled
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && type == that.type
                && Objects.equals(expression, that.expression)
                && Objects.equals(expectedValue, that.expectedValue);
    }

    /**
     * Returns the hash code for this assertion.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, type, expression, expectedValue, enabled);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "ResponseAssertion{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", type=" + type +
                ", expression='" + expression + '\'' +
                ", expectedValue='" + expectedValue + '\'' +
                ", enabled=" + enabled +
                '}';
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
