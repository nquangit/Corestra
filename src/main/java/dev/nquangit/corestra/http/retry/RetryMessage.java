package dev.nquangit.corestra.http.retry;

import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The parts of one request/response exchange that retry conditions can read. Text views come from
 * {@link HttpMessageSnapshot#regexInput}, the same rendering used by extraction rules.
 *
 * @param response      response snapshot (status line, header lines, body)
 * @param status        response status code
 * @param requestMethod request method
 * @param requestPath   request path including the query
 * @param requestHeaders request header lines ({@code Name: value})
 */
public record RetryMessage(HttpMessageSnapshot response, int status, String requestMethod, String requestPath,
                           List<String> requestHeaders) {

    /** Normalizes the message. */
    public RetryMessage {
        Objects.requireNonNull(response, "response");
        requestMethod = requestMethod == null ? "" : requestMethod;
        requestPath = requestPath == null ? "" : requestPath;
        requestHeaders = requestHeaders == null ? List.of() : List.copyOf(requestHeaders);
    }

    /**
     * Parses a raw HTTP response (as pasted in the Test view) with optional request facts.
     *
     * @param rawResponse   raw response text; CRLF or LF line endings
     * @param requestMethod request method, may be blank
     * @param requestPath   request path, may be blank
     * @return message
     * @throws IllegalArgumentException when the status line is missing or malformed
     */
    public static RetryMessage parse(String rawResponse, String requestMethod, String requestPath) {
        String raw = rawResponse == null ? "" : rawResponse.replace("\r\n", "\n");
        int split = raw.indexOf("\n\n");
        String head = split < 0 ? raw : raw.substring(0, split);
        String body = split < 0 ? "" : raw.substring(split + 2);
        String[] lines = head.split("\n", -1);
        String statusLine = lines[0].strip();
        String[] parts = statusLine.split(" ", 3);
        int status;
        try {
            if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new NumberFormatException();
            status = Integer.parseInt(parts[1]);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Response must start with a status line such as HTTP/1.1 403 Forbidden");
        }
        List<String> headers = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) headers.add(lines[i].strip());
        }
        return new RetryMessage(new HttpMessageSnapshot(statusLine, headers, body), status, requestMethod, requestPath,
                List.of());
    }

    /** Returns the response body. */
    public String body() {
        return response.body();
    }

    /** Returns status line and headers. */
    public String headerBlock() {
        return response.regexInput(ExtractRule.RegexSource.HEADERS);
    }

    /** Returns status line, headers, blank line and body. */
    public String fullMessage() {
        return response.regexInput(ExtractRule.RegexSource.FULL_MESSAGE);
    }

    /** Returns the first response header value with the given name, ignoring case. */
    public Optional<String> responseHeader(String name) {
        return header(response.headers(), name);
    }

    /** Returns the first request header value with the given name, ignoring case. */
    public Optional<String> requestHeader(String name) {
        return header(requestHeaders, name);
    }

    private static Optional<String> header(List<String> lines, String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).strip().equalsIgnoreCase(name.strip())) {
                return Optional.of(line.substring(colon + 1).strip());
            }
        }
        return Optional.empty();
    }
}
