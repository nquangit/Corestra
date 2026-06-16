package dev.nquangit.corestra.flow.control;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Describes one boolean condition evaluated after a flow step attempt.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class StepCondition {
    private final String id;
    private final String name;
    private final boolean enabled;
    private final StepConditionSource source;
    private final String selector;
    private final StepConditionOperator operator;
    private final ConditionValueSource expectedValueSource;
    private final String expectedValue;
    private final boolean caseSensitive;
    private final boolean negate;

    /**
     * Creates a step condition.
     *
     * @param id stable condition identifier
     * @param name display name
     * @param enabled whether the condition is enabled
     * @param source value source to inspect
     * @param selector source-specific selector
     * @param operator comparison operator
     * @param expectedValueSource expected-value source
     * @param expectedValue expected value or variable name
     * @param caseSensitive whether text comparison is case-sensitive
     * @param negate whether to invert the final condition result
     */
    @JsonCreator
    public StepCondition(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("source") StepConditionSource source,
            @JsonProperty("selector") String selector,
            @JsonProperty("operator") StepConditionOperator operator,
            @JsonProperty("expectedValueSource") ConditionValueSource expectedValueSource,
            @JsonProperty("expectedValue") String expectedValue,
            @JsonProperty("caseSensitive") boolean caseSensitive,
            @JsonProperty("negate") boolean negate) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.enabled = enabled;
        this.source = Objects.requireNonNullElse(source, StepConditionSource.RESPONSE_STATUS);
        this.selector = normalizeOptionalText(selector);
        this.operator = Objects.requireNonNullElse(operator, StepConditionOperator.EQUALS);
        this.expectedValueSource = Objects.requireNonNullElse(expectedValueSource, ConditionValueSource.STATIC);
        this.expectedValue = normalizeOptionalText(expectedValue);
        this.caseSensitive = caseSensitive;
        this.negate = negate;
    }

    /**
     * Returns the stable condition identifier.
     *
     * @return the stable condition identifier
     */
    public String id() {
        return id;
    }

    /**
     * Returns the display name.
     *
     * @return the display name
     */
    public String name() {
        return name;
    }

    /**
     * Returns whether this condition is enabled.
     *
     * @return whether this condition is enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the inspected value source.
     *
     * @return the inspected value source
     */
    public StepConditionSource source() {
        return source;
    }

    /**
     * Returns the source-specific selector.
     *
     * @return the source-specific selector, or an empty string
     */
    public String selector() {
        return selector;
    }

    /**
     * Returns the comparison operator.
     *
     * @return the comparison operator
     */
    public StepConditionOperator operator() {
        return operator;
    }

    /**
     * Returns where the expected value comes from.
     *
     * @return the expected-value source
     */
    public ConditionValueSource expectedValueSource() {
        return expectedValueSource;
    }

    /**
     * Returns the expected value or expected variable key.
     *
     * @return the expected value
     */
    public String expectedValue() {
        return expectedValue;
    }

    /**
     * Returns whether text comparisons are case-sensitive.
     *
     * @return whether text comparisons are case-sensitive
     */
    public boolean caseSensitive() {
        return caseSensitive;
    }

    /**
     * Returns whether to invert the final result.
     *
     * @return whether to invert the final result
     */
    public boolean negate() {
        return negate;
    }

    /**
     * Compares this condition to another object.
     *
     * @param other the candidate object
     * @return {@code true} when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StepCondition that)) {
            return false;
        }
        return enabled == that.enabled
                && caseSensitive == that.caseSensitive
                && negate == that.negate
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && source == that.source
                && Objects.equals(selector, that.selector)
                && operator == that.operator
                && expectedValueSource == that.expectedValueSource
                && Objects.equals(expectedValue, that.expectedValue);
    }

    /**
     * Returns the hash code for this condition.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, enabled, source, selector, operator, expectedValueSource, expectedValue, caseSensitive, negate);
    }

    /**
     * Returns a diagnostic string.
     *
     * @return a diagnostic string
     */
    @Override
    public String toString() {
        return "StepCondition{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", enabled=" + enabled +
                ", source=" + source +
                ", selector='" + selector + '\'' +
                ", operator=" + operator +
                ", expectedValueSource=" + expectedValueSource +
                ", expectedValue='" + expectedValue + '\'' +
                ", caseSensitive=" + caseSensitive +
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
