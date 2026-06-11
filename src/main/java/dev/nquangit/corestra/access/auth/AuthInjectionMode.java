package dev.nquangit.corestra.access.auth;

/**
 * Defines how an AuthProvider injection rule handles an existing target value.
 */
public enum AuthInjectionMode {
    OVERRIDE,
    APPEND,
    SKIP_IF_PRESENT
}
