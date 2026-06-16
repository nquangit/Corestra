package dev.nquangit.corestra.flow.control;

/**
 * Enumerates comparison operators for step conditions.
 */
public enum StepConditionOperator {
    /**
     * Matches when the selected value exists.
     */
    EXISTS,

    /**
     * Matches when the selected value does not exist.
     */
    NOT_EXISTS,

    /**
     * Matches when the selected value equals the expected value.
     */
    EQUALS,

    /**
     * Matches when the selected value does not equal the expected value.
     */
    NOT_EQUALS,

    /**
     * Matches when the selected value contains the expected value.
     */
    CONTAINS,

    /**
     * Matches when the selected value does not contain the expected value.
     */
    NOT_CONTAINS,

    /**
     * Matches when the selected value satisfies the expected regular expression.
     */
    MATCHES_REGEX,

    /**
     * Matches when the selected value is one of a comma-separated set of expected values.
     */
    IN,

    /**
     * Matches when the selected numeric value is greater than the expected value.
     */
    NUMERIC_GT,

    /**
     * Matches when the selected numeric value is greater than or equal to the expected value.
     */
    NUMERIC_GTE,

    /**
     * Matches when the selected numeric value is less than the expected value.
     */
    NUMERIC_LT,

    /**
     * Matches when the selected numeric value is less than or equal to the expected value.
     */
    NUMERIC_LTE
}
