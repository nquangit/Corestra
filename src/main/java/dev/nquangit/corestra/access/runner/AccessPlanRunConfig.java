package dev.nquangit.corestra.access.runner;

import dev.nquangit.corestra.access.variable.RenderMode;

import java.util.Map;
import java.util.Objects;

/**
 * Configuration for one sequential access plan run.
 */
public final class AccessPlanRunConfig {
    private final RenderMode renderMode;
    private final Map<String, String> env;
    private final Map<String, String> scenario;
    private final Map<String, String> legacy;
    private final boolean stopOnFirstError;
    private final boolean includeDisabledRows;
    private final boolean dryRun;

    /**
     * Creates a plan run config.
     *
     * @param renderMode render mode for template rendering
     * @param env environment variables
     * @param scenario scenario variables
     * @param legacy legacy compatibility variables
     * @param stopOnFirstError whether to stop after the first non-OK result
     * @param includeDisabledRows whether disabled rows should be included
     */
    public AccessPlanRunConfig(
            RenderMode renderMode,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            boolean stopOnFirstError,
            boolean includeDisabledRows) {
        this(renderMode, env, scenario, legacy, stopOnFirstError, includeDisabledRows, false);
    }

    /**
     * Creates a plan run config.
     *
     * @param renderMode render mode for template rendering
     * @param env environment variables
     * @param scenario scenario variables
     * @param legacy legacy compatibility variables
     * @param stopOnFirstError whether to stop after the first non-OK result
     * @param includeDisabledRows whether disabled rows should be included
     * @param dryRun whether requests should be rendered but not sent
     */
    public AccessPlanRunConfig(
            RenderMode renderMode,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            boolean stopOnFirstError,
            boolean includeDisabledRows,
            boolean dryRun) {
        this.renderMode = Objects.requireNonNullElse(renderMode, RenderMode.STRICT);
        this.env = Map.copyOf(Objects.requireNonNullElse(env, Map.of()));
        this.scenario = Map.copyOf(Objects.requireNonNullElse(scenario, Map.of()));
        this.legacy = Map.copyOf(Objects.requireNonNullElse(legacy, Map.of()));
        this.stopOnFirstError = stopOnFirstError;
        this.includeDisabledRows = includeDisabledRows;
        this.dryRun = dryRun;
    }

    /**
     * Returns default sequential plan config.
     *
     * @return default config
     */
    public static AccessPlanRunConfig defaults() {
        return new AccessPlanRunConfig(RenderMode.STRICT, Map.of(), Map.of(), Map.of(), false, false);
    }

    /**
     * Returns the render mode.
     *
     * @return render mode
     */
    public RenderMode renderMode() {
        return renderMode;
    }

    /**
     * Returns environment variables.
     *
     * @return immutable env variables
     */
    public Map<String, String> env() {
        return env;
    }

    /**
     * Returns scenario variables.
     *
     * @return immutable scenario variables
     */
    public Map<String, String> scenario() {
        return scenario;
    }

    /**
     * Returns legacy compatibility variables.
     *
     * @return immutable legacy variables
     */
    public Map<String, String> legacy() {
        return legacy;
    }

    /**
     * Returns whether the run should stop after the first non-OK result.
     *
     * @return stop-on-error flag
     */
    public boolean stopOnFirstError() {
        return stopOnFirstError;
    }

    /**
     * Returns whether disabled rows should be included.
     *
     * @return include-disabled flag
     */
    public boolean includeDisabledRows() {
        return includeDisabledRows;
    }

    /**
     * Returns whether requests should be rendered only and not sent.
     *
     * @return dry-run flag
     */
    public boolean dryRun() {
        return dryRun;
    }
}
