package dev.nquangit.corestra.access.importer;

import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.VariableScope;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservatively maps external variable names to Corestra scoped placeholders.
 */
public final class ImportVariableMapper {
    private static final Pattern POSTMAN_VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    /**
     * Maps one external variable name to a Corestra scoped variable.
     *
     * @param variableName external variable name
     * @return scoped variable mapping
     */
    public ImportVariableMapping mapVariable(String variableName) {
        String name = normalizeVariableName(variableName);
        String lookup = name.toLowerCase(Locale.ROOT);
        return switch (lookup) {
            case "baseurl", "host", "tenant", "appversion" ->
                    mapping(name, VariableScope.ENV, name, true);
            case "token", "accesstoken", "bearertoken", "jwt" ->
                    mapping(name, VariableScope.AUTH, "accessToken", true);
            case "refreshtoken" ->
                    mapping(name, VariableScope.AUTH, "refreshToken", true);
            case "csrf", "csrftoken", "xsrftoken" ->
                    mapping(name, VariableScope.AUTH, "csrfToken", true);
            case "accountid", "cardid", "customerid", "userid", "walletid", "merchantid",
                    "transactionid", "orderid" ->
                    mapping(name, VariableScope.DATA, name, true);
            case "requestid", "correlationid" ->
                    mapping(name, VariableScope.RUNTIME, "uuid", true);
            case "nonce" ->
                    mapping(name, VariableScope.RUNTIME, "nonce", true);
            default -> mapping(name, VariableScope.LEGACY, name, false);
        };
    }

    /**
     * Maps one external variable name to a placeholder and records uncertainty warnings.
     *
     * @param variableName external variable name
     * @param warnings warnings collection to append to
     * @param location source location
     * @return mapped template placeholder
     */
    public String placeholderFor(String variableName, List<ImportWarning> warnings, String location) {
        ImportVariableMapping mapping = mapVariable(variableName);
        if (!mapping.certain()) {
            warnings.add(new ImportWarning(location,
                    "Variable {{" + mapping.originalName() + "}} was imported as "
                            + mapping.placeholder() + " because no safe scoped mapping was known."));
        }
        return mapping.placeholder();
    }

    /**
     * Replaces Postman-style placeholders in a value with Corestra scoped placeholders.
     *
     * @param value source value
     * @param warnings warnings collection to append to
     * @param location source location
     * @return value with placeholders mapped
     */
    public String replacePostmanVariables(String value, List<ImportWarning> warnings, String location) {
        if (value == null || value.isEmpty()) {
            return Objects.requireNonNullElse(value, "");
        }
        Matcher matcher = POSTMAN_VARIABLE.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(placeholderFor(matcher.group(1), warnings, location)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private ImportVariableMapping mapping(String originalName, VariableScope scope, String key, boolean certain) {
        return new ImportVariableMapping(originalName, new ScopedVariableRef(scope, normalizeVariableName(key)), certain);
    }

    private static String normalizeVariableName(String value) {
        String normalized = Objects.requireNonNullElse(value, "").strip();
        if (normalized.isBlank()) {
            return "value";
        }
        return normalized.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
