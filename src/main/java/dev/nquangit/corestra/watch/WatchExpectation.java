package dev.nquangit.corestra.watch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An expectation checked against the before/after values of matching snapshot keys.
 *
 * <p>{@code keyPattern} is a glob over snapshot keys ({@code *} any run of characters,
 * {@code ?} one character), e.g. {@code balance[0123]} or {@code balance[*]}.</p>
 *
 * <p>Expressions use a small deterministic arithmetic language over exact decimals:
 * numbers, {@code + - * /}, parentheses, unary minus, {@code min(a,b)}, {@code max(a,b)},
 * {@code abs(a)}, {@code round(a,scale)} (HALF_UP), and variables. Variables are request
 * variables written as {@code {{name}}} or bare identifiers, plus {@code before} and
 * {@code after} of the key being checked. Example DELTA_EQUALS expression:
 * {@code -{{amount}} - max({{amount}} * 0.011, 5000)}.</p>
 *
 * @param id          stable id
 * @param enabled     whether it is evaluated
 * @param keyPattern  glob of snapshot keys this applies to
 * @param type        check kind
 * @param expression  DELTA_EQUALS: expected delta; DELTA_BETWEEN and AFTER_AT_LEAST: lower bound
 * @param expression2 DELTA_BETWEEN: upper bound; otherwise unused
 * @param tolerance   absolute tolerance for DELTA_EQUALS as a decimal string ("" = 0)
 */
public record WatchExpectation(String id, boolean enabled, String keyPattern, Type type,
                               String expression, String expression2, String tolerance) {
    /** Expectation kinds. Append new values only. */
    public enum Type {
        /** Value must differ. */
        CHANGED,
        /** Value must be identical. */
        UNCHANGED,
        /** after - before must equal expression (± tolerance). */
        DELTA_EQUALS,
        /** expression ≤ after - before ≤ expression2. */
        DELTA_BETWEEN,
        /** after ≥ expression. */
        AFTER_AT_LEAST
    }

    /**
     * Normalizes null fields.
     *
     * @param id          id
     * @param enabled     enabled
     * @param keyPattern  key glob
     * @param type        type
     * @param expression  expression
     * @param expression2 second expression
     * @param tolerance   tolerance
     */
    @JsonCreator
    public WatchExpectation(@JsonProperty("id") String id,
                            @JsonProperty("enabled") boolean enabled,
                            @JsonProperty("keyPattern") String keyPattern,
                            @JsonProperty("type") Type type,
                            @JsonProperty("expression") String expression,
                            @JsonProperty("expression2") String expression2,
                            @JsonProperty("tolerance") String tolerance) {
        this.id = id == null ? "" : id;
        this.enabled = enabled;
        this.keyPattern = keyPattern == null ? "" : keyPattern.strip();
        this.type = type == null ? Type.CHANGED : type;
        this.expression = expression == null ? "" : expression.strip();
        this.expression2 = expression2 == null ? "" : expression2.strip();
        this.tolerance = tolerance == null ? "" : tolerance.strip();
    }
}
