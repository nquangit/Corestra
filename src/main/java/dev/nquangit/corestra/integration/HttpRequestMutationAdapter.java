package dev.nquangit.corestra.integration;

import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import dev.nquangit.corestra.http.inject.RequestMutationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Applies string-based injection results through Montoya's immutable request mutation API.
 * This preserves the original service, HTTP version, custom Host value, and untouched binary
 * body while allowing Montoya to maintain framing headers when a body actually changes.
 */
public final class HttpRequestMutationAdapter {
    private HttpRequestMutationAdapter() {
    }

    /**
     * Applies a mutation result to the original request.
     *
     * @param original original Montoya request
     * @param mutated mutation result
     * @return immutable mutated request
     */
    public static HttpRequest apply(HttpRequest original, RequestMutationResult mutated) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(mutated, "mutated");

        RequestLine requestLine = parseRequestLine(mutated.requestLine(), original.method(), original.path());
        String originalBody = Objects.requireNonNullElse(original.bodyToString(), "");
        String mutatedBody = Objects.requireNonNullElse(mutated.body(), "");
        boolean bodyChanged = !originalBody.equals(mutatedBody);

        List<String> originalHeaderLines = original.headers() == null ? List.of() : original.headers().stream()
                .map(header -> header.name() + ": " + header.value())
                .toList();
        if (Objects.equals(normalizeMethod(original.method()), requestLine.method())
                && Objects.equals(normalizePath(original.path()), requestLine.path())
                && originalHeaderLines.equals(mutated.headers())
                && !bodyChanged) {
            return original;
        }

        HttpRequest updated = original.withMethod(requestLine.method()).withPath(requestLine.path());

        List<HttpHeader> desiredHeaders = new ArrayList<>();
        for (String line : mutated.headers()) {
            if (line == null || line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).strip();
            if (bodyChanged && name.toLowerCase(Locale.ROOT).equals("content-length")) {
                continue;
            }
            desiredHeaders.add(HttpHeader.httpHeader(name, line.substring(colon + 1).stripLeading()));
        }

        List<HttpHeader> originalHeaders = original.headers();
        if (originalHeaders != null && !originalHeaders.isEmpty()) {
            updated = updated.withRemovedHeaders(originalHeaders);
        }
        if (!desiredHeaders.isEmpty()) {
            updated = updated.withAddedHeaders(desiredHeaders);
        }
        if (bodyChanged) {
            updated = updated.withBody(mutatedBody);
        }
        return updated;
    }

    private static RequestLine parseRequestLine(String raw, String fallbackMethod, String fallbackPath) {
        String line = Objects.requireNonNullElse(raw, "").strip();
        int firstSpace = line.indexOf(' ');
        int lastSpace = line.lastIndexOf(' ');
        if (firstSpace <= 0 || lastSpace <= firstSpace) {
            return new RequestLine(normalizeMethod(fallbackMethod), normalizePath(fallbackPath));
        }
        return new RequestLine(
                normalizeMethod(line.substring(0, firstSpace)),
                normalizePath(line.substring(firstSpace + 1, lastSpace)));
    }

    private static String normalizeMethod(String method) {
        return method == null || method.isBlank() ? "GET" : method.strip();
    }

    private static String normalizePath(String path) {
        return path == null || path.isBlank() ? "/" : path.strip();
    }

    private record RequestLine(String method, String path) {
    }
}
