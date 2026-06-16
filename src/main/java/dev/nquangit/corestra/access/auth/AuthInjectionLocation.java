package dev.nquangit.corestra.access.auth;

/**
 * Defines where an AuthProvider injection rule will place rendered auth material.
 */
public enum AuthInjectionLocation {
    HEADER,
    COOKIE,
    QUERY,
    BODY_JSON,
    BODY_FORM,
    PATH
}
