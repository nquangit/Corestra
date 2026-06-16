package dev.nquangit.corestra.access.evaluate;

/**
 * Enumerates response indicator checks supported by evaluation profiles.
 */
public enum IndicatorType {
    STATUS_CODE_IN,
    BODY_CONTAINS,
    BODY_NOT_CONTAINS,
    HEADER_EXISTS,
    HEADER_VALUE_CONTAINS,
    JSON_PATH_EQUALS,
    JSON_PATH_EXISTS,
    JSON_PATH_NOT_EXISTS
}
