package dev.nquangit.corestra.session;

/**
 * Defines how a session profile decides whether traffic belongs to its scope.
 */
public enum SessionScopeMode {
    /**
     * Uses Burp Suite's project target scope.
     */
    BURP_TARGET_SCOPE,

    /**
     * Uses Corestra-owned request match criteria.
     */
    CUSTOM
}
