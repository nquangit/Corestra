package dev.nquangit.corestra.session;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
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

    /**
     * Creates auth configuration.
     *
     * @param mainAuthFlowId Flowise flow id used for authentication
     * @param ttlSeconds session TTL in seconds, or 0 for no explicit expiry
     * @param triggerCriteria optional request criteria that permit on-demand auth
     */
    @JsonCreator
    public SessionAuthConfig(
            @JsonProperty("mainAuthFlowId") String mainAuthFlowId,
            @JsonProperty("ttlSeconds") long ttlSeconds,
            @JsonProperty("triggerCriteria") List<SessionScopeCriterion> triggerCriteria) {
        this.mainAuthFlowId = normalizeOptionalText(mainAuthFlowId);
        this.ttlSeconds = Math.max(0L, ttlSeconds);
        this.triggerCriteria = copyCriteria(triggerCriteria);
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
                && Objects.equals(mainAuthFlowId, that.mainAuthFlowId)
                && Objects.equals(triggerCriteria, that.triggerCriteria);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(mainAuthFlowId, ttlSeconds, triggerCriteria);
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
