package dev.nquangit.corestra.session;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One regex criterion used by a custom session profile scope or auth trigger.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class SessionScopeCriterion {
    private final SessionCriterionType type;
    private final String selector;
    private final String pattern;

    /**
     * Creates a session scope criterion.
     *
     * @param type request attribute to inspect
     * @param selector optional selector, used as the header name for {@link SessionCriterionType#HEADER}
     * @param pattern regex pattern; blank means match any value
     */
    @JsonCreator
    public SessionScopeCriterion(
            @JsonProperty("type") SessionCriterionType type,
            @JsonProperty("selector") String selector,
            @JsonProperty("pattern") String pattern) {
        this.type = Objects.requireNonNullElse(type, SessionCriterionType.HOST);
        this.selector = normalizeOptionalText(selector);
        this.pattern = normalizeOptionalText(pattern);
    }

    /**
     * Returns the request attribute type.
     *
     * @return criterion type
     */
    public SessionCriterionType type() {
        return type;
    }

    /**
     * Returns the optional selector.
     *
     * @return selector, or empty
     */
    public String selector() {
        return selector;
    }

    /**
     * Returns the regex pattern.
     *
     * @return regex pattern, or empty
     */
    public String pattern() {
        return pattern;
    }

    /**
     * Returns whether this criterion matches the supplied request attributes.
     *
     * @param method request method
     * @param host request host
     * @param path request path without query string
     * @param headers request headers as {@code Name: Value}
     * @param body request body
     * @return true when matched
     */
    public boolean matches(String method, String host, String path, List<String> headers, String body) {
        String value = switch (type) {
            case METHOD -> Objects.requireNonNullElse(method, "");
            case HOST -> Objects.requireNonNullElse(host, "");
            case PATH -> Objects.requireNonNullElse(path, "");
            case HEADER -> headerValue(headers, selector);
            case BODY -> Objects.requireNonNullElse(body, "");
        };
        if (pattern.isBlank()) {
            return true;
        }
        try {
            return Pattern.compile(pattern).matcher(value).find();
        } catch (PatternSyntaxException ignored) {
            return false;
        }
    }

    /**
     * Compares this criterion to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SessionScopeCriterion that)) {
            return false;
        }
        return type == that.type
                && Objects.equals(selector, that.selector)
                && Objects.equals(pattern, that.pattern);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(type, selector, pattern);
    }

    private static String headerValue(List<String> headers, String name) {
        if (headers == null || name == null || name.isBlank()) {
            return "";
        }
        String expected = name.strip();
        for (String header : headers) {
            if (header == null) {
                continue;
            }
            int colon = header.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            if (header.substring(0, colon).strip().equalsIgnoreCase(expected)) {
                return header.substring(colon + 1).strip();
            }
        }
        return "";
    }

    private static String normalizeOptionalText(String value) {
        return value == null ? "" : value.strip();
    }
}
