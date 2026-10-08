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
    private final List<SessionScopeRule> rules;

    /**
     * Creates a session scope.
     *
     * @param mode scope mode
     * @param criteria custom scope criteria
     */
    public SessionScope(
            SessionScopeMode mode, List<SessionScopeCriterion> criteria) {
        this(mode, criteria, List.of());
    }

    /** Creates common AND criteria plus alternative OR rules; missing rules preserve legacy semantics. */
    @JsonCreator
    public SessionScope(@JsonProperty("mode") SessionScopeMode mode,
                        @JsonProperty("criteria") List<SessionScopeCriterion> criteria,
                        @JsonProperty("rules") List<SessionScopeRule> rules) {
        this.mode = Objects.requireNonNullElse(mode, SessionScopeMode.BURP_TARGET_SCOPE);
        this.criteria = copyCriteria(criteria);
        this.rules = List.copyOf(Objects.requireNonNullElse(rules, List.of()));
        if (this.rules.stream().map(SessionScopeRule::name).distinct().count() != this.rules.size()) {
            throw new IllegalArgumentException("Scope rule names must be unique");
        }
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
     * Returns immutable common custom criteria, ANDed before alternative scope rules.
     *
     * @return criteria
     */
    public List<SessionScopeCriterion> criteria() {
        return criteria;
    }

    /** Returns immutable alternative scope rules, ORed after the common criteria. */
    public List<SessionScopeRule> rules() {
        return rules;
    }

    /** Matches common criteria AND at least one rule; legacy scopes without rules remain an AND list. */
    public boolean matches(SessionTrafficContext context) {
        Objects.requireNonNull(context, "context");
        if (mode == SessionScopeMode.BURP_TARGET_SCOPE) return context.burpInScope();
        if (!criteria.stream().allMatch(criterion -> criterion.matches(context))) return false;
        return rules.isEmpty() || rules.stream().anyMatch(rule -> rule.matches(context));
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
        return matches(new SessionTrafficContext("", burpInScope, method, host, path, headers, body));
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
        return mode == that.mode && Objects.equals(criteria, that.criteria) && Objects.equals(rules, that.rules);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(mode, criteria, rules);
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
