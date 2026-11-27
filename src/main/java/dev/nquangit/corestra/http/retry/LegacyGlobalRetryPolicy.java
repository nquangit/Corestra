package dev.nquangit.corestra.http.retry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.nquangit.corestra.flow.control.StepConditionOperator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only shape of the first Global Retry format (project settings key {@code globalRetry}: one
 * flat policy with status codes and a response regex). Only used to migrate stored projects into a
 * {@link RetryConfig}; never written.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LegacyGlobalRetryPolicy(boolean enabled, boolean burpScopeOnly, String hostPattern,
                                      List<String> toolTypes, List<Integer> statusCodes, String responsePattern,
                                      List<String> methods, int maxRetries, long baseDelayMillis, long maxDelayMillis,
                                      double backoffMultiplier, boolean respectRetryAfter, int maxTotalWaitSeconds,
                                      boolean hostCooldown) {

    /**
     * Converts the legacy policy into one "Default" profile with a "Status codes" and/or a
     * "Response regex" retry rule. Out-of-range numbers are clamped; invalid values fall back to the
     * defaults so a stored project never fails to load.
     *
     * @return equivalent configuration
     */
    public RetryConfig toConfig() {
        RetryProfile fallback = RetryProfile.standard("Default");
        List<RetryRule> rules = new ArrayList<>();
        List<Integer> statuses = Objects.requireNonNullElse(statusCodes, List.<Integer>of()).stream()
                .filter(Objects::nonNull).filter(status -> status >= 100 && status <= 599).distinct().toList();
        if (!statuses.isEmpty()) {
            rules.add(RetryRule.of("Status codes", RetryRule.Action.RETRY, List.of(RetryCondition.of(
                    RetryConditionSource.STATUS, "", StepConditionOperator.IN,
                    String.join(", ", statuses.stream().map(String::valueOf).toList())))));
        }
        if (responsePattern != null && !responsePattern.isBlank()) {
            try {
                rules.add(RetryRule.of("Response regex", RetryRule.Action.RETRY, List.of(new RetryCondition(true, 1,
                        RetryConditionSource.RESPONSE_FULL_MESSAGE, "", StepConditionOperator.MATCHES_REGEX,
                        responsePattern.strip(), true, false))));
            } catch (IllegalArgumentException invalidRegex) {
                // Keep loading; the broken pattern is dropped.
            }
        }
        if (rules.isEmpty()) rules = fallback.rules();
        List<String> tools = Objects.requireNonNullElse(toolTypes, List.<String>of()).stream()
                .filter(Objects::nonNull).map(String::strip).map(value -> value.toUpperCase(java.util.Locale.ROOT))
                .filter(Set.copyOf(RetryProfile.SUPPORTED_TOOLS)::contains).distinct().toList();
        if (tools.isEmpty()) tools = fallback.toolTypes();
        long base = clamp(baseDelayMillis, 0, RetryProfile.MAX_DELAY_MILLIS, fallback.baseDelayMillis());
        long max = clamp(maxDelayMillis, base, RetryProfile.MAX_DELAY_MILLIS, Math.max(base, fallback.maxDelayMillis()));
        RetryProfile profile;
        try {
            profile = new RetryProfile(null, "Default", true, tools, burpScopeOnly, hostPattern, "", methods, rules,
                    (int) clamp(maxRetries, 1, RetryProfile.MAX_RETRIES, fallback.maxRetries()), base, max,
                    backoffMultiplier >= 1.0 && backoffMultiplier <= 10.0 ? backoffMultiplier : fallback.backoffMultiplier(),
                    respectRetryAfter,
                    (int) clamp(maxTotalWaitSeconds, 1, RetryProfile.MAX_DELAY_MILLIS / 1000, fallback.maxTotalWaitSeconds()),
                    hostCooldown);
        } catch (IllegalArgumentException invalid) {
            profile = fallback.withRules(rules);
        }
        return new RetryConfig(enabled, List.of(profile));
    }

    private static long clamp(long value, long min, long max, long fallback) {
        return value < min || value > max ? fallback : value;
    }
}
