package dev.nquangit.corestra.access.data;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Stores stable owner data used to bind access tests to business entities.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class DataProfile {
    private final String id;
    private final String name;
    private final String ownerAuthProfileId;
    private final Map<String, String> values;
    private final boolean enabled;

    /**
     * Creates a data profile.
     *
     * @param id stable profile identifier
     * @param name display name
     * @param ownerAuthProfileId auth profile that owns the data
     * @param values stable data values such as accountId, cardId, and customerId
     * @param enabled whether this data profile is enabled
     */
    @JsonCreator
    public DataProfile(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("ownerAuthProfileId") String ownerAuthProfileId,
            @JsonProperty("values") Map<String, String> values,
            @JsonProperty("enabled") boolean enabled) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.ownerAuthProfileId = normalizeOptionalText(ownerAuthProfileId);
        this.values = copyMap(values);
        this.enabled = enabled;
    }

    /**
     * Creates an enabled data profile.
     *
     * @param id stable profile identifier
     * @param name display name
     * @param ownerAuthProfileId auth profile that owns the data
     * @param values stable data values
     * @return an enabled data profile
     */
    public static DataProfile enabled(String id, String name, String ownerAuthProfileId, Map<String, String> values) {
        return new DataProfile(id, name, ownerAuthProfileId, values, true);
    }

    /**
     * Returns the stable profile identifier.
     *
     * @return stable profile identifier
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
     * Returns the owning auth profile identifier.
     *
     * @return owner auth profile identifier, or an empty string
     */
    public String ownerAuthProfileId() {
        return ownerAuthProfileId;
    }

    /**
     * Returns stable business data values.
     *
     * @return immutable values
     */
    public Map<String, String> values() {
        return values;
    }

    /**
     * Returns whether the profile is enabled.
     *
     * @return whether the profile is enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Compares this profile to another object.
     *
     * @param other the candidate object
     * @return true when the objects are equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DataProfile that)) {
            return false;
        }
        return enabled == that.enabled
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && Objects.equals(ownerAuthProfileId, that.ownerAuthProfileId)
                && Objects.equals(values, that.values);
    }

    /**
     * Returns the hash code for this profile.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, ownerAuthProfileId, values, enabled);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "DataProfile{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", ownerAuthProfileId='" + ownerAuthProfileId + '\'' +
                ", valueKeys=" + values.keySet() +
                ", enabled=" + enabled +
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

    private static Map<String, String> copyMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("data keys must not be blank");
            }
            copied.put(key.strip(), Objects.requireNonNullElse(value, ""));
        });
        return Map.copyOf(copied);
    }
}
