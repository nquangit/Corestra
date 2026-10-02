package dev.nquangit.corestra.access.auth.runtime;

/**
 * Describes the outcome of ensuring an AuthProfile has usable authentication state.
 */
public enum AuthEnsureStatus {
    VALID,
    REFRESHED,
    LOGGED_IN,
    KEPT_ALIVE,
    DEFERRED,
    MANUAL,
    SKIPPED,
    FAILED
}
