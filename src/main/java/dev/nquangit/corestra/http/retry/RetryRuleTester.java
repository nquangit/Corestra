package dev.nquangit.corestra.http.retry;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Explains, without sending anything, which profile and rule would handle a pasted response. Used
 * by the Test view of the Global Retry tab.
 */
public final class RetryRuleTester {
    private final RetryRuleEvaluator evaluator = new RetryRuleEvaluator();
    private final LiveRetryEngine delays;

    /**
     * Creates a tester.
     *
     * @param clock time source for HTTP-date Retry-After values
     */
    public RetryRuleTester(Clock clock) {
        this.delays = new LiveRetryEngine(clock, millis -> true, new HostCooldownGate(clock));
    }

    /**
     * Describes the decision for one exchange. Profiles are matched as if the master switch were on.
     *
     * @param profiles ordered profiles
     * @param request  request facts
     * @param message  response (and request) to evaluate
     * @return multi-line, plain-text explanation
     */
    public String explain(List<RetryProfile> profiles, RetryRequestContext request, RetryMessage message) {
        Optional<RetryProfile> profile = profiles.stream().filter(candidate -> candidate.covers(request)).findFirst();
        if (profile.isEmpty()) {
            return "No enabled profile covers this request (check tools, Burp scope, host, path and methods)."
                    + "\nThe response would be delivered unchanged.";
        }
        StringBuilder text = new StringBuilder("Profile: ").append(profile.get().name()).append('\n');
        Optional<RetryRule> rule = evaluator.firstMatch(profile.get().rules(), message);
        if (rule.isEmpty()) {
            return text.append("Rule: none matched; the response would be delivered unchanged.").toString();
        }
        RetryRule matched = rule.get();
        text.append("Rule: ").append(matched.name()).append(" -> ").append(matched.action()).append('\n');
        text.append("Because: ").append(matched.expression().replace("\n", " ")).append('\n');
        if (matched.action() == RetryRule.Action.STOP) {
            text.append("The response would be delivered unchanged");
        } else {
            long delay = delays.delayMillis(profile.get(), matched, 0, message.responseHeader("Retry-After").orElse(null));
            int limit = matched.maxRetries() != null ? matched.maxRetries() : profile.get().maxRetries();
            text.append("First retry after ").append(delay).append(" ms (")
                    .append(matched.delayMillis() != null ? "rule delay"
                            : profile.get().respectRetryAfter() && message.responseHeader("Retry-After").isPresent()
                            ? "Retry-After" : "backoff")
                    .append("), up to ").append(limit).append(limit == 1 ? " retry" : " retries")
                    .append(" within ").append(profile.get().maxTotalWaitSeconds()).append(" s");
        }
        if (matched.highlight() != RetryRule.Highlight.NONE) {
            text.append(", highlighted ").append(matched.highlight().name().toLowerCase(java.util.Locale.ROOT));
        }
        return text.append('.').toString();
    }
}
