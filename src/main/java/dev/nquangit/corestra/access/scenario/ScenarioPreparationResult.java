package dev.nquangit.corestra.access.scenario;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result returned after preparing scenario state for an access test case.
 */
public final class ScenarioPreparationResult {
    private final boolean success;
    private final Optional<ScenarioInstance> scenario;
    private final String reason;
    private final List<String> warnings;

    /**
     * Creates a scenario preparation result.
     *
     * @param success whether preparation succeeded
     * @param scenario prepared scenario, if any
     * @param reason human-readable reason
     * @param warnings non-fatal warnings
     */
    public ScenarioPreparationResult(
            boolean success,
            Optional<ScenarioInstance> scenario,
            String reason,
            List<String> warnings) {
        this.success = success;
        this.scenario = Objects.requireNonNull(scenario, "scenario");
        this.reason = reason == null ? "" : reason;
        this.warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    /**
     * Creates a successful preparation result.
     *
     * @param scenario prepared scenario, if any
     * @param reason human-readable reason
     * @param warnings non-fatal warnings
     * @return successful result
     */
    public static ScenarioPreparationResult success(
            Optional<ScenarioInstance> scenario,
            String reason,
            List<String> warnings) {
        return new ScenarioPreparationResult(true, scenario, reason, warnings == null ? List.of() : warnings);
    }

    /**
     * Creates a failed preparation result.
     *
     * @param reason failure reason
     * @param warnings non-fatal warnings
     * @return failed result
     */
    public static ScenarioPreparationResult failed(String reason, List<String> warnings) {
        return new ScenarioPreparationResult(false, Optional.empty(), reason, warnings == null ? List.of() : warnings);
    }

    /**
     * Returns whether preparation succeeded.
     *
     * @return true when successful
     */
    public boolean success() {
        return success;
    }

    /**
     * Returns whether preparation failed.
     *
     * @return true when failed
     */
    public boolean failed() {
        return !success;
    }

    /**
     * Returns the prepared scenario.
     *
     * @return prepared scenario, if any
     */
    public Optional<ScenarioInstance> scenario() {
        return scenario;
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
     * Returns warnings produced during preparation.
     *
     * @return immutable warnings
     */
    public List<String> warnings() {
        return warnings;
    }
}
