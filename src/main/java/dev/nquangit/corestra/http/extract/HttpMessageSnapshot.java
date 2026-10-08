package dev.nquangit.corestra.http.extract;

import java.util.List;
import java.util.Objects;
import java.util.Arrays;

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
        String body,
        byte[] bodyBytes) {

    /** Creates a text-only snapshot. Binary extraction is unavailable for this snapshot. */
    public HttpMessageSnapshot(String statusLine, List<String> headers, String body) {
        this(statusLine, headers, body, null);
    }

    /**
     * Creates an HTTP message snapshot.
     *
     * @param statusLine the first line of the HTTP message (request line or response status line)
     * @param headers    the header lines in {@code "Name: Value"} format; must not be {@code null}
     * @param body       the message body as a string; must not be {@code null}
     * @param bodyBytes  exact body bytes, or {@code null} when unavailable; defensively copied
     */
    public HttpMessageSnapshot {
        Objects.requireNonNull(statusLine, "statusLine");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
        headers = List.copyOf(headers);
        bodyBytes = bodyBytes == null ? null : bodyBytes.clone();
    }

    /** Returns a copy of the exact body bytes, or {@code null} when unavailable. */
    @Override
    public byte[] bodyBytes() {
        return bodyBytes == null ? null : bodyBytes.clone();
    }

    /** Compares the snapshot contents, including binary body contents. */
    @Override
    public boolean equals(Object other) {
        return other instanceof HttpMessageSnapshot snapshot
                && statusLine.equals(snapshot.statusLine)
                && headers.equals(snapshot.headers)
                && body.equals(snapshot.body)
                && Arrays.equals(bodyBytes, snapshot.bodyBytes);
    }

    /** Returns a content-based hash code. */
    @Override
    public int hashCode() {
        return 31 * Objects.hash(statusLine, headers, body) + Arrays.hashCode(bodyBytes);
    }
}
