package dev.nquangit.corestra.session;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Configures when and how a session profile runs its main authentication flow.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class SessionAuthConfig {
    private final String mainAuthFlowId;
    private final long ttlSeconds;
    private final List<SessionScopeCriterion> triggerCriteria;
    private final String reloginStatusCodes;
    private final String reloginBodyContains;
    private final boolean resendAfterRelogin;

    /**
     * Creates auth configuration without live re-login conditions.
     *
     * @param mainAuthFlowId Flowise flow id used for authentication
     * @param ttlSeconds session TTL in seconds, or 0 for no explicit expiry
     * @param triggerCriteria optional request criteria that permit on-demand auth
     */
    public SessionAuthConfig(String mainAuthFlowId, long ttlSeconds, List<SessionScopeCriterion> triggerCriteria) {
        this(mainAuthFlowId, ttlSeconds, triggerCriteria, "", "", false);
    }

    /**
     * Creates auth configuration.
     *
     * <p>Live re-login conditions apply to standalone profiles only: when a live response handled
     * by the Session Handler has one of {@code reloginStatusCodes} <em>or</em> its body contains
     * {@code reloginBodyContains} (case-insensitive), the auth flow runs again. Linked profiles use
     * their Auth Provider's failure rules and target retry policy instead.</p>
     *
     * @param mainAuthFlowId Flowise flow id used for authentication
     * @param ttlSeconds session TTL in seconds, or 0 for no explicit expiry
     * @param triggerCriteria optional request criteria that permit on-demand auth
     * @param reloginStatusCodes comma-separated HTTP status codes meaning "session lost", or empty
     * @param reloginBodyContains body text meaning "session lost", or empty
     * @param resendAfterRelogin whether the failed live request is sent once more after re-login
     * @throws IllegalArgumentException when a status code is not a number between 100 and 599
     */
    @JsonCreator
    public SessionAuthConfig(
            @JsonProperty("mainAuthFlowId") String mainAuthFlowId,
            @JsonProperty("ttlSeconds") long ttlSeconds,
            @JsonProperty("triggerCriteria") List<SessionScopeCriterion> triggerCriteria,
            @JsonProperty("reloginStatusCodes") String reloginStatusCodes,
            @JsonProperty("reloginBodyContains") String reloginBodyContains,
            @JsonProperty("resendAfterRelogin") boolean resendAfterRelogin) {
        this.mainAuthFlowId = normalizeOptionalText(mainAuthFlowId);
        this.ttlSeconds = Math.max(0L, ttlSeconds);
        this.triggerCriteria = copyCriteria(triggerCriteria);
        this.reloginStatusCodes = normalizeStatusCodes(reloginStatusCodes);
        this.reloginBodyContains = normalizeOptionalText(reloginBodyContains);
        this.resendAfterRelogin = resendAfterRelogin;
    }

    /**
     * Returns default auth config.
     *
     * @return default config
     */
    public static SessionAuthConfig defaults() {
        return new SessionAuthConfig("", 0L, List.of());
    }

    /**
     * Returns the main auth flow id.
     *
     * @return flow id, or empty
     */
    public String mainAuthFlowId() {
        return mainAuthFlowId;
    }

    /**
     * Returns the TTL in seconds.
     *
     * @return TTL seconds, or 0
     */
    public long ttlSeconds() {
        return ttlSeconds;
    }

    /**
     * Returns on-demand auth trigger criteria.
     *
     * @return immutable criteria
     */
    public List<SessionScopeCriterion> triggerCriteria() {
        return triggerCriteria;
    }

    /**
     * Returns whether on-demand auth may run for the supplied request.
     *
     * @param method request method
     * @param host request host
     * @param path request path
     * @param headers request headers
     * @param body request body
     * @return true when allowed
     */
    public boolean matchesTrigger(String method, String host, String path, List<String> headers, String body) {
        return matchesTrigger(new SessionTrafficContext("", false, method, host, path, headers, body));
    }

    /** Checks trigger criteria against complete request metadata, including port/protocol. */
    public boolean matchesTrigger(SessionTrafficContext context) {
        return triggerCriteria.stream().allMatch(criterion -> criterion.matches(context));
    }

    /**
     * Returns the normalised comma-separated status codes that mean "session lost".
     *
     * @return codes such as {@code 401,419}, or empty
     */
    public String reloginStatusCodes() {
        return reloginStatusCodes;
    }

    /**
     * Returns the parsed status codes that mean "session lost".
     *
     * @return immutable status codes
     */
    @JsonIgnore
    public List<Integer> reloginStatusCodeList() {
        if (reloginStatusCodes.isEmpty()) {
            return List.of();
        }
        return java.util.Arrays.stream(reloginStatusCodes.split(",")).map(Integer::valueOf).toList();
    }

    /**
     * Returns the body text that means "session lost".
     *
     * @return text, or empty
     */
    public String reloginBodyContains() {
        return reloginBodyContains;
    }

    /**
     * Returns whether a failed live request is sent once more after re-login.
     *
     * @return true when resending
     */
    public boolean resendAfterRelogin() {
        return resendAfterRelogin;
    }

    /**
     * Returns whether any live re-login condition is configured.
     *
     * @return true when status codes or body text are set
     */
    @JsonIgnore
    public boolean hasReloginConditions() {
        return !reloginStatusCodes.isEmpty() || !reloginBodyContains.isEmpty();
    }

    /**
     * Returns whether a live response means the standalone session was lost.
     *
     * @param statusCode response status code
     * @param body response body
     * @return true when a configured status code or body text matches
     */
    public boolean indicatesSessionLost(int statusCode, String body) {
        if (reloginStatusCodeList().contains(statusCode)) {
            return true;
        }
        return !reloginBodyContains.isEmpty() && body != null
                && body.toLowerCase(java.util.Locale.ROOT).contains(reloginBodyContains.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Returns a copy with different live re-login conditions.
     *
     * @param statusCodes comma-separated status codes
     * @param bodyContains body text
     * @param resend whether to resend the failed request once
     * @return updated config
     */
    public SessionAuthConfig withReloginConditions(String statusCodes, String bodyContains, boolean resend) {
        return new SessionAuthConfig(mainAuthFlowId, ttlSeconds, triggerCriteria, statusCodes, bodyContains, resend);
    }

    private static String normalizeStatusCodes(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        java.util.LinkedHashSet<Integer> codes = new java.util.LinkedHashSet<>();
        for (String part : value.split("[,\\s]+")) {
            if (part.isBlank()) continue;
            int code;
            try {
                code = Integer.parseInt(part.strip());
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Re-login status code must be a number: " + part.strip());
            }
            if (code < 100 || code > 599) {
                throw new IllegalArgumentException("Re-login status code must be between 100 and 599: " + code);
            }
            codes.add(code);
        }
        return String.join(",", codes.stream().map(String::valueOf).toList());
    }

    /**
     * Compares this config to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SessionAuthConfig that)) {
            return false;
        }
        return ttlSeconds == that.ttlSeconds
                && resendAfterRelogin == that.resendAfterRelogin
                && Objects.equals(mainAuthFlowId, that.mainAuthFlowId)
                && Objects.equals(triggerCriteria, that.triggerCriteria)
                && Objects.equals(reloginStatusCodes, that.reloginStatusCodes)
                && Objects.equals(reloginBodyContains, that.reloginBodyContains);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(mainAuthFlowId, ttlSeconds, triggerCriteria, reloginStatusCodes,
                reloginBodyContains, resendAfterRelogin);
    }

    private static List<SessionScopeCriterion> copyCriteria(List<SessionScopeCriterion> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return List.of();
        }
        ArrayList<SessionScopeCriterion> copied = new ArrayList<>();
        criteria.forEach(criterion -> copied.add(Objects.requireNonNull(criterion, "criterion")));
        return List.copyOf(copied);
    }

    private static String normalizeOptionalText(String value) {
        return value == null ? "" : value.strip();
    }
}
