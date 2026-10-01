package dev.nquangit.corestra.access.auth;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Describes a configured authentication provider for future login, refresh, and validation flows.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class AuthProvider {
    /** Default time for reusing auth state when a flow does not extract an expiry. */
    public static final long DEFAULT_STATE_TTL_SECONDS = 300L;
    /** Maximum configurable fallback state lifetime. */
    public static final long MAX_STATE_TTL_SECONDS = 86_400L;
    private final String id;
    private final String name;
    private final String description;
    private final boolean enabled;
    private final String loginFlowId;
    private final String refreshFlowId;
    private final String validateFlowId;
    private final List<AuthInjectionRule> injectionRules;
    private final List<AuthSuccessRule> successRules;
    private final List<AuthFailureRule> failureRules;
    private final AuthFallbackPolicy fallbackPolicy;
    private final AuthRetryPolicy targetRetryPolicy;
    private final long stateTtlSeconds;

    /**
     * Creates an auth provider configuration.
     *
     * @param id stable provider identifier
     * @param name display name
     * @param description optional description
     * @param enabled whether this provider is enabled
     * @param loginFlowId referenced login flow id
     * @param refreshFlowId referenced refresh flow id
     * @param validateFlowId referenced validate flow id
     * @param injectionRules configured injection rules
     * @param successRules configured success rules
     * @param failureRules configured failure rules
     * @param fallbackPolicy future fallback policy
     * @param targetRetryPolicy future target retry policy
     */
    public AuthProvider(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("loginFlowId") String loginFlowId,
            @JsonProperty("refreshFlowId") String refreshFlowId,
            @JsonProperty("validateFlowId") String validateFlowId,
            @JsonProperty("injectionRules") List<AuthInjectionRule> injectionRules,
            @JsonProperty("successRules") List<AuthSuccessRule> successRules,
            @JsonProperty("failureRules") List<AuthFailureRule> failureRules,
            @JsonProperty("fallbackPolicy") AuthFallbackPolicy fallbackPolicy,
            @JsonProperty("targetRetryPolicy") AuthRetryPolicy targetRetryPolicy) {
        this(id, name, description, enabled, loginFlowId, refreshFlowId, validateFlowId,
                injectionRules, successRules, failureRules, fallbackPolicy, targetRetryPolicy,
                DEFAULT_STATE_TTL_SECONDS);
    }

    /** Creates an auth provider configuration with a fallback state lifetime. */
    @JsonCreator
    public AuthProvider(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("loginFlowId") String loginFlowId,
            @JsonProperty("refreshFlowId") String refreshFlowId,
            @JsonProperty("validateFlowId") String validateFlowId,
            @JsonProperty("injectionRules") List<AuthInjectionRule> injectionRules,
            @JsonProperty("successRules") List<AuthSuccessRule> successRules,
            @JsonProperty("failureRules") List<AuthFailureRule> failureRules,
            @JsonProperty("fallbackPolicy") AuthFallbackPolicy fallbackPolicy,
            @JsonProperty("targetRetryPolicy") AuthRetryPolicy targetRetryPolicy,
            @JsonProperty("stateTtlSeconds") Long stateTtlSeconds) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.description = normalizeOptionalText(description);
        this.enabled = enabled;
        this.loginFlowId = normalizeOptionalText(loginFlowId);
        this.refreshFlowId = normalizeOptionalText(refreshFlowId);
        this.validateFlowId = normalizeOptionalText(validateFlowId);
        this.injectionRules = copyList(injectionRules, "injectionRules");
        this.successRules = copyList(successRules, "successRules");
        this.failureRules = copyList(failureRules, "failureRules");
        this.fallbackPolicy = Objects.requireNonNullElse(fallbackPolicy, AuthFallbackPolicy.MANUAL_ONLY);
        this.targetRetryPolicy = Objects.requireNonNullElse(targetRetryPolicy, AuthRetryPolicy.NO_RETRY);
        long ttl = stateTtlSeconds == null ? DEFAULT_STATE_TTL_SECONDS : stateTtlSeconds;
        if (ttl < 0L || ttl > MAX_STATE_TTL_SECONDS) {
            throw new IllegalArgumentException("stateTtlSeconds must be between 0 and " + MAX_STATE_TTL_SECONDS);
        }
        this.stateTtlSeconds = ttl;
    }

    /**
     * Returns the stable provider identifier.
     *
     * @return stable provider identifier
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
     * Returns the optional description.
     *
     * @return description, or an empty string
     */
    public String description() {
        return description;
    }

    /**
     * Returns whether this provider is enabled.
     *
     * @return whether this provider is enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the referenced login flow id.
     *
     * @return login flow id, or an empty string
     */
    public String loginFlowId() {
        return loginFlowId;
    }

    /**
     * Returns the referenced refresh flow id.
     *
     * @return refresh flow id, or an empty string
     */
    public String refreshFlowId() {
        return refreshFlowId;
    }

    /**
     * Returns the referenced validate flow id.
     *
     * @return validate flow id, or an empty string
     */
    public String validateFlowId() {
        return validateFlowId;
    }

    /**
     * Returns configured injection rules.
     *
     * @return immutable injection rules
     */
    public List<AuthInjectionRule> injectionRules() {
        return injectionRules;
    }

    /**
     * Returns configured success rules.
     *
     * @return immutable success rules
     */
    public List<AuthSuccessRule> successRules() {
        return successRules;
    }

    /**
     * Returns configured failure rules.
     *
     * @return immutable failure rules
     */
    public List<AuthFailureRule> failureRules() {
        return failureRules;
    }

    /**
     * Returns the future fallback policy.
     *
     * @return fallback policy
     */
    public AuthFallbackPolicy fallbackPolicy() {
        return fallbackPolicy;
    }

    /**
     * Returns the future target retry policy.
     *
     * @return target retry policy
     */
    public AuthRetryPolicy targetRetryPolicy() {
        return targetRetryPolicy;
    }

    /** Returns the fallback auth-state lifetime in seconds. */
    public long stateTtlSeconds() {
        return stateTtlSeconds;
    }

    /**
     * Compares this provider to another object.
     *
     * @param other the candidate object
     * @return true when the objects are equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AuthProvider that)) {
            return false;
        }
        return enabled == that.enabled
                && stateTtlSeconds == that.stateTtlSeconds
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && Objects.equals(description, that.description)
                && Objects.equals(loginFlowId, that.loginFlowId)
                && Objects.equals(refreshFlowId, that.refreshFlowId)
                && Objects.equals(validateFlowId, that.validateFlowId)
                && Objects.equals(injectionRules, that.injectionRules)
                && Objects.equals(successRules, that.successRules)
                && Objects.equals(failureRules, that.failureRules)
                && fallbackPolicy == that.fallbackPolicy
                && targetRetryPolicy == that.targetRetryPolicy;
    }

    /**
     * Returns the hash code for this provider.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, description, enabled, loginFlowId, refreshFlowId, validateFlowId,
                injectionRules, successRules, failureRules, fallbackPolicy, targetRetryPolicy, stateTtlSeconds);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "AuthProvider{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", enabled=" + enabled +
                ", loginFlowId='" + loginFlowId + '\'' +
                ", refreshFlowId='" + refreshFlowId + '\'' +
                ", validateFlowId='" + validateFlowId + '\'' +
                ", injectionRules=" + injectionRules.size() +
                ", successRules=" + successRules.size() +
                ", failureRules=" + failureRules.size() +
                ", fallbackPolicy=" + fallbackPolicy +
                ", targetRetryPolicy=" + targetRetryPolicy +
                ", stateTtlSeconds=" + stateTtlSeconds +
                '}';
    }

    private static <T> List<T> copyList(List<T> values, String fieldName) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<T> copied = new ArrayList<>();
        values.forEach(value -> copied.add(Objects.requireNonNull(value, fieldName + " value")));
        return List.copyOf(copied);
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
