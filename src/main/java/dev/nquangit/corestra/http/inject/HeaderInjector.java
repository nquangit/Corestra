package dev.nquangit.corestra.http.inject;

import dev.nquangit.corestra.flow.rule.InjectRule.WriteStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Injects a value into a named HTTP request header.
 *
 * <p>Header lines are expected in {@code "Name: Value"} format. Matching is case-insensitive,
 * consistent with RFC 7230.
 */
public final class HeaderInjector {

    /**
     * Injects {@code value} into the header identified by {@code headerName}.
     *
     * <p>Behaviour is controlled by {@code strategy}:
     * <ul>
     *   <li>{@link WriteStrategy#OVERWRITE} — replaces the first matching header and removes any
     *       additional headers with the same name.</li>
     *   <li>{@link WriteStrategy#APPEND} — adds a new header even when one with the same name
     *       already exists.</li>
     *   <li>{@link WriteStrategy#SKIP_IF_PRESENT} — returns the headers unchanged when a header
     *       with the same name already exists (case-insensitive).</li>
     * </ul>
     *
     * @param headers    the current header lines in {@code "Name: Value"} format; must not be {@code null}
     * @param headerName the header name to target (case-insensitive); must not be {@code null}
     * @param value      the value to inject; must not be {@code null}
     * @param strategy   the write strategy; must not be {@code null}
     * @return an updated list of header lines reflecting the injection
     */
    public List<String> inject(List<String> headers, String headerName, String value, WriteStrategy strategy) {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(headerName, "headerName");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(strategy, "strategy");

        String normalizedName = headerName.strip();
        String newLine = normalizedName + ": " + value;

        if (strategy == WriteStrategy.SKIP_IF_PRESENT) {
            boolean exists = headers.stream().anyMatch(h -> matchesName(h, normalizedName));
            if (exists) {
                return headers;
            }
            // fall through to add the header since it is not present
            List<String> result = new ArrayList<>(headers);
            result.add(newLine);
            return result;
        }

        if (strategy == WriteStrategy.APPEND) {
            List<String> result = new ArrayList<>(headers);
            result.add(newLine);
            return result;
        }

        // OVERWRITE: replace first match, drop subsequent duplicates
        List<String> result = new ArrayList<>();
        boolean replaced = false;
        for (String line : headers) {
            if (matchesName(line, normalizedName)) {
                if (!replaced) {
                    result.add(newLine);
                    replaced = true;
                }
                // drop subsequent duplicates
            } else {
                result.add(line);
            }
        }
        if (!replaced) {
            result.add(newLine);
        }
        return result;
    }

    /**
     * Returns {@code true} when the header line's name matches {@code targetName} case-insensitively.
     */
    private boolean matchesName(String headerLine, String targetName) {
        int colon = headerLine.indexOf(':');
        if (colon < 0) {
            return false;
        }
        return headerLine.substring(0, colon).strip().equalsIgnoreCase(targetName);
    }
}
