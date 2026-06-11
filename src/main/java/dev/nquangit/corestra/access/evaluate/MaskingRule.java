package dev.nquangit.corestra.access.evaluate;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Defines an acceptable masked representation for a sensitive JSON response field.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class MaskingRule {
    private final String id;
    private final String name;
    private final boolean enabled;
    private final String jsonPath;
    private final String maskedValuePattern;

    /**
     * Creates a masking rule.
     *
     * @param id stable rule identifier
     * @param name display name
     * @param enabled whether this rule is enabled
     * @param jsonPath dot-separated JSON path
     * @param maskedValuePattern regex pattern accepted as masked
     */
    @JsonCreator
    public MaskingRule(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("jsonPath") String jsonPath,
            @JsonProperty("maskedValuePattern") String maskedValuePattern) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.enabled = enabled;
        this.jsonPath = normalizeRequiredText(jsonPath, "jsonPath");
        this.maskedValuePattern = normalizeRequiredText(maskedValuePattern, "maskedValuePattern");
    }

    /**
     * Returns the stable identifier.
     *
     * @return rule id
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
     * @return true when enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the dot-separated JSON path.
     *
     * @return JSON path
     */
    public String jsonPath() {
        return jsonPath;
    }

    /**
     * Returns the regex pattern accepted as masked.
     *
     * @return masked value pattern
     */
    public String maskedValuePattern() {
        return maskedValuePattern;
    }

    /**
     * Compares this rule to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MaskingRule that)) {
            return false;
        }
        return enabled == that.enabled
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && Objects.equals(jsonPath, that.jsonPath)
                && Objects.equals(maskedValuePattern, that.maskedValuePattern);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, enabled, jsonPath, maskedValuePattern);
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
