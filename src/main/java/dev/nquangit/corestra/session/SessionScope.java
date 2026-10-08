package dev.nquangit.corestra.session;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Defines the traffic scope for a session profile.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@JsonIgnoreProperties("requireMatchingRule")
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

    /** Creates shared AND criteria and optional alternative OR rules; without alternatives only shared criteria apply. */
    @JsonCreator
    public SessionScope(@JsonProperty("mode") SessionScopeMode mode,
                        @JsonProperty("criteria") List<SessionScopeCriterion> criteria,
                        @JsonProperty("rules") List<SessionScopeRule> rules) {
        this.mode = Objects.requireNonNullElse(mode, SessionScopeMode.BURP_TARGET_SCOPE);
        this.criteria = copyCriteria(criteria);
        List<SessionScopeRule> configuredRules = List.copyOf(Objects.requireNonNullElse(rules, List.of()));
        if (configuredRules.stream().map(SessionScopeRule::name).distinct().count() != configuredRules.size()) {
            throw new IllegalArgumentException("Scope rule names must be unique");
        }
        // Earlier versions generated this placeholder when the last alternative was deleted.
        // Normalize it at the domain boundary so runtime matching and the editor agree.
        this.rules = configuredRules.stream()
                .filter(rule -> !isLegacyEmptyScopePlaceholder(rule)).toList();
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

    /** Matches shared criteria AND any alternative, or just shared criteria when no alternatives exist. */
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

    private static boolean isLegacyEmptyScopePlaceholder(SessionScopeRule rule) {
        if (!"Empty custom scope".equals(rule.name())) return false;
        if (rule.criteria().isEmpty()) return true;
        if (rule.criteria().size() != 1) return false;
        SessionScopeCriterion criterion = rule.criteria().getFirst();
        return criterion.type() == SessionCriterionType.HOST && criterion.selector().isBlank()
                && "(?!)".equals(criterion.pattern());
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
