package dev.nquangit.corestra.flow.control;

/**
 * Defines how enabled conditions inside a condition/action rule are combined.
 */
public enum ConditionMatchPolicy {
    /**
     * All enabled conditions must match.
     */
    ALL,

    /**
     * At least one enabled condition must match.
     */
    ANY
}
