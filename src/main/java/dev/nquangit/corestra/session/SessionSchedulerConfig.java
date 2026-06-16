package dev.nquangit.corestra.session;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Configures background session authentication refresh.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class SessionSchedulerConfig {
    private final boolean enabled;
    private final long intervalSeconds;
    private final boolean runAtStartup;

    /**
     * Creates scheduler config.
     *
     * @param enabled whether scheduler refresh is enabled
     * @param intervalSeconds refresh interval in seconds
     * @param runAtStartup whether to run immediately at extension startup
     */
    @JsonCreator
    public SessionSchedulerConfig(
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("intervalSeconds") long intervalSeconds,
            @JsonProperty("runAtStartup") boolean runAtStartup) {
        this.enabled = enabled;
        this.intervalSeconds = Math.max(1L, intervalSeconds == 0L ? 300L : intervalSeconds);
        this.runAtStartup = runAtStartup;
    }

    /**
     * Returns disabled default scheduler config.
     *
     * @return default config
     */
    public static SessionSchedulerConfig disabled() {
        return new SessionSchedulerConfig(false, 300L, false);
    }

    /**
     * Returns whether scheduler refresh is enabled.
     *
     * @return true when enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the refresh interval.
     *
     * @return interval seconds
     */
    public long intervalSeconds() {
        return intervalSeconds;
    }

    /**
     * Returns whether to run at startup.
     *
     * @return true when startup run is enabled
     */
    public boolean runAtStartup() {
        return runAtStartup;
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
        if (!(other instanceof SessionSchedulerConfig that)) {
            return false;
        }
        return enabled == that.enabled
                && intervalSeconds == that.intervalSeconds
                && runAtStartup == that.runAtStartup;
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(enabled, intervalSeconds, runAtStartup);
    }
}
