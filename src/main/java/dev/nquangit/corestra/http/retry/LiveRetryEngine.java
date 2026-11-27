package dev.nquangit.corestra.http.retry;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Transport-neutral retry loop for live traffic. Every response (the original and each resend) is
 * checked against the profile's ordered rules: no match delivers it, a STOP rule delivers it and
 * ends the loop, a RETRY rule waits and resends within the rule's and profile's limits.
 */
public final class LiveRetryEngine {
    private final Clock clock;
    private final RetryWaiter waiter;
    private final HostCooldownGate gate;
    private final RetryRuleEvaluator evaluator = new RetryRuleEvaluator();

    /**
     * Creates an engine.
     *
     * @param clock  time source
     * @param waiter waits between attempts
     * @param gate   shared per-host cooldown
     */
    public LiveRetryEngine(Clock clock, RetryWaiter waiter, HostCooldownGate gate) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.waiter = Objects.requireNonNull(waiter, "waiter");
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    /** Why the loop stopped. */
    public enum Stop {
        /** No rule matched the original response; nothing was resent. */
        NO_MATCH,
        /** A STOP rule matched (the original or a resent response). */
        STOPPED_BY_RULE,
        /** A resend produced a response no rule matches. */
        RECOVERED,
        /** The retry limit was reached while a RETRY rule still matched. */
        RETRIES_EXHAUSTED,
        /** The next wait would exceed the profile's per-request budget. */
        BUDGET_EXCEEDED,
        /** The wait was aborted (extension unloading). */
        ABORTED,
        /** A resend threw or returned no response; the last response received is kept. */
        SEND_FAILED
    }

    /**
     * Result of a retry run.
     *
     * @param response     response to deliver to the Burp tool
     * @param resends      resends that produced a response
     * @param waitedMillis total time spent waiting
     * @param finalStatus  status of {@code response}
     * @param stop         why the loop stopped
     * @param rule         rule matching the delivered response, or {@code null} when none matches
     * @param triggerRule  RETRY rule that caused the first resend, or {@code null}
     * @param detail       failure detail for {@link Stop#SEND_FAILED}, otherwise empty
     * @param <R>          response type
     */
    public record Result<R>(R response, int resends, long waitedMillis, int finalStatus, Stop stop, RetryRule rule,
                            RetryRule triggerRule, String detail) {
        /** Returns whether the delivered response differs from the original. */
        public boolean replaced() { return resends > 0; }
    }

    /**
     * Runs the retry loop.
     *
     * @param profile profile chosen when the request was admitted
     * @param hostKey host key for the cooldown gate
     * @param initial the response Burp received
     * @param view    maps a response to what rules read
     * @param resend  resends the original request; may throw or return {@code null}
     * @param <R>     response type
     * @return outcome, never {@code null}
     */
    public <R> Result<R> execute(RetryProfile profile, String hostKey, R initial, Function<R, RetryMessage> view,
                                 Supplier<R> resend) {
        RetryMessage message = view.apply(initial);
        Optional<RetryRule> match = evaluator.firstMatch(profile.rules(), message);
        if (match.isEmpty()) {
            return new Result<>(initial, 0, 0L, message.status(), Stop.NO_MATCH, null, null, "");
        }
        if (match.get().action() == RetryRule.Action.STOP) {
            return new Result<>(initial, 0, 0L, message.status(), Stop.STOPPED_BY_RULE, match.get(), null, "");
        }
        RetryRule trigger = match.get();
        RetryRule rule = trigger;
        R current = initial;
        int resends = 0;
        long waited = 0L;
        while (true) {
            int limit = rule.maxRetries() != null ? rule.maxRetries() : profile.maxRetries();
            if (resends >= limit) {
                return new Result<>(current, resends, waited, message.status(), Stop.RETRIES_EXHAUSTED, rule, trigger, "");
            }
            long delay = delayMillis(profile, rule, resends, message.responseHeader("Retry-After").orElse(null));
            // Other requests to this host should hold off whether or not this one keeps retrying.
            if (profile.hostCooldown() && delay > 0) gate.block(hostKey, clock.millis() + delay);
            if (waited + delay > profile.maxTotalWaitMillis()) {
                return new Result<>(current, resends, waited, message.status(), Stop.BUDGET_EXCEEDED, rule, trigger, "");
            }
            if (!waiter.await(delay)) {
                return new Result<>(current, resends, waited, message.status(), Stop.ABORTED, rule, trigger, "");
            }
            waited += delay;
            R next;
            try {
                next = resend.get();
            } catch (RuntimeException failure) {
                String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                return new Result<>(current, resends, waited, message.status(), Stop.SEND_FAILED, rule, trigger, detail);
            }
            if (next == null) {
                return new Result<>(current, resends, waited, message.status(), Stop.SEND_FAILED, rule, trigger, "no response");
            }
            resends++;
            current = next;
            message = view.apply(next);
            match = evaluator.firstMatch(profile.rules(), message);
            if (match.isEmpty() || match.get().action() == RetryRule.Action.STOP) {
                if (profile.hostCooldown()) gate.release(hostKey);
                return new Result<>(current, resends, waited, message.status(),
                        match.isEmpty() ? Stop.RECOVERED : Stop.STOPPED_BY_RULE, match.orElse(null), trigger, "");
            }
            rule = match.get();
        }
    }

    /**
     * Returns the wait before the next resend: the rule's fixed delay, else the server's
     * {@code Retry-After} when allowed, else capped exponential backoff.
     *
     * @param profile    profile
     * @param rule       matching RETRY rule
     * @param attempt    resends already made (0-based)
     * @param retryAfter raw {@code Retry-After} value, may be {@code null}
     * @return delay in milliseconds
     */
    public long delayMillis(RetryProfile profile, RetryRule rule, int attempt, String retryAfter) {
        if (rule.delayMillis() != null) return rule.delayMillis();
        if (profile.respectRetryAfter()) {
            OptionalLong serverDelay = RetryAfterParser.delayMillis(retryAfter, Instant.ofEpochMilli(clock.millis()));
            if (serverDelay.isPresent()) return serverDelay.getAsLong();
        }
        double backoff = profile.baseDelayMillis() * Math.pow(profile.backoffMultiplier(), attempt);
        return (long) Math.min(backoff, profile.maxDelayMillis());
    }
}
