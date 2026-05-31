package dev.nquangit.corestra.flow;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Stores a transport-neutral HTTP request template for a flow step.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class StoredRequestTemplate {
    private final String method;
    private final String scheme;
    private final String host;
    private final int port;
    private final String pathAndQuery;
    private final List<String> headerLines;
    private final String body;

    /**
     * Creates a stored request template.
     *
     * @param method the HTTP method
     * @param scheme the URL scheme
     * @param host the target host
     * @param port the target port
     * @param pathAndQuery the request path and query string
     * @param headerLines the raw header lines in wire order
     * @param body the request body as text
     */
    @JsonCreator
    public StoredRequestTemplate(
            @JsonProperty("method") String method,
            @JsonProperty("scheme") String scheme,
            @JsonProperty("host") String host,
            @JsonProperty("port") int port,
            @JsonProperty("pathAndQuery") String pathAndQuery,
            @JsonProperty("headerLines") List<String> headerLines,
            @JsonProperty("body") String body) {
        this.method = normalizeRequiredText(method, "method");
        this.scheme = normalizeRequiredText(scheme, "scheme");
        this.host = normalizeRequiredText(host, "host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        this.port = port;
        this.pathAndQuery = normalizeRequiredText(pathAndQuery, "pathAndQuery");
        this.headerLines = normalizeHeaders(headerLines);
        this.body = body == null ? "" : body;
    }

    /**
     * Returns the HTTP method.
     *
     * @return the HTTP method
     */
    public String method() {
        return method;
    }

    /**
     * Returns the URL scheme.
     *
     * @return the URL scheme
     */
    public String scheme() {
        return scheme;
    }

    /**
     * Returns the target host.
     *
     * @return the target host
     */
    public String host() {
        return host;
    }

    /**
     * Returns the target port.
     *
     * @return the target port
     */
    public int port() {
        return port;
    }

    /**
     * Returns the request path and query string.
     *
     * @return the request path and query string
     */
    public String pathAndQuery() {
        return pathAndQuery;
    }

    /**
     * Returns the raw header lines.
     *
     * @return the raw header lines
     */
    public List<String> headerLines() {
        return headerLines;
    }

    /**
     * Returns the request body text.
     *
     * @return the request body text
     */
    public String body() {
        return body;
    }

    /**
     * Compares this template to another object.
     *
     * @param other the candidate object
     * @return {@code true} when the objects are equal, otherwise {@code false}
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StoredRequestTemplate that)) {
            return false;
        }
        return port == that.port
                && Objects.equals(method, that.method)
                && Objects.equals(scheme, that.scheme)
                && Objects.equals(host, that.host)
                && Objects.equals(pathAndQuery, that.pathAndQuery)
                && Objects.equals(headerLines, that.headerLines)
                && Objects.equals(body, that.body);
    }

    /**
     * Returns the hash code for this template.
     *
     * @return the hash code for this template
     */
    @Override
    public int hashCode() {
        return Objects.hash(method, scheme, host, port, pathAndQuery, headerLines, body);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "StoredRequestTemplate{" +
                "method='" + method + '\'' +
                ", scheme='" + scheme + '\'' +
                ", host='" + host + '\'' +
                ", port=" + port +
                ", pathAndQuery='" + pathAndQuery + '\'' +
                ", headerLines=" + headerLines +
                ", bodyLength=" + body.length() +
                '}';
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }

    private static List<String> normalizeHeaders(List<String> headerLines) {
        if (headerLines == null) {
            return List.of();
        }

        List<String> normalized = new ArrayList<>();
        for (String headerLine : headerLines) {
            if (headerLine != null && !headerLine.isBlank()) {
                normalized.add(headerLine.strip());
            }
        }
        return List.copyOf(normalized);
    }
}
