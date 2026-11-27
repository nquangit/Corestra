package dev.nquangit.corestra.http.retry;

/**
 * Request facts used to choose a {@link RetryProfile}.
 *
 * @param toolType    Burp tool name, e.g. {@code PROXY}
 * @param method      HTTP method
 * @param host        host name
 * @param path        path including the query string
 * @param inBurpScope whether Burp considers the request in scope
 */
public record RetryRequestContext(String toolType, String method, String host, String path, boolean inBurpScope) { }
