package dev.nquangit.corestra.session;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Stores global session values for one session profile.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class SessionState {
    private final Map<String, String> values;
    private final long lastAuthenticatedAtEpochMillis;
    private final long expiresAtEpochMillis;
    private final long lastUpdatedAtEpochMillis;

    /**
     * Creates session state.
     *
     * @param values global session values keyed without the {@code session.} prefix
     * @param lastAuthenticatedAtEpochMillis last successful auth-flow timestamp
     * @param expiresAtEpochMillis expiration timestamp, or 0 when non-expiring
     * @param lastUpdatedAtEpochMillis last state update timestamp
     */
    @JsonCreator
    public SessionState(
            @JsonProperty("values") Map<String, String> values,
            @JsonProperty("lastAuthenticatedAtEpochMillis") long lastAuthenticatedAtEpochMillis,
            @JsonProperty("expiresAtEpochMillis") long expiresAtEpochMillis,
            @JsonProperty("lastUpdatedAtEpochMillis") long lastUpdatedAtEpochMillis) {
        this.values = copyValues(values);
        this.lastAuthenticatedAtEpochMillis = Math.max(0L, lastAuthenticatedAtEpochMillis);
        this.expiresAtEpochMillis = Math.max(0L, expiresAtEpochMillis);
        this.lastUpdatedAtEpochMillis = Math.max(0L, lastUpdatedAtEpochMillis);
    }

    /**
     * Returns empty session state.
     *
     * @return empty state
     */
    public static SessionState empty() {
        return new SessionState(Map.of(), 0L, 0L, 0L);
    }

    /**
     * Returns global session values.
     *
     * @return immutable values
     */
    public Map<String, String> values() {
        return values;
    }

    /**
     * Returns the last successful auth timestamp.
     *
     * @return epoch milliseconds
     */
    public long lastAuthenticatedAtEpochMillis() {
        return lastAuthenticatedAtEpochMillis;
    }

    /**
     * Returns the state expiration time.
     *
     * @return epoch milliseconds, or 0
     */
    public long expiresAtEpochMillis() {
        return expiresAtEpochMillis;
    }

    /**
     * Returns the last update timestamp.
     *
     * @return epoch milliseconds
     */
    public long lastUpdatedAtEpochMillis() {
        return lastUpdatedAtEpochMillis;
    }

    /**
     * Returns true when the state has no values or has expired.
     *
     * @param nowEpochMillis current timestamp
     * @return true when missing or expired
     */
    public boolean missingOrExpired(long nowEpochMillis) {
        if (values.isEmpty()) {
            return true;
        }
        return expiresAtEpochMillis > 0L && expiresAtEpochMillis <= nowEpochMillis;
    }

    /**
     * Returns a copy updated by response extraction.
     *
     * @param updates non-empty extracted values keyed without prefix
     * @param nowEpochMillis update time
     * @return updated state
     */
    public SessionState withExtractedValues(Map<String, String> updates, long nowEpochMillis) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>(values);
        copyValues(updates).forEach((key, value) -> {
            if (!value.isEmpty()) {
                merged.put(key, value);
            }
        });
        return new SessionState(merged, lastAuthenticatedAtEpochMillis, expiresAtEpochMillis, nowEpochMillis);
    }

    /**
     * Returns a copy updated by a successful auth flow.
     *
     * @param updates auth-flow values keyed without prefix
     * @param nowEpochMillis auth timestamp
     * @param ttlSeconds time-to-live in seconds, or 0 for no explicit expiry
     * @return updated state
     */
    public SessionState withAuthenticatedValues(Map<String, String> updates, long nowEpochMillis, long ttlSeconds) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>(values);
        copyValues(updates).forEach((key, value) -> {
            if (!value.isEmpty()) {
                merged.put(key, value);
            }
        });
        long expiresAt = ttlSeconds <= 0L ? 0L : nowEpochMillis + (ttlSeconds * 1000L);
        return new SessionState(merged, nowEpochMillis, expiresAt, nowEpochMillis);
    }

    /**
     * Returns an empty-state copy.
     *
     * @return cleared state
     */
    public SessionState cleared() {
        return empty();
    }

    /**
     * Compares this state to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SessionState that)) {
            return false;
        }
        return lastAuthenticatedAtEpochMillis == that.lastAuthenticatedAtEpochMillis
                && expiresAtEpochMillis == that.expiresAtEpochMillis
                && lastUpdatedAtEpochMillis == that.lastUpdatedAtEpochMillis
                && Objects.equals(values, that.values);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(values, lastAuthenticatedAtEpochMillis, expiresAtEpochMillis, lastUpdatedAtEpochMillis);
    }

    private static Map<String, String> copyValues(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> copied.put(normalizeSessionKey(key), value == null ? "" : value));
        return Map.copyOf(copied);
    }

    static String normalizeSessionKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("session key must not be blank");
        }
        String normalized = key.strip();
        if (normalized.startsWith("session.")) {
            normalized = normalized.substring("session.".length());
        }
        if (normalized.isBlank() || normalized.contains(" ")) {
            throw new IllegalArgumentException("session key must not be blank or contain spaces");
        }
        return normalized;
    }
}
