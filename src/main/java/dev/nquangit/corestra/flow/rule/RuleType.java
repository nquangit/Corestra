package dev.nquangit.corestra.flow.rule;

/**
 * Identifies the broad category of a flow rule for serialization and UI presentation.
 */
public enum RuleType {
    /**
     * A rule that reads data from a request or response and stores it in the execution context.
     */
    EXTRACT,

    /**
     * A rule that writes data into a request before the step is executed.
     */
    INJECT
}
