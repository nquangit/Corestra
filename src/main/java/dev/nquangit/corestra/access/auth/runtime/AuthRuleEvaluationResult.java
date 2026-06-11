package dev.nquangit.corestra.access.auth.runtime;

import java.util.List;
import java.util.Objects;

/**
 * Result of evaluating AuthProvider success and failure rules.
 */
public final class AuthRuleEvaluationResult {
    private final boolean success;
    private final String reason;
    private final List<String> warnings;

    /**
     * Creates an auth rule evaluation result.
     *
     * @param success whether rules indicate success
     * @param reason human-readable reason
     * @param warnings warnings
     */
    public AuthRuleEvaluationResult(boolean success, String reason, List<String> warnings) {
        this.success = success;
        this.reason = reason == null ? "" : reason;
        this.warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    /**
     * Creates a successful evaluation result.
     *
     * @param reason reason
     * @param warnings warnings
     * @return successful evaluation
     */
    public static AuthRuleEvaluationResult success(String reason, List<String> warnings) {
        return new AuthRuleEvaluationResult(true, reason, warnings == null ? List.of() : warnings);
    }

    /**
     * Creates a failed evaluation result.
     *
     * @param reason reason
     * @param warnings warnings
     * @return failed evaluation
     */
    public static AuthRuleEvaluationResult failure(String reason, List<String> warnings) {
        return new AuthRuleEvaluationResult(false, reason, warnings == null ? List.of() : warnings);
    }

    /**
     * Returns whether rules indicate success.
     *
     * @return true when successful
     */
    public boolean success() {
        return success;
    }

    /**
     * Returns the human-readable reason.
     *
     * @return reason
     */
    public String reason() {
        return reason;
    }

    /**
     * Returns warnings emitted during evaluation.
     *
     * @return immutable warnings
     */
    public List<String> warnings() {
        return warnings;
    }
}
