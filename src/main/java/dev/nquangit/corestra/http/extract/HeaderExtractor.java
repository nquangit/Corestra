package dev.nquangit.corestra.http.extract;

import java.util.List;
import java.util.Objects;

/**
 * Extracts the value of a named HTTP header from a list of raw header lines.
 *
 * <p>Each element of the header list is expected to be in {@code "Name: Value"} format.
 * Header name matching is case-insensitive, consistent with RFC 7230.
 */
public final class HeaderExtractor {

    /**
     * Extracts the value of the first header whose name matches {@code headerName}.
     *
     * @param headers     raw header lines in {@code "Name: Value"} format; must not be {@code null}
     * @param headerName  the header name to locate (case-insensitive); must not be {@code null}
     * @param targetVariable the variable name to associate with the result
     * @return a successful {@link ExtractionResult} containing the header value, or a failure if
     *         the header is not found or the header list is empty
     */
    public ExtractionResult extract(List<String> headers, String headerName, String targetVariable) {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(headerName, "headerName");
        Objects.requireNonNull(targetVariable, "targetVariable");

        String normalizedName = headerName.strip();

        for (String headerLine : headers) {
            int colonIndex = headerLine.indexOf(':');
            if (colonIndex < 0) {
                continue;
            }
            String name = headerLine.substring(0, colonIndex).strip();
            if (name.equalsIgnoreCase(normalizedName)) {
                String value = headerLine.substring(colonIndex + 1).strip();
                return ExtractionResult.success(targetVariable, value);
            }
        }

        return ExtractionResult.failure(targetVariable,
                "Header '" + headerName + "' not found");
    }
}
