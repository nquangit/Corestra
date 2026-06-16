package dev.nquangit.corestra.access.scenario;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Result returned after executing a setup flow or setup flow prefix.
 */
public final class ScenarioSetupRunResult {
    private final boolean success;
    private final String reason;
    private final Map<String, String> scenarioVariables;
    private final Map<String, String> rawExtractedVariables;
    private final List<String> warnings;

    /**
     * Creates a setup run result.
     *
     * @param success whether setup flow execution succeeded
     * @param reason human-readable reason
     * @param scenarioVariables extracted scenario variables keyed without {@code scenario.}
     * @param rawExtractedVariables raw variables extracted by flow steps
     * @param warnings non-fatal warnings
     */
    public ScenarioSetupRunResult(
            boolean success,
            String reason,
            Map<String, String> scenarioVariables,
            Map<String, String> rawExtractedVariables,
            List<String> warnings) {
        this.success = success;
        this.reason = reason == null ? "" : reason;
        this.scenarioVariables = Map.copyOf(Objects.requireNonNull(scenarioVariables, "scenarioVariables"));
        this.rawExtractedVariables = Map.copyOf(Objects.requireNonNull(rawExtractedVariables, "rawExtractedVariables"));
        this.warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    /**
     * Creates a successful setup run result.
     *
     * @param reason human-readable reason
     * @param scenarioVariables extracted scenario variables
     * @param rawExtractedVariables raw extracted variables
     * @param warnings warnings
     * @return successful result
     */
    public static ScenarioSetupRunResult success(
            String reason,
            Map<String, String> scenarioVariables,
            Map<String, String> rawExtractedVariables,
            List<String> warnings) {
        return new ScenarioSetupRunResult(true, reason,
                scenarioVariables == null ? Map.of() : scenarioVariables,
                rawExtractedVariables == null ? Map.of() : rawExtractedVariables,
                warnings == null ? List.of() : warnings);
    }

    /**
     * Creates a failed setup run result.
     *
     * @param reason failure reason
     * @param rawExtractedVariables raw extracted variables available at failure time
     * @param warnings warnings
     * @return failed result
     */
    public static ScenarioSetupRunResult failed(
            String reason,
            Map<String, String> rawExtractedVariables,
            List<String> warnings) {
        return new ScenarioSetupRunResult(false, reason, Map.of(),
                rawExtractedVariables == null ? Map.of() : rawExtractedVariables,
                warnings == null ? List.of() : warnings);
    }

    /**
     * Returns whether setup execution succeeded.
     *
     * @return true when successful
     */
    public boolean success() {
        return success;
    }

    /**
     * Returns whether setup execution failed.
     *
     * @return true when failed
     */
    public boolean failed() {
        return !success;
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
     * Returns extracted scenario variables keyed without {@code scenario.}.
     *
     * @return immutable scenario variable map
     */
    public Map<String, String> scenarioVariables() {
        return scenarioVariables;
    }

    /**
     * Returns raw extracted variables.
     *
     * @return immutable raw extracted variable map
     */
    public Map<String, String> rawExtractedVariables() {
        return rawExtractedVariables;
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
