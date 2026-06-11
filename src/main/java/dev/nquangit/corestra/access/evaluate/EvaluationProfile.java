package dev.nquangit.corestra.access.evaluate;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Defines advanced response evaluation rules for access test cases.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class EvaluationProfile {
    private final String id;
    private final String name;
    private final boolean enabled;
    private final List<ResponseIndicator> allowIndicators;
    private final List<ResponseIndicator> denyIndicators;
    private final List<SensitiveFieldRule> sensitiveFieldRules;
    private final List<MaskingRule> maskedFieldRules;

    /**
     * Creates an evaluation profile.
     *
     * @param id stable profile identifier
     * @param name display name
     * @param enabled whether this profile is enabled
     * @param allowIndicators indicators that identify a business allow/success response
     * @param denyIndicators indicators that identify a business deny/failure response
     * @param sensitiveFieldRules fields that must be absent or masked for MASKED expectations
     * @param maskedFieldRules acceptable masking patterns for sensitive fields
     */
    @JsonCreator
    public EvaluationProfile(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("allowIndicators") List<ResponseIndicator> allowIndicators,
            @JsonProperty("denyIndicators") List<ResponseIndicator> denyIndicators,
            @JsonProperty("sensitiveFieldRules") List<SensitiveFieldRule> sensitiveFieldRules,
            @JsonProperty("maskedFieldRules") List<MaskingRule> maskedFieldRules) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.enabled = enabled;
        this.allowIndicators = copyList(allowIndicators, "allowIndicators");
        this.denyIndicators = copyList(denyIndicators, "denyIndicators");
        this.sensitiveFieldRules = copyList(sensitiveFieldRules, "sensitiveFieldRules");
        this.maskedFieldRules = copyList(maskedFieldRules, "maskedFieldRules");
    }

    /**
     * Returns the stable identifier.
     *
     * @return profile id
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
     * Returns whether this profile is enabled.
     *
     * @return true when enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns allow indicators.
     *
     * @return immutable allow indicators
     */
    public List<ResponseIndicator> allowIndicators() {
        return allowIndicators;
    }

    /**
     * Returns deny indicators.
     *
     * @return immutable deny indicators
     */
    public List<ResponseIndicator> denyIndicators() {
        return denyIndicators;
    }

    /**
     * Returns sensitive field rules.
     *
     * @return immutable sensitive field rules
     */
    public List<SensitiveFieldRule> sensitiveFieldRules() {
        return sensitiveFieldRules;
    }

    /**
     * Returns masked field rules.
     *
     * @return immutable masked field rules
     */
    public List<MaskingRule> maskedFieldRules() {
        return maskedFieldRules;
    }

    /**
     * Compares this profile to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EvaluationProfile that)) {
            return false;
        }
        return enabled == that.enabled
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && Objects.equals(allowIndicators, that.allowIndicators)
                && Objects.equals(denyIndicators, that.denyIndicators)
                && Objects.equals(sensitiveFieldRules, that.sensitiveFieldRules)
                && Objects.equals(maskedFieldRules, that.maskedFieldRules);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, enabled, allowIndicators, denyIndicators, sensitiveFieldRules, maskedFieldRules);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return diagnostic string
     */
    @Override
    public String toString() {
        return "EvaluationProfile{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", enabled=" + enabled +
                ", allowIndicators=" + allowIndicators.size() +
                ", denyIndicators=" + denyIndicators.size() +
                ", sensitiveFieldRules=" + sensitiveFieldRules.size() +
                ", maskedFieldRules=" + maskedFieldRules.size() +
                '}';
    }

    private static <T> List<T> copyList(List<T> values, String fieldName) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        ArrayList<T> copied = new ArrayList<>();
        values.forEach(value -> copied.add(Objects.requireNonNull(value, fieldName + " entry")));
        return List.copyOf(copied);
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
