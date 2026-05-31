package dev.nquangit.corestra.http.extract;

import java.util.List;
import java.util.Objects;

/**
 * An immutable, Montoya-free snapshot of an HTTP message used as input to extraction services.
 *
 * <p>The {@code headers} list holds raw header lines in {@code "Name: Value"} format.
 * The {@code statusLine} carries the first line of an HTTP response (e.g. {@code "HTTP/1.1 200 OK"})
 * or the request line for request messages; it is used by status-code extraction.
 */
public record HttpMessageSnapshot(
        String statusLine,
        List<String> headers,
        String body) {

    /**
     * Creates an HTTP message snapshot.
     *
     * @param statusLine the first line of the HTTP message (request line or response status line)
     * @param headers    the header lines in {@code "Name: Value"} format; must not be {@code null}
     * @param body       the message body as a string; must not be {@code null}
     */
    public HttpMessageSnapshot {
        Objects.requireNonNull(statusLine, "statusLine");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
        headers = List.copyOf(headers);
    }
}
