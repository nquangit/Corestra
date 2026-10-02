package dev.nquangit.corestra.access.auth.runtime;

/**
 * Identifies the AuthProvider flow purpose being executed.
 */
public enum AuthFlowPurpose {
    VALIDATE,
    REFRESH,
    LOGIN,
    KEEP_ALIVE
}
