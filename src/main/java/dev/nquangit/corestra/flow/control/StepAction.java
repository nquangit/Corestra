package dev.nquangit.corestra.flow.control;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Describes one ordered action to run after a condition/action rule matches.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class StepAction {
    private final String id;
    private final String name;
    private final boolean enabled;
    private final StepActionType type;
    private final Map<String, String> parameters;

    /**
     * Creates a step action.
     *
     * @param id stable action identifier
     * @param name display name
     * @param enabled whether the action is enabled
     * @param type action type
     * @param parameters action parameters
     */
    @JsonCreator
    public StepAction(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("type") StepActionType type,
            @JsonProperty("parameters") Map<String, String> parameters) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.enabled = enabled;
        this.type = Objects.requireNonNullElse(type, StepActionType.BREAK_FLOW);
        this.parameters = copyStringMap(parameters);
    }

    /**
     * Returns the stable action identifier.
     *
     * @return the stable action identifier
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
     * Returns whether this action is enabled.
     *
     * @return whether this action is enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the action type.
     *
     * @return the action type
     */
    public StepActionType type() {
        return type;
    }

    /**
     * Returns the immutable action parameters.
     *
     * @return the immutable action parameters
     */
    public Map<String, String> parameters() {
        return parameters;
    }

    /**
     * Compares this action to another object.
     *
     * @param other the candidate object
     * @return {@code true} when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StepAction that)) {
            return false;
        }
        return enabled == that.enabled
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && type == that.type
                && Objects.equals(parameters, that.parameters);
    }

    /**
     * Returns the hash code for this action.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, enabled, type, parameters);
    }

    /**
     * Returns a diagnostic string.
     *
     * @return a diagnostic string
     */
    @Override
    public String toString() {
        return "StepAction{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", enabled=" + enabled +
                ", type=" + type +
                ", parameters=" + parameters +
                '}';
    }

    private static Map<String, String> copyStringMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> copied.put(
                normalizeRequiredText(key, "parameters key"),
                value == null ? "" : value));
        return Map.copyOf(copied);
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
