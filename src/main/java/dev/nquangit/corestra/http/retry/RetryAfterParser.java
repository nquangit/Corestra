package dev.nquangit.corestra.http.retry;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.OptionalLong;

/** Parses {@code Retry-After} as delta-seconds or an HTTP-date (RFC 9110 section 10.2.3). */
public final class RetryAfterParser {
    private RetryAfterParser() { }

    /**
     * Returns the delay the server asked for.
     *
     * @param value raw header value, may be {@code null}
     * @param now   current time, used for HTTP-date values
     * @return delay in milliseconds (never negative), or empty when absent or unparseable
     */
    public static OptionalLong delayMillis(String value, Instant now) {
        if (value == null || value.isBlank()) return OptionalLong.empty();
        String trimmed = value.strip();
        if (trimmed.matches("\\d{1,9}")) {
            return OptionalLong.of(Long.parseLong(trimmed) * 1_000L);
        }
        try {
            Instant at = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return OptionalLong.of(Math.max(0L, at.toEpochMilli() - now.toEpochMilli()));
        } catch (DateTimeParseException ignored) {
            return OptionalLong.empty();
        }
    }
}
