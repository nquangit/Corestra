package dev.nquangit.corestra.http.inject;

import java.util.List;
import java.util.Objects;

/**
 * Represents the outcome of applying a set of {@link dev.nquangit.corestra.flow.rule.InjectRule}
 * objects to an HTTP request.
 *
 * <p>Instances are immutable and constructed via {@link #of(List, String, List)}.
 */
public final class RequestMutationResult {

    private final String requestLine;
    private final List<String> headers;
    private final String body;
    private final List<String> diagnostics;

    private RequestMutationResult(String requestLine, List<String> headers, String body, List<String> diagnostics) {
        this.requestLine = Objects.requireNonNull(requestLine, "requestLine");
        this.headers = List.copyOf(headers);
        this.body = Objects.requireNonNull(body, "body");
        this.diagnostics = List.copyOf(diagnostics);
    }

    /**
     * Creates a {@code RequestMutationResult} from the mutated request parts and per-rule diagnostics.
     *
     * @param requestLine the mutated HTTP request line; must not be {@code null}
     * @param headers     the mutated header lines in {@code "Name: Value"} format; must not be {@code null}
     * @param body        the mutated request body; must not be {@code null}
     * @param diagnostics human-readable per-rule outcome messages; must not be {@code null}
     * @return a new {@code RequestMutationResult}
     */
    public static RequestMutationResult of(String requestLine, List<String> headers, String body, List<String> diagnostics) {
        Objects.requireNonNull(requestLine, "requestLine");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(diagnostics, "diagnostics");
        return new RequestMutationResult(requestLine, headers, body, diagnostics);
    }

    /**
     * Returns the mutated HTTP request line (e.g. {@code "GET /path?q=1 HTTP/1.1"}).
     *
     * @return the mutated request line
     */
    public String requestLine() {
        return requestLine;
    }

    /**
     * Returns the mutated header lines in {@code "Name: Value"} format.
     *
     * @return the mutated headers as an unmodifiable list
     */
    public List<String> headers() {
        return headers;
    }

    /**
     * Returns the mutated request body.
     *
     * @return the mutated body
     */
    public String body() {
        return body;
    }

    /**
     * Returns the per-rule diagnostic messages collected during mutation.
     *
     * @return the diagnostics as an unmodifiable list
     */
    public List<String> diagnostics() {
        return diagnostics;
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "RequestMutationResult{requestLine='" + requestLine + "', headers=" + headers + ", body='" + body + "', diagnostics=" + diagnostics + '}';
    }
}
