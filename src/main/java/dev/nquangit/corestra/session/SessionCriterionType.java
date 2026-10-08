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

    /** Matches the actual destination service port. */
    PORT,

    /** Matches the request transport scheme, http or https. */
    PROTOCOL,

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
