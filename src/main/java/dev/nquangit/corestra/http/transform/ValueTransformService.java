package dev.nquangit.corestra.http.transform;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.PatternSyntaxException;

/**
 * Applies ordered, persisted text transforms to extracted or injected values.
 *
 * <p>Supported specifications are {@code trim}, {@code lowercase}, {@code uppercase},
 * {@code urlDecode}, {@code urlEncode}, {@code base64Decode}, {@code base64Encode},
 * {@code substring:START[:END]}, {@code prefix:TEXT}, {@code suffix:TEXT},
 * {@code stripPrefix:TEXT}, {@code stripSuffix:TEXT}, and
 * {@code replaceRegex:PATTERN=>REPLACEMENT}.</p>
 */
public final class ValueTransformService {

    /**
     * Applies transforms in declaration order.
     *
     * @param input source value
     * @param transforms transform specifications
     * @return transformed value or an actionable failure
     */
    public ValueTransformResult apply(String input, List<String> transforms) {
        String value = Objects.requireNonNull(input, "input");
        List<String> chain = transforms == null ? List.of() : transforms;
        for (int index = 0; index < chain.size(); index++) {
            String specification = chain.get(index);
            if (specification == null || specification.isBlank()) {
                continue;
            }
            try {
                // Leading whitespace is formatting noise, while trailing whitespace may be
                // an intentional prefix/suffix argument (for example "prefix:Bearer ").
                value = applyOne(value, specification.stripLeading());
            } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
                return ValueTransformResult.failure(value,
                        "Transform #" + (index + 1) + " '" + specification + "' failed: "
                                + exception.getMessage());
            }
        }
        return ValueTransformResult.success(value);
    }

    private String applyOne(String value, String specification) {
        String operation = operation(specification).toLowerCase(Locale.ROOT);
        String argument = argument(specification);
        return switch (operation) {
            case "trim" -> value.strip();
            case "lowercase", "lower" -> value.toLowerCase(Locale.ROOT);
            case "uppercase", "upper" -> value.toUpperCase(Locale.ROOT);
            case "urldecode" -> URLDecoder.decode(value, StandardCharsets.UTF_8);
            case "urlencode" -> URLEncoder.encode(value, StandardCharsets.UTF_8);
            case "base64decode" -> new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            case "base64encode" -> Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
            case "substring" -> substring(value, requiredArgument(operation, argument));
            case "prefix" -> requiredArgument(operation, argument) + value;
            case "suffix" -> value + requiredArgument(operation, argument);
            case "stripprefix" -> value.startsWith(requiredArgument(operation, argument))
                    ? value.substring(argument.length()) : value;
            case "stripsuffix" -> value.endsWith(requiredArgument(operation, argument))
                    ? value.substring(0, value.length() - argument.length()) : value;
            case "replaceregex" -> replaceRegex(value, requiredArgument(operation, argument));
            default -> throw new IllegalArgumentException("unknown transform");
        };
    }

    private String substring(String value, String argument) {
        String[] bounds = argument.split(":", -1);
        if (bounds.length > 2 || bounds[0].isBlank()) {
            throw new IllegalArgumentException("expected substring:START[:END]");
        }
        int start = parseIndex(bounds[0], "start");
        int end = bounds.length == 2 && !bounds[1].isBlank()
                ? parseIndex(bounds[1], "end") : value.length();
        if (start < 0 || end < start || end > value.length()) {
            throw new IndexOutOfBoundsException(
                    "range " + start + ":" + end + " is outside value length " + value.length());
        }
        return value.substring(start, end);
    }

    private String replaceRegex(String value, String argument) {
        int separator = argument.indexOf("=>");
        if (separator < 0) {
            throw new IllegalArgumentException("expected replaceRegex:PATTERN=>REPLACEMENT");
        }
        String pattern = argument.substring(0, separator);
        String replacement = argument.substring(separator + 2);
        try {
            return value.replaceAll(pattern, replacement);
        } catch (PatternSyntaxException exception) {
            throw new IllegalArgumentException("invalid regex: " + exception.getDescription(), exception);
        }
    }

    private int parseIndex(String raw, String name) {
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " index is not an integer: " + raw, exception);
        }
    }

    private String requiredArgument(String operation, String argument) {
        if (argument == null) {
            throw new IllegalArgumentException(operation + " requires an argument");
        }
        return argument;
    }

    private String operation(String specification) {
        int separator = specification.indexOf(':');
        return separator < 0 ? specification : specification.substring(0, separator);
    }

    private String argument(String specification) {
        int separator = specification.indexOf(':');
        return separator < 0 ? null : specification.substring(separator + 1);
    }
}
