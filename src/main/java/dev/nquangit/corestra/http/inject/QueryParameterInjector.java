package dev.nquangit.corestra.http.inject;

import dev.nquangit.corestra.flow.rule.InjectRule.WriteStrategy;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Mutates only the selected query parameter while preserving all other raw query text. */
public final class QueryParameterInjector {
    /**
     * Injects a query parameter into a path or absolute HTTP request target.
     * Existing names are decoded only for matching; existing values are never decoded.
     * OVERWRITE replaces every matching value in place, APPEND adds another occurrence,
     * and SKIP_IF_PRESENT returns the original target if any matching name exists.
     *
     * @param target raw request target
     * @param name decoded parameter name, matched case-sensitively
     * @param value unencoded value to write
     * @param strategy write strategy, defaulting to OVERWRITE
     * @return updated target and whether an existing parameter caused a skip
     */
    public InjectionOutcome inject(String target, String name, String value, WriteStrategy strategy) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        WriteStrategy writeStrategy = Objects.requireNonNullElse(strategy, WriteStrategy.OVERWRITE);
        int queryStart = target.indexOf('?');
        String path = queryStart < 0 ? target : target.substring(0, queryStart);
        String query = queryStart < 0 ? "" : target.substring(queryStart + 1);
        String[] pairs = query.split("&", -1);
        boolean present = false;
        String encodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8);
        for (int index = 0; index < pairs.length; index++) {
            String pair = pairs[index];
            int equals = pair.indexOf('=');
            String rawName = equals < 0 ? pair : pair.substring(0, equals);
            if (!matches(rawName, name)) continue;
            present = true;
            if (writeStrategy == WriteStrategy.SKIP_IF_PRESENT) {
                return new InjectionOutcome(target, true);
            }
            if (writeStrategy == WriteStrategy.OVERWRITE) {
                pairs[index] = rawName + "=" + encodedValue;
            }
        }
        String updatedQuery = String.join("&", pairs);
        if (!present || writeStrategy == WriteStrategy.APPEND) {
            String newPair = URLEncoder.encode(name, StandardCharsets.UTF_8) + "=" + encodedValue;
            updatedQuery = updatedQuery.isEmpty() ? newPair : updatedQuery + "&" + newPair;
        }
        return new InjectionOutcome(path + "?" + updatedQuery, false);
    }

    private boolean matches(String rawName, String name) {
        try {
            return URLDecoder.decode(rawName, StandardCharsets.UTF_8).equals(name);
        } catch (IllegalArgumentException exception) {
            // Malformed escapes in unrelated names must remain usable in testing traffic.
            return rawName.equals(name);
        }
    }

    /** Result of query mutation, retaining the original target when a rule is skipped. */
    public record InjectionOutcome(String target, boolean skipped) { }
}
