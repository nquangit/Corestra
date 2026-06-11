package dev.nquangit.corestra.access.auth;

/**
 * Defines the future retry policy for a target request after authentication failure.
 */
public enum AuthRetryPolicy {
    NO_RETRY,
    REFRESH_AND_RETRY_ONCE,
    LOGIN_AND_RETRY_ONCE,
    REFRESH_THEN_LOGIN_AND_RETRY_ONCE
}
