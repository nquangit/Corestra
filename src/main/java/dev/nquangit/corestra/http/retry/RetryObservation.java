package dev.nquangit.corestra.http.retry;

/**
 * What the retry engine needs to know about one response.
 *
 * @param status      response status code
 * @param fullMessage rendered full response (status line, headers, blank line, body); may be {@code null}
 *                    when the policy has no response pattern
 * @param retryAfter  raw {@code Retry-After} header value, or {@code null}
 */
public record RetryObservation(int status, String fullMessage, String retryAfter) { }
