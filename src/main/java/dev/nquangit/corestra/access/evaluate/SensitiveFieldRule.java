package dev.nquangit.corestra.access.evaluate;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Identifies a JSON response field that must not leak raw sensitive values.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class SensitiveFieldRule {
    private final String id;
    private final String name;
    private final boolean enabled;
    private final String jsonPath;

    /**
     * Creates a sensitive field rule.
     *
     * @param id stable rule identifier
     * @param name display name
     * @param enabled whether this rule is enabled
     * @param jsonPath dot-separated JSON path
     */
    @JsonCreator
    public SensitiveFieldRule(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("jsonPath") String jsonPath) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.enabled = enabled;
        this.jsonPath = normalizeRequiredText(jsonPath, "jsonPath");
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
        if (!(other instanceof SensitiveFieldRule that)) {
            return false;
        }
        return enabled == that.enabled
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && Objects.equals(jsonPath, that.jsonPath);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, enabled, jsonPath);
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
