package dev.nquangit.corestra.http.inject;

import dev.nquangit.corestra.flow.rule.InjectRule.WriteStrategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Injects a cookie name=value pair into the {@code Cookie} request header.
 *
 * <p>The existing {@code Cookie} header is parsed, the named cookie is added or updated
 * according to the write strategy, and the header is rebuilt from the resulting map.
 * Cookie header matching is case-insensitive.
 */
public final class CookieInjector {

    private static final String COOKIE_HEADER = "Cookie";

    /**
     * Injects the cookie {@code cookieName=value} into the headers.
     *
     * <p>Strategy semantics:
     * <ul>
     *   <li>{@link WriteStrategy#OVERWRITE} — replaces an existing cookie with the same name.</li>
     *   <li>{@link WriteStrategy#APPEND} — adds the cookie regardless of whether one with the same
     *       name already exists; the pre-existing cookie is preserved alongside the new one.</li>
     *   <li>{@link WriteStrategy#SKIP_IF_PRESENT} — returns the headers unchanged when a cookie
     *       with the same name already exists.</li>
     * </ul>
     *
     * @param headers    the current header lines in {@code "Name: Value"} format; must not be {@code null}
     * @param cookieName the name of the cookie to inject; must not be {@code null}
     * @param value      the cookie value to inject; must not be {@code null}
     * @param strategy   the write strategy; must not be {@code null}
     * @return an updated list of header lines with the {@code Cookie} header reflecting the injection
     */
    public List<String> inject(List<String> headers, String cookieName, String value, WriteStrategy strategy) {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(cookieName, "cookieName");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(strategy, "strategy");

        String existingCookieValue = findCookieHeaderValue(headers);

        // Parse existing cookies preserving insertion order
        Map<String, String> cookies = parseCookies(existingCookieValue);

        boolean alreadyPresent = cookies.containsKey(cookieName);

        if (strategy == WriteStrategy.SKIP_IF_PRESENT && alreadyPresent) {
            return headers;
        }

        if (strategy == WriteStrategy.APPEND && alreadyPresent) {
            // For APPEND when already present: keep existing and add the new one alongside it
            // by appending to the raw header value, since a Map cannot hold duplicate keys.
            return rebuildWithAppend(headers, existingCookieValue, cookieName, value);
        }

        // OVERWRITE, or APPEND when cookie name is not yet present, or SKIP_IF_PRESENT when not present
        cookies.put(cookieName, value);

        String newCookieValue = buildCookieValue(cookies);
        return replaceCookieHeader(headers, newCookieValue);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String findCookieHeaderValue(List<String> headers) {
        for (String line : headers) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String name = line.substring(0, colon).strip();
            if (name.equalsIgnoreCase(COOKIE_HEADER)) {
                return line.substring(colon + 1).strip();
            }
        }
        return "";
    }

    private Map<String, String> parseCookies(String cookieHeaderValue) {
        Map<String, String> map = new LinkedHashMap<>();
        if (cookieHeaderValue == null || cookieHeaderValue.isBlank()) {
            return map;
        }
        for (String pair : cookieHeaderValue.split(";")) {
            pair = pair.strip();
            int eq = pair.indexOf('=');
            if (eq < 0) {
                map.put(pair, "");
            } else {
                map.put(pair.substring(0, eq).strip(), pair.substring(eq + 1).strip());
            }
        }
        return map;
    }

    private String buildCookieValue(Map<String, String> cookies) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    private List<String> replaceCookieHeader(List<String> headers, String newCookieValue) {
        List<String> result = new ArrayList<>();
        boolean replaced = false;
        for (String line : headers) {
            int colon = line.indexOf(':');
            if (colon >= 0 && line.substring(0, colon).strip().equalsIgnoreCase(COOKIE_HEADER)) {
                if (!replaced) {
                    result.add(COOKIE_HEADER + ": " + newCookieValue);
                    replaced = true;
                }
            } else {
                result.add(line);
            }
        }
        if (!replaced) {
            result.add(COOKIE_HEADER + ": " + newCookieValue);
        }
        return result;
    }

    /**
     * Rebuilds headers for APPEND when the cookie name already exists, appending the new
     * name=value pair to the raw Cookie header value without removing the existing one.
     */
    private List<String> rebuildWithAppend(List<String> headers, String existingCookieValue, String cookieName, String value) {
        String appended = existingCookieValue.isBlank()
                ? cookieName + "=" + value
                : existingCookieValue + "; " + cookieName + "=" + value;
        return replaceCookieHeader(headers, appended);
    }
}
