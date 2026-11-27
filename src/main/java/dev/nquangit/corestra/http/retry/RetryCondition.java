package dev.nquangit.corestra.http.retry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.nquangit.corestra.flow.control.StepConditionOperator;

import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * One comparison inside a {@link RetryRule}. Conditions with the same {@link #group()} must all
 * match (AND); the rule matches when any group matches (OR). With {@link #negate()} this expresses
 * any boolean combination.
 *
 * @param enabled       disabled conditions are ignored
 * @param group         OR-group number, {@code >= 1}
 * @param source        value to read
 * @param selector      header name or JSON path when {@link RetryConditionSource#needsSelector()}
 * @param operator      comparison (same semantics as Flowise conditions)
 * @param value         expected value; comma-separated list for {@code IN}
 * @param caseSensitive compare text case-sensitively (regex uses {@code (?i)} otherwise)
 * @param negate        invert the result
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RetryCondition(boolean enabled, int group, RetryConditionSource source, String selector,
                             StepConditionOperator operator, String value, boolean caseSensitive, boolean negate) {

    /** Highest OR-group number. */
    public static final int MAX_GROUP = 20;

    /** Validates and normalizes the condition. */
    public RetryCondition {
        group = group == 0 ? 1 : group;
        if (group < 1 || group > MAX_GROUP) throw new IllegalArgumentException("Condition group must be 1-" + MAX_GROUP);
        source = Objects.requireNonNullElse(source, RetryConditionSource.STATUS);
        operator = Objects.requireNonNullElse(operator, StepConditionOperator.EQUALS);
        selector = selector == null ? "" : selector.strip();
        value = value == null ? "" : value;
        if (source.needsSelector() && selector.isEmpty()) {
            throw new IllegalArgumentException("Condition on " + source.label() + " needs a header name or JSON path");
        }
        boolean valueless = operator == StepConditionOperator.EXISTS || operator == StepConditionOperator.NOT_EXISTS;
        if (enabled && !valueless && value.isBlank()) {
            throw new IllegalArgumentException("Condition " + source.label() + " " + operator + " needs a value");
        }
        if (enabled && operator == StepConditionOperator.MATCHES_REGEX) {
            try {
                Pattern.compile(value);
            } catch (PatternSyntaxException exception) {
                throw new IllegalArgumentException("Invalid regex '" + value + "': " + exception.getDescription());
            }
        }
        if (enabled && isNumeric(operator)) {
            try {
                Double.parseDouble(value.strip());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Numeric comparison needs a number, got '" + value + "'");
            }
        }
    }

    /**
     * Creates an enabled, case-insensitive, non-negated condition in group 1.
     *
     * @param source   source
     * @param selector selector, may be blank
     * @param operator operator
     * @param value    expected value
     * @return condition
     */
    public static RetryCondition of(RetryConditionSource source, String selector, StepConditionOperator operator,
                                    String value) {
        return new RetryCondition(true, 1, source, selector, operator, value, false, false);
    }

    /** Returns a copy in another OR-group. */
    public RetryCondition inGroup(int newGroup) {
        return new RetryCondition(enabled, newGroup, source, selector, operator, value, caseSensitive, negate);
    }

    /** Returns a copy with the result inverted. */
    public RetryCondition negated() {
        return new RetryCondition(enabled, group, source, selector, operator, value, caseSensitive, !negate);
    }

    /** Returns a readable form such as {@code header[Server] contains "cloudflare"}. */
    public String describe() {
        String subject = source.needsSelector() ? source.label() + "[" + selector + "]" : source.label();
        String text = switch (operator) {
            case EXISTS -> subject + " exists";
            case NOT_EXISTS -> subject + " missing";
            case EQUALS -> subject + " = " + quote(value);
            case NOT_EQUALS -> subject + " != " + quote(value);
            case CONTAINS -> subject + " contains " + quote(value);
            case NOT_CONTAINS -> subject + " not contains " + quote(value);
            case MATCHES_REGEX -> subject + " ~ /" + value + "/";
            case IN -> subject + " in [" + value + "]";
            case NUMERIC_GT -> subject + " > " + value.strip();
            case NUMERIC_GTE -> subject + " >= " + value.strip();
            case NUMERIC_LT -> subject + " < " + value.strip();
            case NUMERIC_LTE -> subject + " <= " + value.strip();
        };
        if (caseSensitive && isTextual(operator)) text += " (case)";
        return negate ? "NOT (" + text + ")" : text;
    }

    static boolean isNumeric(StepConditionOperator operator) {
        return operator == StepConditionOperator.NUMERIC_GT || operator == StepConditionOperator.NUMERIC_GTE
                || operator == StepConditionOperator.NUMERIC_LT || operator == StepConditionOperator.NUMERIC_LTE;
    }

    private static boolean isTextual(StepConditionOperator operator) {
        return !isNumeric(operator) && operator != StepConditionOperator.EXISTS
                && operator != StepConditionOperator.NOT_EXISTS;
    }

    private static String quote(String text) {
        return "\"" + text + "\"";
    }
}
