package dev.nquangit.corestra.access.result;

/**
 * Describes the security verdict derived from an access test result.
 */
public enum Verdict {
    OK,
    SUSPICIOUS,
    REVIEW,
    ERROR,
    NOT_RUN
}
