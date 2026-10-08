package dev.nquangit.corestra.session;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
    @JsonIgnore private final Pattern compiledPattern;
    @JsonIgnore private final String validationError;

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
        Pattern compiled = null;
        String error = "";
        try {
            compiled = Pattern.compile(this.pattern);
        } catch (PatternSyntaxException exception) {
            error = "Invalid regex: " + exception.getDescription();
        }
        if (this.type == SessionCriterionType.HEADER && this.selector.isBlank()) {
            error = "HEADER requires a header name in Selector";
        }
        this.compiledPattern = compiled;
        this.validationError = error;
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
        return matches(new SessionTrafficContext("", false, method, host, path, headers, body));
    }

    /** Returns a static configuration error without making old malformed criteria broaden scope. */
    @JsonIgnore
    public Optional<String> validationError() {
        return validationError.isEmpty() ? Optional.empty() : Optional.of(validationError);
    }

    /** Matches full request attributes, requiring header presence and known port/protocol metadata. */
    public boolean matches(SessionTrafficContext context) {
        if (!validationError.isEmpty()) return false;
        String value = switch (type) {
            case METHOD -> context.method();
            case HOST -> context.host();
            case PORT -> context.port() == 0 ? null : Integer.toString(context.port());
            case PROTOCOL -> context.scheme().isBlank() ? null : context.scheme();
            case PATH -> context.path();
            case HEADER -> headerValue(context.headers(), selector);
            case BODY -> context.body();
        };
        return value != null && (pattern.isBlank() || compiledPattern.matcher(value).find());
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
            return null;
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
        return null;
    }

    private static String normalizeOptionalText(String value) {
        return value == null ? "" : value.strip();
    }
}
