package dev.nquangit.corestra.intel;

import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Converts stored Corestra messages into {@link HttpMessageSnapshot}s for offline analysis.
 *
 * <p>Analysis never sends traffic; it only reads what a flow step already captured.</p>
 */
public final class RawHttpMessages {
    private RawHttpMessages() {
    }

    /**
     * Decodes a step's Base64 {@code lastResponseRaw} into a response snapshot.
     *
     * @param base64 Base64 raw response, or null
     * @return snapshot, or empty when absent or malformed
     */
    public static Optional<HttpMessageSnapshot> responseFromBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            return Optional.empty();
        }
        try {
            return parse(Base64.getDecoder().decode(base64.strip()));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * Parses raw HTTP message bytes (start line, headers, blank line, body).
     *
     * @param raw raw message bytes
     * @return snapshot, or empty when there is no header/body separator
     */
    public static Optional<HttpMessageSnapshot> parse(byte[] raw) {
        if (raw == null) {
            return Optional.empty();
        }
        int separator = -1;
        int separatorLength = 0;
        for (int index = 0; index < raw.length - 1; index++) {
            if (index + 3 < raw.length && raw[index] == '\r' && raw[index + 1] == '\n'
                    && raw[index + 2] == '\r' && raw[index + 3] == '\n') {
                separator = index;
                separatorLength = 4;
                break;
            }
            if (raw[index] == '\n' && raw[index + 1] == '\n') {
                separator = index;
                separatorLength = 2;
                break;
            }
        }
        String head;
        byte[] bodyBytes;
        if (separator < 0) {
            head = new String(raw, StandardCharsets.UTF_8).stripTrailing();
            bodyBytes = new byte[0];
        } else {
            head = new String(raw, 0, separator, StandardCharsets.UTF_8);
            bodyBytes = Arrays.copyOfRange(raw, separator + separatorLength, raw.length);
        }
        String[] lines = head.split("\\r?\\n", -1);
        if (lines.length == 0 || lines[0].isBlank()) {
            return Optional.empty();
        }
        List<String> headers = new ArrayList<>();
        for (int index = 1; index < lines.length; index++) {
            if (!lines[index].isBlank()) {
                headers.add(lines[index]);
            }
        }
        return Optional.of(new HttpMessageSnapshot(
                lines[0], headers, new String(bodyBytes, StandardCharsets.UTF_8), bodyBytes));
    }

    /**
     * Builds a request snapshot from a stored request template.
     *
     * @param request stored request
     * @return request snapshot
     */
    public static HttpMessageSnapshot request(StoredRequestTemplate request) {
        return new HttpMessageSnapshot(
                request.method() + " " + request.pathAndQuery() + " HTTP/1.1",
                request.headerLines(),
                request.body());
    }

    /**
     * Returns the first header value with the given name (case-insensitive).
     *
     * @param headers header lines
     * @param name header name
     * @return value, or empty
     */
    public static Optional<String> header(List<String> headers, String name) {
        for (String line : headers) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).strip().equalsIgnoreCase(name)) {
                return Optional.of(line.substring(colon + 1).strip());
            }
        }
        return Optional.empty();
    }
}
