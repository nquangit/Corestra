package dev.nquangit.corestra.http.extract;

import java.util.List;
import java.util.Objects;
import java.util.Arrays;

import dev.nquangit.corestra.flow.rule.ExtractRule;

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

    /**
     * Returns the exact text a regex rule searches for the given section. This is the single
     * source of truth shared by runtime extraction and regex suggestions, so selection offsets
     * computed for a suggestion always line up with what the rule sees at runtime.
     *
     * <ul>
     *   <li>{@code BODY}: the body only.</li>
     *   <li>{@code HEADERS}: the start line (request line or status line) plus header lines,
     *       joined with CRLF.</li>
     *   <li>{@code FULL_MESSAGE}: the {@code HEADERS} text, a blank CRLF line, and the body.</li>
     * </ul>
     *
     * @param source the section to render
     * @return the searchable text; never {@code null}
     */
    public String regexInput(ExtractRule.RegexSource source) {
        Objects.requireNonNull(source, "source");
        String head = statusLine + (headers.isEmpty() ? "" : "\r\n" + String.join("\r\n", headers));
        return switch (source) {
            case BODY -> body;
            case HEADERS -> head;
            case FULL_MESSAGE -> head + "\r\n\r\n" + body;
        };
    }

    /**
     * Builds a response status line consistently for every runtime path. A blank reason phrase
     * (HTTP/2 or an absent reason) is omitted rather than leaving a trailing space.
     *
     * @param httpVersion the HTTP version, defaulting to {@code HTTP/1.1} when blank
     * @param statusCode  the numeric status code
     * @param reason      the reason phrase, or {@code null}/blank when absent
     * @return the status line
     */
    public static String responseStartLine(String httpVersion, int statusCode, String reason) {
        String version = httpVersion == null || httpVersion.isBlank() ? "HTTP/1.1" : httpVersion.strip();
        String phrase = reason == null ? "" : reason.strip();
        return version + " " + statusCode + (phrase.isEmpty() ? "" : " " + phrase);
    }
}
