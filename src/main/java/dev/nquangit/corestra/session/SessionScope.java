package dev.nquangit.corestra.session;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Defines the traffic scope for a session profile.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class SessionScope {
    private final SessionScopeMode mode;
    private final List<SessionScopeCriterion> criteria;

    /**
     * Creates a session scope.
     *
     * @param mode scope mode
     * @param criteria custom scope criteria
     */
    @JsonCreator
    public SessionScope(
            @JsonProperty("mode") SessionScopeMode mode,
            @JsonProperty("criteria") List<SessionScopeCriterion> criteria) {
        this.mode = Objects.requireNonNullElse(mode, SessionScopeMode.BURP_TARGET_SCOPE);
        this.criteria = copyCriteria(criteria);
    }

    /**
     * Returns the default Burp target scope.
     *
     * @return target scope
     */
    public static SessionScope burpTargetScope() {
        return new SessionScope(SessionScopeMode.BURP_TARGET_SCOPE, List.of());
    }

    /**
     * Returns a custom scope.
     *
     * @param criteria criteria to apply
     * @return custom scope
     */
    public static SessionScope custom(List<SessionScopeCriterion> criteria) {
        return new SessionScope(SessionScopeMode.CUSTOM, criteria);
    }

    /**
     * Returns the scope mode.
     *
     * @return scope mode
     */
    public SessionScopeMode mode() {
        return mode;
    }

    /**
     * Returns immutable custom criteria.
     *
     * @return criteria
     */
    public List<SessionScopeCriterion> criteria() {
        return criteria;
    }

    /**
     * Returns whether traffic is in scope.
     *
     * @param burpInScope Burp target-scope result
     * @param method request method
     * @param host request host
     * @param path request path without query
     * @param headers request headers
     * @param body request body
     * @return true when in scope
     */
    public boolean matches(boolean burpInScope, String method, String host, String path, List<String> headers, String body) {
        if (mode == SessionScopeMode.BURP_TARGET_SCOPE) {
            return burpInScope;
        }
        for (SessionScopeCriterion criterion : criteria) {
            if (!criterion.matches(method, host, path, headers, body)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares this scope to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SessionScope that)) {
            return false;
        }
        return mode == that.mode && Objects.equals(criteria, that.criteria);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(mode, criteria);
    }

    private static List<SessionScopeCriterion> copyCriteria(List<SessionScopeCriterion> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return List.of();
        }
        ArrayList<SessionScopeCriterion> copied = new ArrayList<>();
        criteria.forEach(criterion -> copied.add(Objects.requireNonNull(criterion, "criterion")));
        return List.copyOf(copied);
    }
}
