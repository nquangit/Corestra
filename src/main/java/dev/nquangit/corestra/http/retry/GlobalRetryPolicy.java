package dev.nquangit.corestra.http.retry;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Project-wide retry policy applied to live Burp traffic (Proxy, Repeater, Intruder, Scanner) when a
 * response looks rate-limited or blocked, e.g. by a WAF.
 *
 * <p>A response is retryable when its status is in {@link #statusCodes()} <em>or</em> the
 * {@link #responsePattern()} regex matches the full response message (status line, headers, blank
 * line, body). Retries wait using {@code Retry-After} when present and allowed, otherwise exponential
 * backoff {@code baseDelayMillis * backoffMultiplier^attempt} capped at {@code maxDelayMillis}. A
 * single request never waits longer than {@code maxTotalWaitSeconds} in total.
 *
 * @param enabled             whether the policy is active
 * @param burpScopeOnly       only retry requests inside Burp's target scope
 * @param hostPattern         optional regex the request host must fully match (blank = any host)
 * @param toolTypes           Burp tools whose traffic is retried (see {@link #SUPPORTED_TOOLS})
 * @param statusCodes         response status codes that trigger a retry
 * @param responsePattern     optional regex over the full response message that triggers a retry
 * @param methods             HTTP methods to retry (empty = any method)
 * @param maxRetries          maximum resends per request ({@code 1..10})
 * @param baseDelayMillis     first backoff delay ({@code 0..300000})
 * @param maxDelayMillis      cap for a single backoff delay ({@code baseDelayMillis..300000})
 * @param backoffMultiplier   growth factor between attempts ({@code 1..10})
 * @param respectRetryAfter   honor the server's {@code Retry-After} header
 * @param maxTotalWaitSeconds total wait budget per request ({@code 1..300})
 * @param hostCooldown        pause other matching requests to the same host while it is rate-limited
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record GlobalRetryPolicy(boolean enabled, boolean burpScopeOnly, String hostPattern,
                                List<String> toolTypes, List<Integer> statusCodes, String responsePattern,
                                List<String> methods, int maxRetries, long baseDelayMillis, long maxDelayMillis,
                                double backoffMultiplier, boolean respectRetryAfter, int maxTotalWaitSeconds,
                                boolean hostCooldown) {

    /** Burp tools whose live traffic may be retried. Extension (Corestra) traffic is never retried here. */
    public static final List<String> SUPPORTED_TOOLS = List.of("PROXY", "REPEATER", "INTRUDER", "SCANNER");
    /** Hard cap on resends per request. */
    public static final int MAX_RETRIES = 10;
    /** Hard cap on a single delay and on the per-request wait budget. */
    public static final long MAX_DELAY_MILLIS = 300_000L;

    /** Validates and normalizes the policy. */
    public GlobalRetryPolicy {
        hostPattern = hostPattern == null ? "" : hostPattern.strip();
        responsePattern = responsePattern == null ? "" : responsePattern.strip();
        toolTypes = Objects.requireNonNullElse(toolTypes, List.<String>of()).stream()
                .filter(Objects::nonNull).map(value -> value.strip().toUpperCase(Locale.ROOT))
                .filter(value -> !value.isEmpty()).distinct().toList();
        if (!Set.copyOf(SUPPORTED_TOOLS).containsAll(toolTypes)) {
            throw new IllegalArgumentException("Unsupported tool for global retry: " + toolTypes);
        }
        statusCodes = Objects.requireNonNullElse(statusCodes, List.<Integer>of()).stream()
                .filter(Objects::nonNull).distinct().sorted().toList();
        for (int status : statusCodes) {
            if (status < 100 || status > 599) throw new IllegalArgumentException("Status codes must be 100-599");
        }
        methods = Objects.requireNonNullElse(methods, List.<String>of()).stream()
                .filter(Objects::nonNull).map(value -> value.strip().toUpperCase(Locale.ROOT))
                .filter(value -> !value.isEmpty()).distinct().toList();
        for (String method : methods) {
            if (!method.matches("[A-Z][A-Z0-9_-]*")) throw new IllegalArgumentException("Invalid HTTP method: " + method);
        }
        if (maxRetries < 1 || maxRetries > MAX_RETRIES) {
            throw new IllegalArgumentException("Max retries must be between 1 and " + MAX_RETRIES);
        }
        if (baseDelayMillis < 0 || baseDelayMillis > MAX_DELAY_MILLIS) {
            throw new IllegalArgumentException("Base delay must be between 0 and " + MAX_DELAY_MILLIS + " ms");
        }
        if (maxDelayMillis < baseDelayMillis || maxDelayMillis > MAX_DELAY_MILLIS) {
            throw new IllegalArgumentException("Max delay must be between the base delay and " + MAX_DELAY_MILLIS + " ms");
        }
        if (!(backoffMultiplier >= 1.0 && backoffMultiplier <= 10.0)) {
            throw new IllegalArgumentException("Backoff multiplier must be between 1 and 10");
        }
        if (maxTotalWaitSeconds < 1 || maxTotalWaitSeconds > MAX_DELAY_MILLIS / 1000) {
            throw new IllegalArgumentException("Max total wait must be between 1 and " + MAX_DELAY_MILLIS / 1000 + " seconds");
        }
        compile(hostPattern, "Host pattern");
        compile(responsePattern, "Response pattern");
        if (enabled) {
            if (toolTypes.isEmpty()) throw new IllegalArgumentException("Select at least one tool for global retry");
            if (statusCodes.isEmpty() && responsePattern.isEmpty()) {
                throw new IllegalArgumentException("Add at least one status code or a response pattern");
            }
        }
    }

    /**
     * Returns the default, disabled policy: Proxy/Repeater/Intruder, Burp scope only, 429/503, up to
     * 3 retries with 1s→2s→4s backoff, {@code Retry-After} honored, 60s budget, host cooldown on.
     * Scanner is excluded by default because retry waits distort its time-based checks.
     *
     * @return default policy
     */
    public static GlobalRetryPolicy defaults() {
        return new GlobalRetryPolicy(false, true, "", List.of("PROXY", "REPEATER", "INTRUDER"),
                List.of(429, 503), "", List.of(), 3, 1_000, 30_000, 2.0, true, 60, true);
    }

    /** Returns the per-request wait budget in milliseconds. */
    public long maxTotalWaitMillis() {
        return maxTotalWaitSeconds * 1_000L;
    }

    /**
     * Returns whether a live request is covered by this policy (status/regex conditions excluded).
     *
     * @param toolType   Burp tool name, e.g. {@code PROXY}
     * @param method     HTTP method
     * @param host       request host
     * @param inBurpScope whether Burp considers the request in scope
     * @return {@code true} when retries may apply
     */
    public boolean coversRequest(String toolType, String method, String host, boolean inBurpScope) {
        if (!enabled) return false;
        if (toolType == null || !toolTypes.contains(toolType.toUpperCase(Locale.ROOT))) return false;
        if (burpScopeOnly && !inBurpScope) return false;
        if (!methods.isEmpty() && (method == null || !methods.contains(method.toUpperCase(Locale.ROOT)))) return false;
        return hostPattern.isEmpty() || (host != null
                && PatternCache.get("(?i)" + hostPattern).matcher(host).matches());
    }

    /** Returns whether checking the response requires the rendered full message. */
    public boolean needsFullMessage() {
        return !responsePattern.isEmpty();
    }

    /**
     * Returns whether an observed response should be retried.
     *
     * @param status      response status code
     * @param fullMessage rendered full response, may be {@code null} when {@link #needsFullMessage()} is false
     * @return {@code true} when the response matches a retry condition
     */
    public boolean isRetryable(int status, String fullMessage) {
        if (statusCodes.contains(status)) return true;
        return !responsePattern.isEmpty() && fullMessage != null
                && PatternCache.get(responsePattern).matcher(fullMessage).find();
    }

    /** Returns a short human-readable summary for the Project tab. */
    public String summary() {
        if (!enabled) return "Disabled";
        StringBuilder text = new StringBuilder("Enabled — ");
        text.append(String.join("/", toolTypes)).append(burpScopeOnly ? ", in-scope only" : ", all hosts");
        if (!statusCodes.isEmpty()) {
            text.append(", status ").append(String.join(",", statusCodes.stream().map(String::valueOf).toList()));
        }
        if (!responsePattern.isEmpty()) text.append(", response regex");
        text.append(", max ").append(maxRetries).append(" retr").append(maxRetries == 1 ? "y" : "ies")
                .append(", budget ").append(maxTotalWaitSeconds).append('s');
        if (hostCooldown) text.append(", host cooldown");
        return text.toString();
    }

    /** Live traffic evaluates the same few patterns on every response; compile each once. */
    private static final class PatternCache {
        private static final int LIMIT = 64;
        private static final java.util.Map<String, Pattern> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

        static Pattern get(String regex) {
            Pattern cached = CACHE.get(regex);
            if (cached != null) return cached;
            if (CACHE.size() >= LIMIT) CACHE.clear();
            return CACHE.computeIfAbsent(regex, Pattern::compile);
        }
    }

    private static void compile(String regex, String label) {
        if (regex.isEmpty()) return;
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException exception) {
            throw new IllegalArgumentException(label + " is not a valid regex: " + exception.getDescription());
        }
    }
}
