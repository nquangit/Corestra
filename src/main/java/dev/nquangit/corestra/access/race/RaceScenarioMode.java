package dev.nquangit.corestra.access.race;

/**
 * Controls how scenario state is prepared for a race run.
 */
public enum RaceScenarioMode {
    /**
     * Reuses one scenario across all workers and iterations.
     */
    REUSE_SINGLE_SCENARIO,

    /**
     * Prepares one scenario per worker and reuses it across iterations.
     */
    FRESH_SCENARIO_PER_WORKER,

    /**
     * Prepares a fresh scenario for every worker/iteration pair.
     */
    FRESH_SCENARIO_PER_ITERATION
}
