package dev.nquangit.corestra.access.auth;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Describes one configured AuthProvider failure rule.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class AuthFailureRule {
    private final String id;
    private final String name;
    private final boolean enabled;
    private final AuthRuleType type;
    private final String expression;
    private final String expectedValue;

    /**
     * Creates an auth failure rule.
     *
     * @param id stable rule identifier
     * @param name display name
     * @param enabled whether this rule is enabled
     * @param type rule type
     * @param expression selector, status code, or expression
     * @param expectedValue expected value
     */
    @JsonCreator
    public AuthFailureRule(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("type") AuthRuleType type,
            @JsonProperty("expression") String expression,
            @JsonProperty("expectedValue") String expectedValue) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.enabled = enabled;
        this.type = Objects.requireNonNullElse(type, AuthRuleType.CUSTOM);
        this.expression = normalizeOptionalText(expression);
        this.expectedValue = normalizeOptionalText(expectedValue);
    }

    /**
     * Returns the stable rule identifier.
     *
     * @return stable rule identifier
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
     * Returns whether this rule is enabled.
     *
     * @return whether this rule is enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the rule type.
     *
     * @return rule type
     */
    public AuthRuleType type() {
        return type;
    }

    /**
     * Returns the selector, status code, or expression.
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
     * Compares this rule to another object.
     *
     * @param other the candidate object
     * @return true when the objects are equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AuthFailureRule that)) {
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
     * Returns the hash code for this rule.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, enabled, type, expression, expectedValue);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "AuthFailureRule{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", enabled=" + enabled +
                ", type=" + type +
                ", expression='" + expression + '\'' +
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
