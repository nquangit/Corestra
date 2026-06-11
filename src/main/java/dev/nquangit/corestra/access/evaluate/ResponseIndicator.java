package dev.nquangit.corestra.access.evaluate;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Defines one response condition used to identify business allow or deny outcomes.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ResponseIndicator {
    private final String id;
    private final String name;
    private final boolean enabled;
    private final IndicatorType type;
    private final String selector;
    private final String expectedValue;

    /**
     * Creates a response indicator.
     *
     * @param id stable indicator identifier
     * @param name display name
     * @param enabled whether this indicator is enabled
     * @param type indicator type
     * @param selector header name, JSON path, or status list depending on type
     * @param expectedValue value or substring expected by value-based indicators
     */
    @JsonCreator
    public ResponseIndicator(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("type") IndicatorType type,
            @JsonProperty("selector") String selector,
            @JsonProperty("expectedValue") String expectedValue) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.enabled = enabled;
        this.type = Objects.requireNonNull(type, "type");
        this.selector = normalizeOptionalText(selector);
        this.expectedValue = normalizeOptionalText(expectedValue);
    }

    /**
     * Returns the stable identifier.
     *
     * @return indicator id
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
     * Returns whether this indicator is enabled.
     *
     * @return true when enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the indicator type.
     *
     * @return indicator type
     */
    public IndicatorType type() {
        return type;
    }

    /**
     * Returns the selector for this indicator.
     *
     * @return selector, or an empty string
     */
    public String selector() {
        return selector;
    }

    /**
     * Returns the expected value for this indicator.
     *
     * @return expected value, or an empty string
     */
    public String expectedValue() {
        return expectedValue;
    }

    /**
     * Compares this indicator to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ResponseIndicator that)) {
            return false;
        }
        return enabled == that.enabled
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && type == that.type
                && Objects.equals(selector, that.selector)
                && Objects.equals(expectedValue, that.expectedValue);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, enabled, type, selector, expectedValue);
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
