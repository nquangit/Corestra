package dev.nquangit.corestra.http.retry;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Remembers hosts that are currently rate-limited so other live requests to the same host wait
 * before being sent, instead of each hitting the limit and retrying on its own.
 */
public final class HostCooldownGate {
    private static final int MAX_HOSTS = 1_024;
    private final Clock clock;
    private final Map<String, Long> blockedUntil = new HashMap<>();

    /**
     * Creates a gate.
     *
     * @param clock time source
     */
    public HostCooldownGate(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Blocks a host until the given time; an existing later deadline is kept.
     *
     * @param host             host key, e.g. {@code example.com:443}
     * @param untilEpochMillis deadline
     */
    public synchronized void block(String host, long untilEpochMillis) {
        long now = clock.millis();
        if (untilEpochMillis <= now) return;
        if (blockedUntil.size() >= MAX_HOSTS) blockedUntil.values().removeIf(until -> until <= now);
        if (blockedUntil.size() >= MAX_HOSTS && !blockedUntil.containsKey(host)) return;
        blockedUntil.merge(host, untilEpochMillis, Math::max);
    }

    /**
     * Returns how long a new request to the host should wait.
     *
     * @param host host key
     * @return remaining cooldown in milliseconds, {@code 0} when the host is not blocked
     */
    public synchronized long remainingMillis(String host) {
        Long until = blockedUntil.get(host);
        if (until == null) return 0L;
        long remaining = until - clock.millis();
        if (remaining <= 0) {
            blockedUntil.remove(host);
            return 0L;
        }
        return remaining;
    }

    /**
     * Clears a host's cooldown after a request to it succeeded.
     *
     * @param host host key
     */
    public synchronized void release(String host) {
        blockedUntil.remove(host);
    }
}
