package dev.nquangit.corestra.session;

/**
 * Identifies the request attribute inspected by a session-scope criterion.
 */
public enum SessionCriterionType {
    /**
     * Matches the request method.
     */
    METHOD,

    /**
     * Matches the request host.
     */
    HOST,

    /**
     * Matches the request path without query string.
     */
    PATH,

    /**
     * Matches a selected request header value.
     */
    HEADER,

    /**
     * Matches the request body.
     */
    BODY
}
