package dev.nquangit.corestra.flow.control;

/**
 * Describes where a condition's expected comparison value comes from.
 */
public enum ConditionValueSource {
    /**
     * Uses the literal value stored on the condition.
     */
    STATIC,

    /**
     * Resolves the condition value from the current execution variable map.
     */
    VARIABLE
}
