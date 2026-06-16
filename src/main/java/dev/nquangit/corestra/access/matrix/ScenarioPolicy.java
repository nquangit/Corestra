package dev.nquangit.corestra.access.matrix;

/**
 * Defines how scenario state should be reused or recreated across access tests.
 */
public enum ScenarioPolicy {
    REUSE_EXISTING,
    FRESH_PER_TEST,
    FRESH_PER_ACTOR,
    FRESH_PER_BATCH
}
