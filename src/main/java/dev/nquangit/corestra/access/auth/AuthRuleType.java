package dev.nquangit.corestra.access.auth;

/**
 * Defines the type of an AuthProvider success or failure rule.
 */
public enum AuthRuleType {
    STATUS_CODE,
    HEADER_EXISTS,
    BODY_CONTAINS,
    BODY_NOT_CONTAINS,
    JSON_PATH,
    CUSTOM
}
