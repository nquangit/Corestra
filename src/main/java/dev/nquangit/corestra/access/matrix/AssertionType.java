package dev.nquangit.corestra.access.matrix;

/**
 * Defines the assertion type attached to an access test case.
 */
public enum AssertionType {
    STATUS_CODE,
    BODY_CONTAINS,
    BODY_NOT_CONTAINS,
    HEADER_EXISTS,
    HEADER_VALUE,
    JSON_PATH,
    CUSTOM
}
