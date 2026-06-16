package dev.nquangit.corestra.access.auth;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Describes one configured AuthProvider injection rule.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class AuthInjectionRule {
    private final String id;
    private final String name;
    private final boolean enabled;
    private final AuthInjectionLocation location;
    private final String target;
    private final String valueTemplate;
    private final AuthInjectionMode mode;

    /**
     * Creates an auth injection rule.
     *
     * @param id stable rule identifier
     * @param name display name
     * @param enabled whether this rule is enabled
     * @param location injection location
     * @param target target name, path placeholder, or field selector
     * @param valueTemplate rendered value template
     * @param mode injection mode
     */
    @JsonCreator
    public AuthInjectionRule(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("location") AuthInjectionLocation location,
            @JsonProperty("target") String target,
            @JsonProperty("valueTemplate") String valueTemplate,
            @JsonProperty("mode") AuthInjectionMode mode) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.enabled = enabled;
        this.location = Objects.requireNonNullElse(location, AuthInjectionLocation.HEADER);
        this.target = normalizeOptionalText(target);
        this.valueTemplate = normalizeOptionalText(valueTemplate);
        this.mode = Objects.requireNonNullElse(mode, AuthInjectionMode.OVERRIDE);
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
     * Returns the injection location.
     *
     * @return injection location
     */
    public AuthInjectionLocation location() {
        return location;
    }

    /**
     * Returns the target name, path placeholder, or field selector.
     *
     * @return target, or an empty string
     */
    public String target() {
        return target;
    }

    /**
     * Returns the rendered value template.
     *
     * @return rendered value template, or an empty string
     */
    public String valueTemplate() {
        return valueTemplate;
    }

    /**
     * Returns the injection mode.
     *
     * @return injection mode
     */
    public AuthInjectionMode mode() {
        return mode;
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
        if (!(other instanceof AuthInjectionRule that)) {
            return false;
        }
        return enabled == that.enabled
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && location == that.location
                && Objects.equals(target, that.target)
                && Objects.equals(valueTemplate, that.valueTemplate)
                && mode == that.mode;
    }

    /**
     * Returns the hash code for this rule.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, enabled, location, target, valueTemplate, mode);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "AuthInjectionRule{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", enabled=" + enabled +
                ", location=" + location +
                ", target='" + target + '\'' +
                ", mode=" + mode +
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
