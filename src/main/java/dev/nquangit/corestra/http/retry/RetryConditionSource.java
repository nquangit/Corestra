package dev.nquangit.corestra.http.retry;

/**
 * Value a retry condition reads from the live exchange. New values must be appended so stored
 * projects keep loading.
 */
public enum RetryConditionSource {
    /** Response status code. */
    STATUS(false, "status"),
    /** First response header with the selector name; absent when missing. */
    RESPONSE_HEADER(true, "header"),
    /** Response status line and headers. */
    RESPONSE_HEADERS(false, "headers"),
    /** Response body text. */
    RESPONSE_BODY(false, "body"),
    /** Status line, headers, blank line and body. */
    RESPONSE_FULL_MESSAGE(false, "response"),
    /** Value(s) selected from a JSON body, e.g. {@code $.error.code}. */
    RESPONSE_JSON_PATH(true, "json"),
    /** Response body length in characters. */
    BODY_LENGTH(false, "body.length"),
    /** Request method. */
    REQUEST_METHOD(false, "method"),
    /** Request path including the query string. */
    REQUEST_PATH(false, "path"),
    /** First request header with the selector name; absent when missing. */
    REQUEST_HEADER(true, "request.header");

    private final boolean needsSelector;
    private final String label;

    RetryConditionSource(boolean needsSelector, String label) {
        this.needsSelector = needsSelector;
        this.label = label;
    }

    /** Returns whether the condition must name a header or JSON path. */
    public boolean needsSelector() {
        return needsSelector;
    }

    /** Returns the short name used in expression previews. */
    public String label() {
        return label;
    }
}
