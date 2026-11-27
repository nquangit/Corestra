package dev.nquangit.corestra.session;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.nquangit.corestra.access.auth.AuthFailureRule;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.auth.AuthRuleType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Configures when and how a session profile runs its main authentication flow.
 *
 * <p>Live re-login for standalone profiles uses the same model as an Auth Provider: a list of
 * {@link AuthFailureRule}s (any enabled rule matching a live response means "session lost") and an
 * {@link AuthRetryPolicy}. A standalone profile has only a login flow, so refresh policies are
 * normalised to {@link AuthRetryPolicy#LOGIN_AND_RETRY_ONCE}. Linked profiles ignore these fields
 * and use their Auth Provider's failure rules and target retry policy.</p>
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class SessionAuthConfig {
    /** Default policy: re-run the standalone auth flow and resend the failed request once. */
    public static final AuthRetryPolicy DEFAULT_RETRY_POLICY = AuthRetryPolicy.LOGIN_AND_RETRY_ONCE;

    private final String mainAuthFlowId;
    private final long ttlSeconds;
    private final List<SessionScopeCriterion> triggerCriteria;
    private final List<AuthFailureRule> failureRules;
    private final AuthRetryPolicy retryPolicy;

    /**
     * Creates auth configuration without live re-login rules.
     *
     * @param mainAuthFlowId Flowise flow id used for authentication
     * @param ttlSeconds session TTL in seconds, or 0 for no explicit expiry
     * @param triggerCriteria optional request criteria that permit on-demand auth
     */
    public SessionAuthConfig(String mainAuthFlowId, long ttlSeconds, List<SessionScopeCriterion> triggerCriteria) {
        this(mainAuthFlowId, ttlSeconds, triggerCriteria, List.of(), DEFAULT_RETRY_POLICY);
    }

    /**
     * Creates auth configuration.
     *
     * @param mainAuthFlowId Flowise flow id used for authentication
     * @param ttlSeconds session TTL in seconds, or 0 for no explicit expiry
     * @param triggerCriteria optional request criteria that permit on-demand auth
     * @param failureRules rules that mark a live response as "session lost"
     * @param retryPolicy what to do when a failure rule matches
     */
    public SessionAuthConfig(String mainAuthFlowId, long ttlSeconds, List<SessionScopeCriterion> triggerCriteria,
                             List<AuthFailureRule> failureRules, AuthRetryPolicy retryPolicy) {
        this.mainAuthFlowId = normalizeOptionalText(mainAuthFlowId);
        this.ttlSeconds = Math.max(0L, ttlSeconds);
        this.triggerCriteria = copyCriteria(triggerCriteria);
        this.failureRules = failureRules == null ? List.of() : List.copyOf(failureRules);
        this.retryPolicy = normalizePolicy(retryPolicy);
    }

    /**
     * Jackson factory. Accepts the pre-rules fields ({@code reloginStatusCodes},
     * {@code reloginBodyContains}, {@code resendAfterRelogin}) from older documents and converts
     * them into failure rules; those fields are never written back.
     */
    @JsonCreator
    static SessionAuthConfig fromJson(
            @JsonProperty("mainAuthFlowId") String mainAuthFlowId,
            @JsonProperty("ttlSeconds") long ttlSeconds,
            @JsonProperty("triggerCriteria") List<SessionScopeCriterion> triggerCriteria,
            @JsonProperty("failureRules") List<AuthFailureRule> failureRules,
            @JsonProperty("retryPolicy") AuthRetryPolicy retryPolicy,
            @JsonProperty("reloginStatusCodes") String legacyStatusCodes,
            @JsonProperty("reloginBodyContains") String legacyBodyContains,
            @JsonProperty("resendAfterRelogin") Boolean legacyResend) {
        List<AuthFailureRule> rules = failureRules != null
                ? failureRules
                : legacyRules(legacyStatusCodes, legacyBodyContains);
        return new SessionAuthConfig(mainAuthFlowId, ttlSeconds, triggerCriteria, rules, retryPolicy);
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
     * Returns the rules that mark a live response as "session lost".
     *
     * @return immutable rules
     */
    public List<AuthFailureRule> failureRules() {
        return failureRules;
    }

    /**
     * Returns what happens when a failure rule matches.
     *
     * @return {@link AuthRetryPolicy#NO_RETRY} or {@link AuthRetryPolicy#LOGIN_AND_RETRY_ONCE}
     */
    public AuthRetryPolicy retryPolicy() {
        return retryPolicy;
    }

    /**
     * Returns whether any enabled failure rule is configured.
     *
     * @return true when live re-login can trigger
     */
    @JsonIgnore
    public boolean hasEnabledFailureRules() {
        return failureRules.stream().anyMatch(AuthFailureRule::enabled);
    }

    /**
     * Returns a copy with different live re-login rules and policy.
     *
     * @param rules failure rules
     * @param policy retry policy
     * @return updated config
     */
    public SessionAuthConfig withFailureHandling(List<AuthFailureRule> rules, AuthRetryPolicy policy) {
        return new SessionAuthConfig(mainAuthFlowId, ttlSeconds, triggerCriteria, rules, policy);
    }

    private static AuthRetryPolicy normalizePolicy(AuthRetryPolicy policy) {
        return policy == AuthRetryPolicy.NO_RETRY ? AuthRetryPolicy.NO_RETRY : DEFAULT_RETRY_POLICY;
    }

    private static List<AuthFailureRule> legacyRules(String statusCodes, String bodyContains) {
        ArrayList<AuthFailureRule> rules = new ArrayList<>();
        if (statusCodes != null) {
            java.util.LinkedHashSet<String> codes = new java.util.LinkedHashSet<>();
            for (String part : statusCodes.split("[,\\s]+")) {
                if (part.strip().matches("[1-5]\\d\\d")) {
                    codes.add(part.strip());
                }
            }
            codes.forEach(code -> rules.add(new AuthFailureRule("relogin-status-" + code, "Status " + code,
                    true, AuthRuleType.STATUS_CODE, code, "")));
        }
        if (bodyContains != null && !bodyContains.isBlank()) {
            rules.add(new AuthFailureRule("relogin-body", "Body contains", true, AuthRuleType.BODY_CONTAINS,
                    bodyContains.strip(), ""));
        }
        return rules;
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
                && retryPolicy == that.retryPolicy
                && Objects.equals(mainAuthFlowId, that.mainAuthFlowId)
                && Objects.equals(triggerCriteria, that.triggerCriteria)
                && Objects.equals(failureRules, that.failureRules);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(mainAuthFlowId, ttlSeconds, triggerCriteria, failureRules, retryPolicy);
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
