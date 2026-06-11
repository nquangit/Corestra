package dev.nquangit.corestra.access.auth;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Describes an actor identity used by access-testing scenarios.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class AuthProfile {
    /**
     * Provider identifier used for manual AuthProfile state.
     */
    public static final String MANUAL_PROVIDER_ID = "manual";

    private final String id;
    private final String name;
    private final String role;
    private final String providerId;
    private final Map<String, String> credentials;
    private final AuthState state;
    private final boolean enabled;

    /**
     * Creates an auth profile.
     *
     * @param id stable profile identifier
     * @param name display name
     * @param role optional role label
     * @param providerId provider identifier, defaulting to manual when blank
     * @param credentials provider credentials for the profile
     * @param state current auth state
     * @param enabled whether the profile is enabled
     */
    @JsonCreator
    public AuthProfile(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("role") String role,
            @JsonProperty("providerId") String providerId,
            @JsonProperty("credentials") Map<String, String> credentials,
            @JsonProperty("state") AuthState state,
            @JsonProperty("enabled") boolean enabled) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.role = normalizeOptionalText(role);
        this.providerId = providerId == null || providerId.isBlank() ? MANUAL_PROVIDER_ID : providerId.strip();
        this.credentials = copyMap(credentials);
        this.state = state == null ? AuthState.empty() : state;
        this.enabled = enabled;
    }

    /**
     * Creates an enabled manual auth profile with no credentials and empty state.
     *
     * @param id stable profile identifier
     * @param name display name
     * @param role optional role label
     * @return an enabled manual auth profile
     */
    public static AuthProfile manual(String id, String name, String role) {
        return new AuthProfile(id, name, role, MANUAL_PROVIDER_ID, Map.of(), AuthState.empty(), true);
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
     * Returns the optional role label.
     *
     * @return role label, or an empty string
     */
    public String role() {
        return role;
    }

    /**
     * Returns the provider identifier.
     *
     * @return provider identifier
     */
    public String providerId() {
        return providerId;
    }

    /**
     * Returns provider credentials.
     *
     * @return immutable credentials
     */
    public Map<String, String> credentials() {
        return credentials;
    }

    /**
     * Returns the current auth state.
     *
     * @return current auth state
     */
    public AuthState state() {
        return state;
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
        if (!(other instanceof AuthProfile that)) {
            return false;
        }
        return enabled == that.enabled
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && Objects.equals(role, that.role)
                && Objects.equals(providerId, that.providerId)
                && Objects.equals(credentials, that.credentials)
                && Objects.equals(state, that.state);
    }

    /**
     * Returns the hash code for this profile.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, role, providerId, credentials, state, enabled);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "AuthProfile{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", role='" + role + '\'' +
                ", providerId='" + providerId + '\'' +
                ", credentialKeys=" + credentials.keySet() +
                ", state=" + state +
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
                throw new IllegalArgumentException("credential keys must not be blank");
            }
            copied.put(key.strip(), Objects.requireNonNullElse(value, ""));
        });
        return Map.copyOf(copied);
    }
}
