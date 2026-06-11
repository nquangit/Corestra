package dev.nquangit.corestra.access.security;

/**
 * Defines how sensitive values should be handled during future exports.
 */
public enum ExportMode {
    FULL_EXPORT,
    SANITIZED_EXPORT,
    EXCLUDE_SECRETS,
    ENCRYPTED_EXPORT
}
