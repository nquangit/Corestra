package dev.nquangit.corestra.access.auth.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Result of executing an AuthProvider login, refresh, or validate flow.
 */
public final class AuthFlowRunResult {
    private final boolean executed;
    private final boolean success;
    private final String reason;
    private final int finalStatusCode;
    private final Map<String, String> extractedVariables;
    private final String finalResponseRaw;
    private final List<String> warnings;

    /**
     * Creates an auth flow run result.
     *
     * @param executed whether a flow was executed
     * @param success whether execution succeeded
     * @param reason human-readable reason
     * @param finalStatusCode final HTTP status code, or 0 when unavailable
     * @param extractedVariables merged extracted variables
     * @param finalResponseRaw final raw response, or empty when unavailable
     * @param warnings warnings
     */
    public AuthFlowRunResult(
            boolean executed,
            boolean success,
            String reason,
            int finalStatusCode,
            Map<String, String> extractedVariables,
            String finalResponseRaw,
            List<String> warnings) {
        this.executed = executed;
        this.success = success;
        this.reason = reason == null ? "" : reason;
        this.finalStatusCode = finalStatusCode;
        this.extractedVariables = Map.copyOf(Objects.requireNonNull(extractedVariables, "extractedVariables"));
        this.finalResponseRaw = finalResponseRaw == null ? "" : finalResponseRaw;
        this.warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    /**
     * Creates a not-executed result.
     *
     * @param reason reason
     * @return not-executed result
     */
    public static AuthFlowRunResult notExecuted(String reason) {
        return new AuthFlowRunResult(false, false, reason, 0, Map.of(), "", List.of());
    }

    /**
     * Creates an executed result.
     *
     * @param success whether execution succeeded
     * @param reason reason
     * @param finalStatusCode final HTTP status code
     * @param extractedVariables extracted variables
     * @param finalResponseRaw final raw response
     * @param warnings warnings
     * @return executed result
     */
    public static AuthFlowRunResult executed(
            boolean success,
            String reason,
            int finalStatusCode,
            Map<String, String> extractedVariables,
            String finalResponseRaw,
            List<String> warnings) {
        return new AuthFlowRunResult(true, success, reason, finalStatusCode,
                extractedVariables == null ? Map.of() : extractedVariables,
                finalResponseRaw,
                warnings == null ? List.of() : warnings);
    }

    /**
     * Returns whether a flow was executed.
     *
     * @return true when executed
     */
    public boolean executed() {
        return executed;
    }

    /**
     * Returns whether execution succeeded.
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
     * Returns the final HTTP status code.
     *
     * @return final status code, or 0
     */
    public int finalStatusCode() {
        return finalStatusCode;
    }

    /**
     * Returns merged extracted variables.
     *
     * @return immutable extracted variables
     */
    public Map<String, String> extractedVariables() {
        return extractedVariables;
    }

    /**
     * Returns the final raw response.
     *
     * @return final raw response, or empty
     */
    public String finalResponseRaw() {
        return finalResponseRaw;
    }

    /**
     * Returns warnings emitted during execution.
     *
     * @return immutable warnings
     */
    public List<String> warnings() {
        return warnings;
    }
}
