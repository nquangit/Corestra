package dev.nquangit.corestra.http.extract;

import dev.nquangit.corestra.flow.rule.ExtractRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Builds and verifies an extraction regex from text selected in an HTTP message.
 *
 * <p>The generated expression always captures the selected value in group {@code 1}. It uses
 * the nearest literal prefix as an anchor, derives a bounded character class for the value, and
 * verifies the result against the complete selected source before returning it.</p>
 */
public final class RegexSuggestionService {
    private static final int MAX_PREFIX_LENGTH = 80;

    /**
     * Suggests a verified expression for a selection.
     *
     * @param snapshot HTTP message containing the selection
     * @param source regex input area in the message
     * @param selectionStart inclusive character offset in the selected source
     * @param selectionEnd exclusive character offset in the selected source
     * @return suggestion result
     */
    public SuggestionResult suggest(
            HttpMessageSnapshot snapshot,
            ExtractRule.RegexSource source,
            int selectionStart,
            int selectionEnd) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(source, "source");
        String input = sourceText(snapshot, source);
        if (selectionStart < 0 || selectionEnd > input.length() || selectionStart >= selectionEnd) {
            return SuggestionResult.failure("Select a non-empty value inside the " + source.name().toLowerCase() + ".");
        }

        String selected = input.substring(selectionStart, selectionEnd);
        char next = selectionEnd < input.length() ? input.charAt(selectionEnd) : 0;
        Suggestion fallback = null;
        for (String valuePattern : valuePatterns(selected, next)) {
            String suffix = suffixAnchor(valuePattern, next);
            int maxPrefix = Math.min(selectionStart, MAX_PREFIX_LENGTH);
            for (int length = 0; length <= maxPrefix; length++) {
                String prefix = escapeLiteral(input.substring(selectionStart - length, selectionStart));
                String expression = prefix + "(" + valuePattern + ")" + suffix;
                MatchCheck check = verify(expression, input, selected);
                if (!check.valid()) {
                    continue;
                }
                Suggestion suggestion = new Suggestion(
                        expression, 1, source, selected, check.matchCount(), check.matchCount() == 1);
                if (suggestion.unique()) {
                    return SuggestionResult.success(readableAnchor(
                            input, selectionStart, valuePattern, suffix, selected, source));
                }
                if (fallback == null) {
                    fallback = suggestion;
                }
            }
        }
        return fallback != null
                ? SuggestionResult.success(fallback)
                : SuggestionResult.failure("Could not build a regex that captures the selected value.");
    }

    private Suggestion readableAnchor(
            String input,
            int selectionStart,
            String valuePattern,
            String suffix,
            String selected,
            ExtractRule.RegexSource source) {
        int maxPrefix = Math.min(selectionStart, MAX_PREFIX_LENGTH);
        int chosen = 0;
        for (int length = 0; length <= maxPrefix; length++) {
            String regex = escapeLiteral(input.substring(selectionStart - length, selectionStart))
                    + "(" + valuePattern + ")" + suffix;
            MatchCheck check = verify(regex, input, selected);
            if (check.valid() && check.matchCount() == 1) {
                chosen = length;
                break;
            }
        }
        int readable = chosen;
        for (int length = chosen; length <= maxPrefix; length++) {
            int start = selectionStart - length;
            String prefix = input.substring(start, selectionStart);
            char before = start > 0 ? input.charAt(start - 1) : 0;
            readable = length;
            if ((start == 0 || isBoundary(before)) && prefix.chars().anyMatch(Character::isLetterOrDigit)) {
                break;
            }
        }
        String regex = escapeLiteral(input.substring(selectionStart - readable, selectionStart))
                + "(" + valuePattern + ")" + suffix;
        MatchCheck check = verify(regex, input, selected);
        if (!check.valid()) {
            regex = escapeLiteral(input.substring(selectionStart - chosen, selectionStart))
                    + "(" + valuePattern + ")" + suffix;
            check = verify(regex, input, selected);
        }
        return new Suggestion(regex, 1, source, selected, check.matchCount(), check.matchCount() == 1);
    }

    // Must match ExtractionService exactly; HEADERS previously omitted the start line here,
    // so suggestion offsets/uniqueness were computed against different text than runtime.
    private static String sourceText(HttpMessageSnapshot snapshot, ExtractRule.RegexSource source) {
        return snapshot.regexInput(source);
    }

    private static List<String> valuePatterns(String value, char next) {
        List<String> patterns = new ArrayList<>();
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            patterns.add("[\\s\\S]{" + value.length() + "}");
            return patterns;
        }
        if (Character.isWhitespace(next)) {
            patterns.add("\\S+");
        } else if (isDelimiter(next)) {
            patterns.add("[^" + escapeInClass(next) + "]+");
        }
        String characterClass = derivedClass(value);
        patterns.add(characterClass + "+");
        patterns.add(characterClass + "{" + value.length() + "}");
        if (next != 0) {
            patterns.add(characterClass + "+?");
        }
        return patterns.stream().distinct().toList();
    }

    private static String suffixAnchor(String valuePattern, char next) {
        if (next == 0) {
            return "";
        }
        if (valuePattern.endsWith("+?") || (isDelimiter(next) && valuePattern.startsWith("[^"))) {
            return escapeLiteral(String.valueOf(next));
        }
        return "";
    }

    private static MatchCheck verify(String regex, String input, String selected) {
        try {
            Matcher matcher = Pattern.compile(regex).matcher(input);
            if (!matcher.find() || matcher.groupCount() < 1 || !selected.equals(matcher.group(1))) {
                return MatchCheck.invalid();
            }
            int count = 1;
            while (matcher.find()) {
                count++;
            }
            return new MatchCheck(true, count);
        } catch (PatternSyntaxException exception) {
            return MatchCheck.invalid();
        }
    }

    private static String derivedClass(String value) {
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        boolean dash = false;
        StringBuilder special = new StringBuilder();
        for (char character : value.toCharArray()) {
            if (character >= 'A' && character <= 'Z') upper = true;
            else if (character >= 'a' && character <= 'z') lower = true;
            else if (character >= '0' && character <= '9') digit = true;
            else if (character == '-') dash = true;
            else {
                String escaped = escapeInClass(character);
                if (special.indexOf(escaped) < 0) special.append(escaped);
            }
        }
        StringBuilder result = new StringBuilder("[");
        if (upper) result.append("A-Z");
        if (lower) result.append("a-z");
        if (digit) result.append("0-9");
        result.append(special);
        if (dash) result.append('-');
        if (result.length() == 1) result.append("\\S");
        return result.append(']').toString();
    }

    private static String escapeLiteral(String value) {
        StringBuilder escaped = new StringBuilder();
        for (char character : value.toCharArray()) {
            switch (character) {
                case '\\', '.', '[', ']', '{', '}', '(', ')', '*', '+', '?', '^', '$', '|' ->
                        escaped.append('\\').append(character);
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private static String escapeInClass(char character) {
        return switch (character) {
            case '\\', ']', '^', '-', '[' -> "\\" + character;
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            default -> String.valueOf(character);
        };
    }

    private static boolean isDelimiter(char character) {
        return "\"'<>&,;|}]\\)".indexOf(character) >= 0;
    }

    private static boolean isBoundary(char character) {
        return "\"'{[(,>=:\n\r &?".indexOf(character) >= 0;
    }

    /** A verified regex suggestion. */
    public record Suggestion(
            String regex,
            int captureGroup,
            ExtractRule.RegexSource source,
            String selectedValue,
            int matchCount,
            boolean unique) {
    }

    /** Success/failure wrapper for a suggestion attempt. */
    public record SuggestionResult(Suggestion suggestion, String failureReason) {
        /** Returns a successful result. */
        public static SuggestionResult success(Suggestion suggestion) {
            return new SuggestionResult(Objects.requireNonNull(suggestion), "");
        }

        /** Returns a failed result. */
        public static SuggestionResult failure(String reason) {
            return new SuggestionResult(null, Objects.requireNonNullElse(reason, "Unknown failure"));
        }

        /** Returns whether a suggestion was generated. */
        public boolean success() {
            return suggestion != null;
        }
    }

    private record MatchCheck(boolean valid, int matchCount) {
        private static MatchCheck invalid() {
            return new MatchCheck(false, 0);
        }
    }
}
