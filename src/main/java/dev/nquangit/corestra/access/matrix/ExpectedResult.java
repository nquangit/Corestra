package dev.nquangit.corestra.access.matrix;

/**
 * Describes the expected authorization outcome for an access test.
 */
public enum ExpectedResult {
    ALLOW,
    DENY,
    MASKED,
    READ_ONLY,
    CUSTOM,
    REVIEW
}
