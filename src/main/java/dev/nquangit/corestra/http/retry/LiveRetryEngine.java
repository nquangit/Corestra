package dev.nquangit.corestra.http.retry;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Transport-neutral retry loop for live traffic. The caller supplies how to observe a response and
 * how to resend the original request; the engine decides whether, when and how often to resend.
 */
public final class LiveRetryEngine {
    private final Clock clock;
    private final RetryWaiter waiter;
    private final HostCooldownGate gate;

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
        /** The first response did not match a retry condition; nothing was resent. */
        NOT_RETRYABLE,
        /** A resend produced a response that no longer matches a retry condition. */
        RECOVERED,
        /** {@code maxRetries} resends were made and the last response still matched. */
        RETRIES_EXHAUSTED,
        /** The next wait would exceed the per-request wait budget. */
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
     * @param resends      number of resends that produced a response
     * @param waitedMillis total time spent waiting
     * @param finalStatus  status of {@code response}
     * @param stop         why the loop stopped
     * @param detail       failure detail for {@link Stop#SEND_FAILED}, otherwise empty
     * @param <R>          response type
     */
    public record Result<R>(R response, int resends, long waitedMillis, int finalStatus, Stop stop, String detail) {
        /** Returns whether the delivered response differs from the original. */
        public boolean replaced() { return resends > 0; }
    }

    /**
     * Runs the retry loop.
     *
     * @param policy  policy snapshot taken when the request was admitted
     * @param hostKey host key for the cooldown gate
     * @param initial the response Burp received
     * @param observe maps a response to what the policy checks
     * @param resend  resends the original request; may throw or return {@code null}
     * @param <R>     response type
     * @return outcome, never {@code null}
     */
    public <R> Result<R> execute(GlobalRetryPolicy policy, String hostKey, R initial,
                                 Function<R, RetryObservation> observe, Supplier<R> resend) {
        RetryObservation observation = observe.apply(initial);
        if (!policy.isRetryable(observation.status(), observation.fullMessage())) {
            return new Result<>(initial, 0, 0L, observation.status(), Stop.NOT_RETRYABLE, "");
        }
        R current = initial;
        int resends = 0;
        long waited = 0L;
        while (true) {
            if (resends >= policy.maxRetries()) {
                return new Result<>(current, resends, waited, observation.status(), Stop.RETRIES_EXHAUSTED, "");
            }
            long delay = delayMillis(policy, resends, observation.retryAfter());
            // Other requests to this host should hold off whether or not this one keeps retrying.
            block(policy, hostKey, delay);
            if (waited + delay > policy.maxTotalWaitMillis()) {
                return new Result<>(current, resends, waited, observation.status(), Stop.BUDGET_EXCEEDED, "");
            }
            if (!waiter.await(delay)) {
                return new Result<>(current, resends, waited, observation.status(), Stop.ABORTED, "");
            }
            waited += delay;
            R next;
            try {
                next = resend.get();
            } catch (RuntimeException failure) {
                String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                return new Result<>(current, resends, waited, observation.status(), Stop.SEND_FAILED, detail);
            }
            if (next == null) {
                return new Result<>(current, resends, waited, observation.status(), Stop.SEND_FAILED, "no response");
            }
            resends++;
            current = next;
            observation = observe.apply(next);
            if (!policy.isRetryable(observation.status(), observation.fullMessage())) {
                if (policy.hostCooldown()) gate.release(hostKey);
                return new Result<>(current, resends, waited, observation.status(), Stop.RECOVERED, "");
            }
        }
    }

    /**
     * Returns the wait before the next resend: the server's {@code Retry-After} when allowed and
     * present, otherwise capped exponential backoff.
     *
     * @param policy     policy
     * @param attempt    resends already made (0-based)
     * @param retryAfter raw {@code Retry-After} value, may be {@code null}
     * @return delay in milliseconds
     */
    public long delayMillis(GlobalRetryPolicy policy, int attempt, String retryAfter) {
        if (policy.respectRetryAfter()) {
            OptionalLong serverDelay = RetryAfterParser.delayMillis(retryAfter, Instant.ofEpochMilli(clock.millis()));
            if (serverDelay.isPresent()) return serverDelay.getAsLong();
        }
        double backoff = policy.baseDelayMillis() * Math.pow(policy.backoffMultiplier(), attempt);
        return (long) Math.min(backoff, policy.maxDelayMillis());
    }

    private void block(GlobalRetryPolicy policy, String hostKey, long delay) {
        if (policy.hostCooldown() && delay > 0) gate.block(hostKey, clock.millis() + delay);
    }
}
