package dev.nquangit.corestra.access.variable;

/**
 * Describes why a placeholder could not be resolved safely.
 */
public enum VariableErrorType {
    AMBIGUOUS_VARIABLE,
    INVALID_SCOPE,
    INVALID_KEY,
    MISSING_VARIABLE,
    MALFORMED_PLACEHOLDER,
    NESTED_PLACEHOLDER
}
