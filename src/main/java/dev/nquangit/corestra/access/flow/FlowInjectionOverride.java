package dev.nquangit.corestra.access.flow;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Overrides a Flowise inject rule for one Access Testing flow-target run.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class FlowInjectionOverride {
    private final String id;
    private final String name;
    private final boolean enabled;
    private final FlowInjectionOverrideTarget targetType;
    private final String targetKey;
    private final String valueTemplate;

    /**
     * Creates an injection override.
     *
     * @param id stable override identifier
     * @param name display name
     * @param enabled whether the override is enabled
     * @param targetType matching strategy
     * @param targetKey rule id or value source key to match
     * @param valueTemplate static value or scoped template to inject
     */
    @JsonCreator
    public FlowInjectionOverride(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("targetType") FlowInjectionOverrideTarget targetType,
            @JsonProperty("targetKey") String targetKey,
            @JsonProperty("valueTemplate") String valueTemplate) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.enabled = enabled;
        this.targetType = Objects.requireNonNullElse(targetType, FlowInjectionOverrideTarget.VALUE_SOURCE_KEY);
        this.targetKey = normalizeRequiredText(targetKey, "targetKey");
        this.valueTemplate = Objects.requireNonNullElse(valueTemplate, "");
    }

    /**
     * Returns the stable override identifier.
     *
     * @return stable override identifier
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
     * Returns whether the override is enabled.
     *
     * @return true when enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the matching strategy.
     *
     * @return matching strategy
     */
    public FlowInjectionOverrideTarget targetType() {
        return targetType;
    }

    /**
     * Returns the rule id or value source key to match.
     *
     * @return target key
     */
    public String targetKey() {
        return targetKey;
    }

    /**
     * Returns the value template to inject.
     *
     * @return static or scoped-template value
     */
    public String valueTemplate() {
        return valueTemplate;
    }

    /**
     * Compares this override to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FlowInjectionOverride that)) {
            return false;
        }
        return enabled == that.enabled
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && targetType == that.targetType
                && Objects.equals(targetKey, that.targetKey)
                && Objects.equals(valueTemplate, that.valueTemplate);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, enabled, targetType, targetKey, valueTemplate);
    }

    /**
     * Returns a diagnostic string.
     *
     * @return diagnostic string
     */
    @Override
    public String toString() {
        return "FlowInjectionOverride{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", enabled=" + enabled +
                ", targetType=" + targetType +
                ", targetKey='" + targetKey + '\'' +
                '}';
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
