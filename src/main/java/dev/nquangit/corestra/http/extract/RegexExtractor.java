package dev.nquangit.corestra.http.extract;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Extracts a value from an arbitrary string using a regular expression.
 *
 * <p>Group index {@code 0} returns the full match. Any positive index returns the corresponding
 * capturing group. Named groups are not currently supported through this API; use numeric
 * group indices instead.
 */
public final class RegexExtractor {

    /**
     * Applies {@code pattern} to {@code input} and returns the value of the indicated group.
     *
     * @param input          the string to search; must not be {@code null}
     * @param pattern        the regular expression; must not be {@code null}
     * @param groupIndex     the capture group index; {@code 0} returns the full match
     * @param targetVariable the variable name to associate with the result
     * @return a successful {@link ExtractionResult} when the pattern matches and the group exists,
     *         otherwise a failure
     */
    public ExtractionResult extract(String input, String pattern, int groupIndex, String targetVariable) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(targetVariable, "targetVariable");

        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return ExtractionResult.failure(targetVariable,
                    "Invalid regex pattern: " + e.getMessage());
        }

        Matcher matcher = compiled.matcher(input);
        if (!matcher.find()) {
            return ExtractionResult.failure(targetVariable,
                    "Pattern '" + pattern + "' did not match");
        }

        if (groupIndex < 0 || groupIndex > matcher.groupCount()) {
            return ExtractionResult.failure(targetVariable,
                    "Group index " + groupIndex + " is out of bounds (pattern has "
                            + matcher.groupCount() + " group(s))");
        }

        String matched = matcher.group(groupIndex);
        if (matched == null) {
            return ExtractionResult.failure(targetVariable,
                    "Group " + groupIndex + " did not participate in the match");
        }

        return ExtractionResult.success(targetVariable, matched);
    }
}
