package dev.nquangit.corestra.http.retry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Project-wide retry configuration: a master switch and ordered {@link RetryProfile}s.
 *
 * @param enabled  master switch
 * @param profiles ordered profiles; the first enabled profile covering a request is used
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RetryConfig(boolean enabled, List<RetryProfile> profiles) {

    /** Validates and normalizes the configuration. */
    public RetryConfig {
        profiles = List.copyOf(Objects.requireNonNullElse(profiles, List.<RetryProfile>of()).stream()
                .filter(Objects::nonNull).toList());
        Set<String> ids = new HashSet<>();
        for (RetryProfile profile : profiles) {
            if (!ids.add(profile.id())) throw new IllegalArgumentException("Duplicate retry profile id " + profile.id());
        }
        if (enabled && profiles.stream().noneMatch(RetryProfile::enabled)) {
            throw new IllegalArgumentException("Enable at least one retry profile");
        }
    }

    /**
     * Returns the default configuration: disabled, one standard profile retrying 429/503. Ids are
     * fixed so two default configurations are equal.
     */
    public static RetryConfig defaults() {
        RetryProfile standard = RetryProfile.standard("Default");
        RetryRule rule = standard.rules().get(0);
        RetryRule fixedRule = new RetryRule("default-rate-limit", rule.name(), rule.enabled(), rule.action(),
                rule.conditions(), rule.delayMillis(), rule.maxRetries(), rule.highlight());
        return new RetryConfig(false, List.of(new RetryProfile("default", standard.name(), standard.enabled(),
                standard.toolTypes(), standard.burpScopeOnly(), standard.hostPattern(), standard.pathPattern(),
                standard.methods(), List.of(fixedRule), standard.maxRetries(), standard.baseDelayMillis(),
                standard.maxDelayMillis(), standard.backoffMultiplier(), standard.respectRetryAfter(),
                standard.maxTotalWaitSeconds(), standard.hostCooldown())));
    }

    /**
     * Returns the profile that handles a request.
     *
     * @param request request facts
     * @return first enabled covering profile, or empty when disabled or nothing covers it
     */
    public Optional<RetryProfile> select(RetryRequestContext request) {
        if (!enabled) return Optional.empty();
        return profiles.stream().filter(profile -> profile.covers(request)).findFirst();
    }

    /** Returns a short summary for status lines. */
    public String summary() {
        long active = profiles.stream().filter(RetryProfile::enabled).count();
        return (enabled ? "Enabled" : "Disabled") + " — " + active + " of " + profiles.size() + " profile"
                + (profiles.size() == 1 ? "" : "s") + " active";
    }
}
