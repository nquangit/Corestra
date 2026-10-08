package dev.nquangit.corestra.session;

import java.util.List;
import java.util.Objects;

/**
 * Montoya-free request attributes used for session profile matching and rule filtering.
 *
 * @param toolTypeName Montoya tool type name such as {@code PROXY}, {@code REPEATER}, or {@code EXTENSIONS}
 * @param burpInScope whether Burp considers the request inside Target scope
 * @param method request method
 * @param host request host
 * @param path request path without query string
 * @param headers request headers in {@code Name: Value} format
 * @param body request body
 * @param scheme request transport scheme ({@code http}/{@code https}), or empty when unknown
 * @param port service port, or 0 when unknown
 */
public record SessionTrafficContext(
        String toolTypeName,
        boolean burpInScope,
        String method,
        String host,
        String path,
        List<String> headers,
        String body,
        String scheme,
        int port) {

    /** Creates a legacy context without transport metadata; port/protocol criteria fail closed. */
    public SessionTrafficContext(String toolTypeName, boolean burpInScope, String method, String host,
                                 String path, List<String> headers, String body) {
        this(toolTypeName, burpInScope, method, host, path, headers, body, "", 0);
    }

    /**
     * Creates a session traffic context.
     */
    public SessionTrafficContext {
        toolTypeName = normalize(toolTypeName);
        method = normalize(method);
        host = normalize(host);
        path = normalize(path);
        headers = List.copyOf(Objects.requireNonNullElse(headers, List.of()));
        body = body == null ? "" : body;
        scheme = normalize(scheme).toLowerCase(java.util.Locale.ROOT);
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid service port: " + port);
    }

    /**
     * Returns an internal extension context for scheduled authentication.
     *
     * @return extension traffic context
     */
    public static SessionTrafficContext extensions() {
        return new SessionTrafficContext("EXTENSIONS", true, "", "", "", List.of(), "");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
