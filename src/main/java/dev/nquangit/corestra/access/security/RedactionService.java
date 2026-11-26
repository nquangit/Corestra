package dev.nquangit.corestra.access.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Applies simple key-based redaction to values, maps, and header lines.
 */
public final class RedactionService {
    private static final String DEFAULT_REPLACEMENT = "<redacted>";
    private static final Pattern INLINE_SECRET_PATTERN = Pattern.compile(
            "(?i)(access[-_]?token|refresh[-_]?token|id[-_]?token|auth[-_]?token|session[-_]?token|csrf[-_]?token"
                    + "|session[-_]?id|api[-_]?key|client[-_]?secret|private[-_]?key|password|device[-_]?id"
                    + "|account[-_]?number|card[-_]?number|national[-_]?id)"
                    + "([\"'\\s:=]+)([^\\s\"',&}]+)");
    /** Matches a compact JWS/JWT ({@code header.payload.signature}) whose header and payload are JSON objects. */
    private static final Pattern JWT_PATTERN = Pattern.compile(
            "eyJ[A-Za-z0-9_-]{4,}\\.eyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]*");

    private final SensitiveKeyMatcher keyMatcher;
    private final String replacement;

    /**
     * Creates a redaction service with the default matcher and replacement.
     */
    public RedactionService() {
        this(new DefaultSensitiveKeyMatcher(), DEFAULT_REPLACEMENT);
    }

    /**
     * Creates a redaction service.
     *
     * @param keyMatcher matcher used to identify sensitive keys
     * @param replacement replacement text for sensitive values
     */
    public RedactionService(SensitiveKeyMatcher keyMatcher, String replacement) {
        this.keyMatcher = Objects.requireNonNull(keyMatcher, "keyMatcher");
        this.replacement = replacement == null || replacement.isBlank() ? DEFAULT_REPLACEMENT : replacement;
    }

    /**
     * Redacts a value when its key is sensitive.
     *
     * @param key field, header, cookie, or variable key
     * @param value value to redact
     * @return redacted or original value
     */
    public String redactValue(String key, String value) {
        return keyMatcher.isSensitive(key) ? replacement : Objects.requireNonNullElse(value, "");
    }

    /**
     * Returns whether a key is considered sensitive by this service.
     *
     * @param key field, header, cookie, or variable key
     * @return true when the key is sensitive
     */
    public boolean isSensitiveKey(String key) {
        return keyMatcher.isSensitive(key);
    }

    /**
     * Redacts all sensitive entries in a map.
     *
     * @param values values to redact
     * @return immutable redacted map
     */
    public Map<String, String> redactMap(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> redacted = new LinkedHashMap<>();
        values.forEach((key, value) -> redacted.put(key, redactValue(key, value)));
        return Map.copyOf(redacted);
    }

    /**
     * Redacts a header value when the header name is sensitive.
     *
     * @param headerName header name
     * @param value header value
     * @return redacted or original header value
     */
    public String redactHeader(String headerName, String value) {
        return redactValue(headerName, value);
    }

    /**
     * Redacts sensitive raw header lines.
     *
     * @param headers raw header lines in {@code Name: value} form
     * @return immutable redacted header lines
     */
    public List<String> redactHeaders(List<String> headers) {
        Objects.requireNonNull(headers, "headers");
        if (headers.isEmpty()) {
            return List.of();
        }
        List<String> redacted = new ArrayList<>();
        for (String header : headers) {
            if (header == null || header.isBlank()) {
                continue;
            }
            int colonIndex = header.indexOf(':');
            if (colonIndex < 0) {
                redacted.add(keyMatcher.isSensitive(header) ? replacement : header);
                continue;
            }
            String name = header.substring(0, colonIndex).strip();
            String value = header.substring(colonIndex + 1).strip();
            redacted.add(name + ": " + redactJwts(redactHeader(name, value)));
        }
        return List.copyOf(redacted);
    }

    /**
     * Redacts sensitive headers and common inline secret fields in a raw HTTP message.
     *
     * @param rawMessage raw HTTP request or response text
     * @return redacted HTTP message
     */
    public String redactHttpMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isEmpty()) {
            return "";
        }
        String normalized = rawMessage.replace("\r\n", "\n");
        List<String> lines = Arrays.asList(normalized.split("\n", -1));
        if (lines.isEmpty()) {
            return "";
        }

        List<String> redacted = new ArrayList<>();
        redacted.add(redactJwts(redactStartLineQuery(lines.getFirst())));
        int index = 1;
        List<String> headerLines = new ArrayList<>();
        while (index < lines.size() && !lines.get(index).isEmpty()) {
            headerLines.add(lines.get(index));
            index++;
        }
        redacted.addAll(redactHeaders(headerLines));
        if (index < lines.size()) {
            redacted.add("");
            index++;
        }
        if (index < lines.size()) {
            String body = String.join("\n", lines.subList(index, lines.size()));
            redacted.add(redactInlineSecrets(body));
        }
        return String.join("\n", redacted);
    }

    /**
     * Redacts common inline key/value secret fields in free text.
     *
     * @param text text to redact
     * @return redacted text
     */
    public String redactInlineSecrets(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String quotedReplacement = java.util.regex.Matcher.quoteReplacement(replacement);
        return redactJwts(INLINE_SECRET_PATTERN.matcher(text).replaceAll("$1$2" + quotedReplacement));
    }

    /**
     * Redacts compact JWTs anywhere in the text, whatever field or header carries them.
     *
     * @param text text to redact
     * @return text with JWTs replaced
     */
    public String redactJwts(String text) {
        if (text == null || text.isEmpty()) {
            return Objects.requireNonNullElse(text, "");
        }
        return JWT_PATTERN.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(replacement));
    }

    /**
     * Redacts sensitive query parameter values in a request start line ({@code METHOD target VERSION}).
     * Response status lines and lines without a query are returned unchanged.
     */
    private String redactStartLineQuery(String startLine) {
        int firstSpace = startLine.indexOf(' ');
        int query = startLine.indexOf('?');
        if (firstSpace < 0 || query < firstSpace) {
            return startLine;
        }
        int targetEnd = startLine.indexOf(' ', query);
        if (targetEnd < 0) {
            targetEnd = startLine.length();
        }
        int fragment = startLine.indexOf('#', query);
        int queryEnd = fragment >= 0 && fragment < targetEnd ? fragment : targetEnd;
        String[] pairs = startLine.substring(query + 1, queryEnd).split("&", -1);
        List<String> redactedPairs = new ArrayList<>(pairs.length);
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            if (equals > 0 && keyMatcher.isSensitive(decodeQueryName(pair.substring(0, equals)))) {
                redactedPairs.add(pair.substring(0, equals + 1) + replacement);
            } else {
                redactedPairs.add(pair);
            }
        }
        return startLine.substring(0, query + 1) + String.join("&", redactedPairs) + startLine.substring(queryEnd);
    }

    private static String decodeQueryName(String name) {
        try {
            return java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return name;
        }
    }
}
