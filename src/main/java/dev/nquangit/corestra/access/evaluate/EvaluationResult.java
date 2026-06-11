package dev.nquangit.corestra.access.evaluate;

import dev.nquangit.corestra.access.result.Verdict;

import java.util.List;
import java.util.Objects;

/**
 * Result of evaluating an access-test HTTP response against an expected outcome.
 */
public final class EvaluationResult {
    private final Verdict verdict;
    private final String reason;
    private final List<String> warnings;

    /**
     * Creates an evaluation result.
     *
     * @param verdict security verdict
     * @param reason human-readable reason
     * @param warnings non-fatal warnings
     */
    public EvaluationResult(Verdict verdict, String reason, List<String> warnings) {
        this.verdict = Objects.requireNonNull(verdict, "verdict");
        this.reason = Objects.requireNonNullElse(reason, "");
        this.warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
    }

    /**
     * Returns the security verdict.
     *
     * @return security verdict
     */
    public Verdict verdict() {
        return verdict;
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
     * Returns non-fatal warnings.
     *
     * @return immutable warnings
     */
    public List<String> warnings() {
        return warnings;
    }
}
