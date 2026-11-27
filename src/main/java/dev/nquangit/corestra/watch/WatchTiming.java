package dev.nquangit.corestra.watch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * User-tunable waiting behaviour. Values are clamped to safe bounds; non-positive values
 * fall back to defaults (except {@code settleMaxMillis}, where 0 disables re-capturing).
 *
 * @param probeTimeoutMillis   maximum time for one probe request (also bounds the BEFORE capture)
 * @param settleIntervalMillis delay between AFTER re-captures while waiting for the state to settle
 * @param settleStableCount    consecutive identical AFTER captures needed to call it settled (1 = take the first)
 * @param settleMaxMillis      maximum time spent re-capturing AFTER before reporting "unsettled"
 * @param flowWaitMillis       when the watched request is an auto-trigger, maximum wait for downstream steps
 */
public record WatchTiming(long probeTimeoutMillis, long settleIntervalMillis, int settleStableCount,
                          long settleMaxMillis, long flowWaitMillis) {
    /**
     * Clamps values.
     *
     * @param probeTimeoutMillis   probe timeout
     * @param settleIntervalMillis settle interval
     * @param settleStableCount    stable count
     * @param settleMaxMillis      settle maximum
     * @param flowWaitMillis       downstream flow wait
     */
    @JsonCreator
    public WatchTiming(@JsonProperty("probeTimeoutMillis") long probeTimeoutMillis,
                       @JsonProperty("settleIntervalMillis") long settleIntervalMillis,
                       @JsonProperty("settleStableCount") int settleStableCount,
                       @JsonProperty("settleMaxMillis") long settleMaxMillis,
                       @JsonProperty("flowWaitMillis") long flowWaitMillis) {
        this.probeTimeoutMillis = probeTimeoutMillis <= 0 ? 5_000 : Math.max(500, Math.min(60_000, probeTimeoutMillis));
        this.settleIntervalMillis = settleIntervalMillis <= 0 ? 700 : Math.max(100, Math.min(30_000, settleIntervalMillis));
        this.settleStableCount = settleStableCount <= 0 ? 2 : Math.min(settleStableCount, 10);
        this.settleMaxMillis = Math.max(0, Math.min(300_000, settleMaxMillis));
        this.flowWaitMillis = flowWaitMillis <= 0 ? 60_000 : Math.max(1_000, Math.min(600_000, flowWaitMillis));
    }

    /**
     * Returns the defaults.
     *
     * @return default timing
     */
    public static WatchTiming defaults() {
        return new WatchTiming(5_000, 700, 2, 6_000, 60_000);
    }
}
