package dev.nquangit.corestra.access.auth;

/**
 * Defines the future flow fallback order for AuthProvider state acquisition.
 */
public enum AuthFallbackPolicy {
    VALIDATE_THEN_REFRESH_THEN_LOGIN,
    REFRESH_THEN_LOGIN,
    LOGIN_ONLY,
    MANUAL_ONLY
}
