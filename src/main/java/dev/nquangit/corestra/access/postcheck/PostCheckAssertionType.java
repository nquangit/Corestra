package dev.nquangit.corestra.access.postcheck;

/**
 * Enumerates assertion checks supported for post-check responses.
 */
public enum PostCheckAssertionType {
    STATUS_CODE,
    BODY_CONTAINS,
    BODY_NOT_CONTAINS,
    HEADER_EXISTS,
    HEADER_VALUE_CONTAINS,
    JSON_PATH_EXISTS,
    JSON_PATH_EQUALS,
    JSON_PATH_NOT_EQUALS
}
