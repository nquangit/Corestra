package dev.nquangit.corestra.access.auth;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Captures the current authentication material for an access-testing actor.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class AuthState {
    private final Map<String, String> variables;
    private final Map<String, String> headers;
    private final Map<String, String> cookies;
    private final long expiresAtEpochMillis;
    private final long lastUpdatedAtEpochMillis;

    /**
     * Creates an authentication state.
     *
     * @param variables scoped auth variables such as accessToken and csrfToken
     * @param headers manual headers for the authenticated actor
     * @param cookies manual cookies for the authenticated actor
     * @param expiresAtEpochMillis expiration timestamp in epoch milliseconds, or 0 when unknown
     * @param lastUpdatedAtEpochMillis last update timestamp in epoch milliseconds, or 0 when unknown
     */
    @JsonCreator
    public AuthState(
            @JsonProperty("variables") Map<String, String> variables,
            @JsonProperty("headers") Map<String, String> headers,
            @JsonProperty("cookies") Map<String, String> cookies,
            @JsonProperty("expiresAtEpochMillis") long expiresAtEpochMillis,
            @JsonProperty("lastUpdatedAtEpochMillis") long lastUpdatedAtEpochMillis) {
        this.variables = copyMap(variables);
        this.headers = copyMap(headers);
        this.cookies = copyMap(cookies);
        this.expiresAtEpochMillis = expiresAtEpochMillis;
        this.lastUpdatedAtEpochMillis = lastUpdatedAtEpochMillis;
    }

    /**
     * Returns an empty authentication state.
     *
     * @return an empty authentication state
     */
    public static AuthState empty() {
        return new AuthState(Map.of(), Map.of(), Map.of(), 0L, 0L);
    }

    /**
     * Returns auth variables such as accessToken, refreshToken, and csrfToken.
     *
     * @return immutable auth variables
     */
    public Map<String, String> variables() {
        return variables;
    }

    /**
     * Returns manual headers for the authenticated actor.
     *
     * @return immutable manual headers
     */
    public Map<String, String> headers() {
        return headers;
    }

    /**
     * Returns manual cookies for the authenticated actor.
     *
     * @return immutable manual cookies
     */
    public Map<String, String> cookies() {
        return cookies;
    }

    /**
     * Returns the expiration timestamp in epoch milliseconds.
     *
     * @return expiration timestamp, or 0 when unknown
     */
    public long expiresAtEpochMillis() {
        return expiresAtEpochMillis;
    }

    /**
     * Returns the last update timestamp in epoch milliseconds.
     *
     * @return last update timestamp, or 0 when unknown
     */
    public long lastUpdatedAtEpochMillis() {
        return lastUpdatedAtEpochMillis;
    }

    /**
     * Compares this state to another object.
     *
     * @param other the candidate object
     * @return true when the objects are equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AuthState that)) {
            return false;
        }
        return expiresAtEpochMillis == that.expiresAtEpochMillis
                && lastUpdatedAtEpochMillis == that.lastUpdatedAtEpochMillis
                && Objects.equals(variables, that.variables)
                && Objects.equals(headers, that.headers)
                && Objects.equals(cookies, that.cookies);
    }

    /**
     * Returns the hash code for this state.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(variables, headers, cookies, expiresAtEpochMillis, lastUpdatedAtEpochMillis);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "AuthState{" +
                "variables=" + variables.keySet() +
                ", headers=" + headers.keySet() +
                ", cookies=" + cookies.keySet() +
                ", expiresAtEpochMillis=" + expiresAtEpochMillis +
                ", lastUpdatedAtEpochMillis=" + lastUpdatedAtEpochMillis +
                '}';
    }

    private static Map<String, String> copyMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("map keys must not be blank");
            }
            copied.put(key.strip(), Objects.requireNonNullElse(value, ""));
        });
        return Map.copyOf(copied);
    }
}
