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
            "(?i)(accessToken|refreshToken|csrfToken|sessionId|password|deviceId|accountNumber|cardNumber|nationalId)" +
                    "([\"'\\s:=]+)([^\\s\"',&}]+)");

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
            redacted.add(name + ": " + redactHeader(name, value));
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
        redacted.add(lines.getFirst());
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
        return INLINE_SECRET_PATTERN.matcher(text).replaceAll("$1$2" + replacement);
    }
}
