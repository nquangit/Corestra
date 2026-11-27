package dev.nquangit.corestra.http.retry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.nquangit.corestra.flow.control.StepConditionOperator;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A set of retry rules for the requests it covers (tools, Burp scope, host, path, methods), plus
 * the waiting limits used when a rule says to retry. Profiles are tried in order and the first
 * enabled profile that covers a request is used, so host- or path-specific profiles go above a
 * catch-all one.
 *
 * @param id                  stable identifier
 * @param name                display name
 * @param enabled             disabled profiles are skipped
 * @param toolTypes           Burp tools covered (see {@link #SUPPORTED_TOOLS})
 * @param burpScopeOnly       only cover requests in Burp's target scope
 * @param hostPattern         regex the host must fully match, case-insensitive (blank = any)
 * @param pathPattern         regex searched in the path incl. query (blank = any)
 * @param methods             methods covered (empty = any)
 * @param rules               ordered rules; first match decides
 * @param maxRetries          resends per request ({@code 1..10})
 * @param baseDelayMillis     first backoff delay
 * @param maxDelayMillis      cap for one backoff delay
 * @param backoffMultiplier   backoff growth ({@code 1..10})
 * @param respectRetryAfter   use the server's Retry-After when no rule delay is set
 * @param maxTotalWaitSeconds per-request wait budget ({@code 1..300})
 * @param hostCooldown        hold other covered requests to a host while it is being retried
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RetryProfile(String id, String name, boolean enabled, List<String> toolTypes, boolean burpScopeOnly,
                           String hostPattern, String pathPattern, List<String> methods, List<RetryRule> rules,
                           int maxRetries, long baseDelayMillis, long maxDelayMillis, double backoffMultiplier,
                           boolean respectRetryAfter, int maxTotalWaitSeconds, boolean hostCooldown) {

    /** Burp tools whose live traffic may be retried. Extension traffic is never retried. */
    public static final List<String> SUPPORTED_TOOLS = List.of("PROXY", "REPEATER", "INTRUDER", "SCANNER");
    /** Hard cap on resends per request. */
    public static final int MAX_RETRIES = 10;
    /** Hard cap on one delay and on the per-request budget. */
    public static final long MAX_DELAY_MILLIS = 300_000L;

    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

    /** Validates and normalizes the profile. */
    public RetryProfile {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.strip();
        name = name == null || name.isBlank() ? "Profile" : name.strip();
        toolTypes = Objects.requireNonNullElse(toolTypes, List.<String>of()).stream().filter(Objects::nonNull)
                .map(value -> value.strip().toUpperCase(Locale.ROOT)).filter(value -> !value.isEmpty()).distinct().toList();
        if (!Set.copyOf(SUPPORTED_TOOLS).containsAll(toolTypes)) {
            throw new IllegalArgumentException("Profile '" + name + "': unsupported tool " + toolTypes);
        }
        hostPattern = hostPattern == null ? "" : hostPattern.strip();
        pathPattern = pathPattern == null ? "" : pathPattern.strip();
        methods = Objects.requireNonNullElse(methods, List.<String>of()).stream().filter(Objects::nonNull)
                .map(value -> value.strip().toUpperCase(Locale.ROOT)).filter(value -> !value.isEmpty()).distinct().toList();
        for (String method : methods) {
            if (!method.matches("[A-Z][A-Z0-9_-]*")) {
                throw new IllegalArgumentException("Profile '" + name + "': invalid HTTP method " + method);
            }
        }
        rules = List.copyOf(Objects.requireNonNullElse(rules, List.<RetryRule>of()).stream().filter(Objects::nonNull).toList());
        if (maxRetries < 1 || maxRetries > MAX_RETRIES) {
            throw new IllegalArgumentException("Profile '" + name + "': max retries must be 1-" + MAX_RETRIES);
        }
        if (baseDelayMillis < 0 || baseDelayMillis > MAX_DELAY_MILLIS) {
            throw new IllegalArgumentException("Profile '" + name + "': base delay must be 0-" + MAX_DELAY_MILLIS + " ms");
        }
        if (maxDelayMillis < baseDelayMillis || maxDelayMillis > MAX_DELAY_MILLIS) {
            throw new IllegalArgumentException("Profile '" + name + "': max delay must be between base delay and "
                    + MAX_DELAY_MILLIS + " ms");
        }
        if (!(backoffMultiplier >= 1.0 && backoffMultiplier <= 10.0)) {
            throw new IllegalArgumentException("Profile '" + name + "': backoff multiplier must be 1-10");
        }
        if (maxTotalWaitSeconds < 1 || maxTotalWaitSeconds > MAX_DELAY_MILLIS / 1000) {
            throw new IllegalArgumentException("Profile '" + name + "': max total wait must be 1-"
                    + MAX_DELAY_MILLIS / 1000 + " s");
        }
        checkRegex(hostPattern, "host regex", name);
        checkRegex(pathPattern, "path regex", name);
        if (enabled && toolTypes.isEmpty()) {
            throw new IllegalArgumentException("Profile '" + name + "': select at least one tool");
        }
    }

    /**
     * Returns a profile covering Proxy/Repeater/Intruder in Burp scope with one rule retrying 429/503.
     *
     * @param name profile name
     * @return profile
     */
    public static RetryProfile standard(String name) {
        return new RetryProfile(null, name, true, List.of("PROXY", "REPEATER", "INTRUDER"), true, "", "", List.of(),
                List.of(RetryRule.of("Rate limited (429/503)", RetryRule.Action.RETRY, List.of(
                        RetryCondition.of(RetryConditionSource.STATUS, "", StepConditionOperator.IN, "429, 503")))),
                3, 1_000, 30_000, 2.0, true, 60, true);
    }

    /** Returns the per-request wait budget in milliseconds. */
    public long maxTotalWaitMillis() {
        return maxTotalWaitSeconds * 1_000L;
    }

    /**
     * Returns whether this profile covers a live request.
     *
     * @param request request facts
     * @return {@code true} when enabled and every scope filter matches
     */
    public boolean covers(RetryRequestContext request) {
        if (!enabled || request == null) return false;
        if (request.toolType() == null || !toolTypes.contains(request.toolType().toUpperCase(Locale.ROOT))) return false;
        if (burpScopeOnly && !request.inBurpScope()) return false;
        if (!methods.isEmpty() && (request.method() == null
                || !methods.contains(request.method().toUpperCase(Locale.ROOT)))) return false;
        if (!hostPattern.isEmpty() && (request.host() == null
                || !pattern("(?i)" + hostPattern).matcher(request.host()).matches())) return false;
        return pathPattern.isEmpty() || (request.path() != null && pattern(pathPattern).matcher(request.path()).find());
    }

    /** Returns a copy with other rules. */
    public RetryProfile withRules(List<RetryRule> newRules) {
        return new RetryProfile(id, name, enabled, toolTypes, burpScopeOnly, hostPattern, pathPattern, methods, newRules,
                maxRetries, baseDelayMillis, maxDelayMillis, backoffMultiplier, respectRetryAfter, maxTotalWaitSeconds,
                hostCooldown);
    }

    /** Returns a copy with a fresh id and a new name (for duplicating). */
    public RetryProfile copyNamed(String newName) {
        return new RetryProfile(null, newName, enabled, toolTypes, burpScopeOnly, hostPattern, pathPattern, methods,
                rules.stream().map(RetryRule::withNewId).toList(), maxRetries, baseDelayMillis, maxDelayMillis,
                backoffMultiplier, respectRetryAfter, maxTotalWaitSeconds, hostCooldown);
    }

    /** Returns a one-line description of what the profile covers. */
    public String scopeSummary() {
        StringBuilder text = new StringBuilder(String.join("/", toolTypes));
        text.append(burpScopeOnly ? ", in scope" : ", any scope");
        if (!hostPattern.isEmpty()) text.append(", host ~ ").append(hostPattern);
        if (!pathPattern.isEmpty()) text.append(", path ~ ").append(pathPattern);
        if (!methods.isEmpty()) text.append(", ").append(String.join("/", methods));
        long active = rules.stream().filter(RetryRule::enabled).count();
        return text.append(", ").append(active).append(active == 1 ? " rule" : " rules").toString();
    }

    static Pattern pattern(String regex) {
        Pattern cached = PATTERNS.get(regex);
        if (cached != null) return cached;
        if (PATTERNS.size() >= 256) PATTERNS.clear();
        return PATTERNS.computeIfAbsent(regex, Pattern::compile);
    }

    private static void checkRegex(String regex, String label, String profile) {
        if (regex.isEmpty()) return;
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException exception) {
            throw new IllegalArgumentException("Profile '" + profile + "': invalid " + label + ": " + exception.getDescription());
        }
    }
}
