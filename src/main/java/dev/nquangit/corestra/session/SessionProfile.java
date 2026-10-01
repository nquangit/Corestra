package dev.nquangit.corestra.session;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A named session profile with global per-profile session state.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class SessionProfile {
    /**
     * Stable id of the built-in fallback profile.
     */
    public static final String GENERAL_ID = "general";

    private final String id;
    private final String name;
    private final boolean enabled;
    private final boolean systemProfile;
    private final SessionScope scope;
    private final String linkedAuthProfileId;
    private final SessionAuthConfig authConfig;
    private final SessionSchedulerConfig scheduler;
    private final SessionState state;
    private final List<SessionInjectionRule> injectionRules;
    private final List<SessionExtractionRule> extractionRules;

    /**
     * Creates a session profile.
     *
     * @param id stable profile id
     * @param name display name
     * @param enabled whether profile is enabled
     * @param systemProfile true for built-in profiles
     * @param scope session scope
     * @param authConfig authentication configuration
     * @param scheduler scheduler configuration
     * @param state global session state
     * @param injectionRules request injection rules
     * @param extractionRules request/response extraction rules
     */
    @JsonCreator
    public SessionProfile(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("systemProfile") boolean systemProfile,
            @JsonProperty("scope") SessionScope scope,
            @JsonProperty("linkedAuthProfileId") String linkedAuthProfileId,
            @JsonProperty("authConfig") SessionAuthConfig authConfig,
            @JsonProperty("scheduler") SessionSchedulerConfig scheduler,
            @JsonProperty("state") SessionState state,
            @JsonProperty("injectionRules") List<SessionInjectionRule> injectionRules,
            @JsonProperty("extractionRules") List<SessionExtractionRule> extractionRules) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.enabled = enabled;
        this.systemProfile = systemProfile || GENERAL_ID.equals(this.id);
        this.scope = scope == null ? SessionScope.burpTargetScope() : scope;
        this.linkedAuthProfileId = normalizeOptionalText(linkedAuthProfileId);
        this.authConfig = authConfig == null ? SessionAuthConfig.defaults() : authConfig;
        this.scheduler = scheduler == null ? SessionSchedulerConfig.disabled() : scheduler;
        this.state = state == null ? SessionState.empty() : state;
        this.injectionRules = copyInjectionRules(injectionRules);
        this.extractionRules = copyExtractionRules(extractionRules);
    }

    /**
     * Creates a standalone session profile compatible with project documents written before
     * linked Access Auth Profiles were supported.
     */
    public SessionProfile(
            String id,
            String name,
            boolean enabled,
            boolean systemProfile,
            SessionScope scope,
            SessionAuthConfig authConfig,
            SessionSchedulerConfig scheduler,
            SessionState state,
            List<SessionInjectionRule> injectionRules,
            List<SessionExtractionRule> extractionRules) {
        this(id, name, enabled, systemProfile, scope, "", authConfig, scheduler, state,
                injectionRules, extractionRules);
    }

    /**
     * Returns the built-in General fallback profile.
     *
     * @return General profile
     */
    public static SessionProfile general() {
        return new SessionProfile(
                GENERAL_ID,
                "General",
                true,
                true,
                SessionScope.burpTargetScope(),
                "",
                SessionAuthConfig.defaults(),
                SessionSchedulerConfig.disabled(),
                SessionState.empty(),
                List.of(),
                List.of());
    }

    /**
     * Returns the stable profile id.
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
     * Returns whether this profile is built-in.
     *
     * @return true for system profiles
     */
    public boolean systemProfile() {
        return systemProfile;
    }

    /**
     * Returns the session scope.
     *
     * @return scope
     */
    public SessionScope scope() {
        return scope;
    }

    /**
     * Returns the shared Access Auth Profile used by this binding.
     *
     * @return auth profile id, or an empty string for standalone flow mode
     */
    public String linkedAuthProfileId() {
        return linkedAuthProfileId;
    }

    /**
     * Returns whether this session binding reuses a shared Access Auth Profile.
     *
     * @return true when a linked profile id is configured
     */
    @JsonIgnore
    public boolean usesLinkedAuthProfile() {
        return !linkedAuthProfileId.isBlank();
    }

    /**
     * Returns auth configuration.
     *
     * @return auth config
     */
    public SessionAuthConfig authConfig() {
        return authConfig;
    }

    /**
     * Returns scheduler configuration.
     *
     * @return scheduler config
     */
    public SessionSchedulerConfig scheduler() {
        return scheduler;
    }

    /**
     * Returns global session state.
     *
     * @return session state
     */
    public SessionState state() {
        return state;
    }

    /**
     * Returns immutable injection rules.
     *
     * @return injection rules
     */
    public List<SessionInjectionRule> injectionRules() {
        return injectionRules;
    }

    /**
     * Returns immutable extraction rules.
     *
     * @return extraction rules
     */
    public List<SessionExtractionRule> extractionRules() {
        return extractionRules;
    }

    /**
     * Returns whether this profile is the built-in General profile.
     *
     * @return true for General
     */
    @JsonIgnore
    public boolean isGeneral() {
        return GENERAL_ID.equals(id);
    }

    /**
     * Returns whether this profile matches a traffic context.
     *
     * @param burpInScope Burp target-scope result
     * @param method request method
     * @param host request host
     * @param path request path
     * @param headers request headers
     * @param body request body
     * @return true when enabled and matched
     */
    public boolean matches(boolean burpInScope, String method, String host, String path, List<String> headers, String body) {
        return enabled && scope.matches(burpInScope, method, host, path, headers, body);
    }

    /**
     * Returns a copy with updated state.
     *
     * @param state new state
     * @return updated profile
     */
    public SessionProfile withState(SessionState state) {
        return new SessionProfile(id, name, enabled, systemProfile, scope, linkedAuthProfileId, authConfig, scheduler,
                state, injectionRules, extractionRules);
    }

    /**
     * Returns a copy with updated enabled flag.
     *
     * @param enabled enabled flag
     * @return updated profile
     */
    public SessionProfile withEnabled(boolean enabled) {
        return new SessionProfile(id, name, enabled, systemProfile, scope, linkedAuthProfileId, authConfig, scheduler,
                state, injectionRules, extractionRules);
    }

    /**
     * Returns a copy with updated core settings.
     *
     * @param name display name
     * @param scope scope
     * @param authConfig auth config
     * @param scheduler scheduler config
     * @param injectionRules injection rules
     * @param extractionRules extraction rules
     * @return updated profile
     */
    public SessionProfile withConfig(
            String name,
            SessionScope scope,
            SessionAuthConfig authConfig,
            SessionSchedulerConfig scheduler,
            List<SessionInjectionRule> injectionRules,
            List<SessionExtractionRule> extractionRules) {
        return new SessionProfile(id, isGeneral() ? "General" : name, enabled, systemProfile, scope,
                linkedAuthProfileId, authConfig,
                scheduler, state, injectionRules, extractionRules);
    }

    /** Returns a copy configured to use the supplied shared Access Auth Profile. */
    public SessionProfile withLinkedAuthProfileId(String authProfileId) {
        return new SessionProfile(id, name, enabled, systemProfile, scope, authProfileId, authConfig,
                scheduler, state, injectionRules, extractionRules);
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
        if (!(other instanceof SessionProfile that)) {
            return false;
        }
        return enabled == that.enabled
                && systemProfile == that.systemProfile
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && Objects.equals(scope, that.scope)
                && Objects.equals(linkedAuthProfileId, that.linkedAuthProfileId)
                && Objects.equals(authConfig, that.authConfig)
                && Objects.equals(scheduler, that.scheduler)
                && Objects.equals(state, that.state)
                && Objects.equals(injectionRules, that.injectionRules)
                && Objects.equals(extractionRules, that.extractionRules);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, enabled, systemProfile, scope, linkedAuthProfileId, authConfig, scheduler,
                state, injectionRules, extractionRules);
    }

    /**
     * Returns the profile display name used by Swing lists and combo boxes.
     *
     * @return display name
     */
    @Override
    public String toString() {
        return enabled ? name : "[off] " + name;
    }

    private static List<SessionInjectionRule> copyInjectionRules(List<SessionInjectionRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        ArrayList<SessionInjectionRule> copied = new ArrayList<>();
        rules.forEach(rule -> copied.add(Objects.requireNonNull(rule, "injection rule")));
        return List.copyOf(copied);
    }

    private static List<SessionExtractionRule> copyExtractionRules(List<SessionExtractionRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        ArrayList<SessionExtractionRule> copied = new ArrayList<>();
        rules.forEach(rule -> copied.add(Objects.requireNonNull(rule, "extraction rule")));
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
