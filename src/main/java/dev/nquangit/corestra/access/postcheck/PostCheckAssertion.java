package dev.nquangit.corestra.access.postcheck;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Describes one assertion to evaluate against a post-check response.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class PostCheckAssertion {
    private final String id;
    private final String name;
    private final boolean enabled;
    private final PostCheckAssertionType type;
    private final String expression;
    private final String expectedValue;
    private final boolean negate;

    /**
     * Creates a post-check assertion.
     *
     * @param id stable assertion id
     * @param name display name
     * @param enabled whether the assertion is enabled
     * @param type assertion type
     * @param expression selector or expression used by the assertion
     * @param expectedValue expected value used by the assertion
     * @param negate whether to invert the assertion result
     */
    @JsonCreator
    public PostCheckAssertion(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("type") PostCheckAssertionType type,
            @JsonProperty("expression") String expression,
            @JsonProperty("expectedValue") String expectedValue,
            @JsonProperty("negate") boolean negate) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.enabled = enabled;
        this.type = Objects.requireNonNullElse(type, PostCheckAssertionType.STATUS_CODE);
        this.expression = normalizeOptionalText(expression);
        this.expectedValue = normalizeOptionalText(expectedValue);
        this.negate = negate;
    }

    /**
     * Returns the stable assertion id.
     *
     * @return assertion id
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
     * Returns whether this assertion is enabled.
     *
     * @return true when enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the assertion type.
     *
     * @return assertion type
     */
    public PostCheckAssertionType type() {
        return type;
    }

    /**
     * Returns the assertion expression.
     *
     * @return expression, or an empty string
     */
    public String expression() {
        return expression;
    }

    /**
     * Returns the expected value.
     *
     * @return expected value, or an empty string
     */
    public String expectedValue() {
        return expectedValue;
    }

    /**
     * Returns whether this assertion result should be inverted.
     *
     * @return true when negated
     */
    public boolean negate() {
        return negate;
    }

    /**
     * Compares this assertion to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PostCheckAssertion that)) {
            return false;
        }
        return enabled == that.enabled
                && negate == that.negate
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && type == that.type
                && Objects.equals(expression, that.expression)
                && Objects.equals(expectedValue, that.expectedValue);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, enabled, type, expression, expectedValue, negate);
    }

    /**
     * Returns a diagnostic string.
     *
     * @return diagnostic string
     */
    @Override
    public String toString() {
        return "PostCheckAssertion{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", enabled=" + enabled +
                ", type=" + type +
                ", expression='" + expression + '\'' +
                ", expectedValue='" + expectedValue + '\'' +
                ", negate=" + negate +
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
