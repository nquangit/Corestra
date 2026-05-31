package dev.nquangit.corestra.http.extract;

import java.util.Objects;

/**
 * Extracts a named cookie value from a cookie header string.
 *
 * <p>Accepts the raw value of a {@code Cookie} request header or the relevant portion of a
 * {@code Set-Cookie} response header in {@code "name=value; name2=value2"} format.
 */
public final class CookieExtractor {

    /**
     * Extracts the value of a named cookie from a cookie header string.
     *
     * <p>The {@code cookieHeaderValue} should be the raw value portion of a {@code Cookie} or
     * {@code Set-Cookie} header, e.g. {@code "session=abc; token=xyz"}.
     *
     * @param cookieHeaderValue the raw cookie string; must not be {@code null}
     * @param cookieName        the name of the cookie to locate (case-sensitive); must not be {@code null}
     * @param targetVariable    the variable name to associate with the result
     * @return a successful {@link ExtractionResult} containing the cookie value, or a failure if
     *         the named cookie is not found or the string is blank
     */
    public ExtractionResult extract(String cookieHeaderValue, String cookieName, String targetVariable) {
        Objects.requireNonNull(cookieHeaderValue, "cookieHeaderValue");
        Objects.requireNonNull(cookieName, "cookieName");
        Objects.requireNonNull(targetVariable, "targetVariable");

        String normalized = cookieName.strip();

        for (String pair : cookieHeaderValue.split(";")) {
            String trimmed = pair.strip();
            int eqIndex = trimmed.indexOf('=');
            if (eqIndex < 0) {
                continue;
            }
            String name = trimmed.substring(0, eqIndex).strip();
            if (name.equals(normalized)) {
                String value = trimmed.substring(eqIndex + 1).strip();
                return ExtractionResult.success(targetVariable, value);
            }
        }

        return ExtractionResult.failure(targetVariable,
                "Cookie '" + cookieName + "' not found");
    }
}
