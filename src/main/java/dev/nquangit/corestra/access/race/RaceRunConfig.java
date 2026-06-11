package dev.nquangit.corestra.access.race;

import java.util.Objects;

/**
 * Immutable configuration for one explicit race run.
 */
public final class RaceRunConfig {
    /** Conservative hard cap for concurrent workers. */
    public static final int MAX_CONCURRENCY = 10;
    /** Conservative hard cap for repeated race batches. */
    public static final int MAX_ITERATIONS = 10;
    /** Default worker readiness timeout. */
    public static final long DEFAULT_PREPARATION_TIMEOUT_MILLIS = 10_000L;

    private final String testCaseId;
    private final int concurrency;
    private final int iterations;
    private final boolean synchronizeStart;
    private final long preGateDelayMillis;
    private final long betweenIterationDelayMillis;
    private final long preparationTimeoutMillis;
    private final RaceScenarioMode scenarioMode;
    private final RaceAuthMode authMode;
    private final boolean dryRun;
    private final boolean runPostChecksAfterBatch;
    private final boolean runTeardownAfterBatch;
    private final boolean stopOnFirstError;

    /**
     * Creates a race run configuration.
     *
     * @param testCaseId selected matrix row id
     * @param concurrency concurrent worker count
     * @param iterations number of race batches to run
     * @param synchronizeStart whether workers wait at a shared start gate
     * @param preGateDelayMillis optional delay after all workers are ready and before release
     * @param betweenIterationDelayMillis optional delay between race batches
     * @param preparationTimeoutMillis maximum time to wait for all workers to prepare
     * @param scenarioMode scenario preparation mode
     * @param authMode auth preparation mode
     * @param dryRun whether to render without sending HTTP
     * @param runPostChecksAfterBatch whether enabled post-checks run once after the batch
     * @param runTeardownAfterBatch whether teardown runs once after post-checks
     * @param stopOnFirstError whether later iterations stop after a worker error
     */
    public RaceRunConfig(
            String testCaseId,
            int concurrency,
            int iterations,
            boolean synchronizeStart,
            long preGateDelayMillis,
            long betweenIterationDelayMillis,
            long preparationTimeoutMillis,
            RaceScenarioMode scenarioMode,
            RaceAuthMode authMode,
            boolean dryRun,
            boolean runPostChecksAfterBatch,
            boolean runTeardownAfterBatch,
            boolean stopOnFirstError) {
        this.testCaseId = normalizeRequiredText(testCaseId, "testCaseId");
        this.concurrency = validateBounded(concurrency, "concurrency", MAX_CONCURRENCY);
        this.iterations = validateBounded(iterations, "iterations", MAX_ITERATIONS);
        this.synchronizeStart = synchronizeStart;
        this.preGateDelayMillis = validateNonNegative(preGateDelayMillis, "preGateDelayMillis");
        this.betweenIterationDelayMillis = validateNonNegative(betweenIterationDelayMillis, "betweenIterationDelayMillis");
        this.preparationTimeoutMillis = validatePositive(preparationTimeoutMillis, "preparationTimeoutMillis");
        this.scenarioMode = Objects.requireNonNullElse(scenarioMode, RaceScenarioMode.REUSE_SINGLE_SCENARIO);
        this.authMode = Objects.requireNonNullElse(authMode, RaceAuthMode.ENSURE_AUTH_BEFORE_GATE);
        this.dryRun = dryRun;
        this.runPostChecksAfterBatch = runPostChecksAfterBatch;
        this.runTeardownAfterBatch = runTeardownAfterBatch;
        this.stopOnFirstError = stopOnFirstError;
    }

    /**
     * Returns the recommended default race configuration for one test case.
     *
     * @param testCaseId selected matrix row id
     * @return default race configuration
     */
    public static RaceRunConfig defaults(String testCaseId) {
        return new RaceRunConfig(
                testCaseId,
                2,
                1,
                true,
                0L,
                0L,
                DEFAULT_PREPARATION_TIMEOUT_MILLIS,
                RaceScenarioMode.REUSE_SINGLE_SCENARIO,
                RaceAuthMode.ENSURE_AUTH_BEFORE_GATE,
                false,
                true,
                true,
                false);
    }

    /** @return selected matrix row id */
    public String testCaseId() {
        return testCaseId;
    }

    /** @return concurrent worker count */
    public int concurrency() {
        return concurrency;
    }

    /** @return number of race batches */
    public int iterations() {
        return iterations;
    }

    /** @return true when workers wait at a shared start gate */
    public boolean synchronizeStart() {
        return synchronizeStart;
    }

    /** @return optional delay before releasing a ready gate */
    public long preGateDelayMillis() {
        return preGateDelayMillis;
    }

    /** @return optional delay between iterations */
    public long betweenIterationDelayMillis() {
        return betweenIterationDelayMillis;
    }

    /** @return worker preparation timeout */
    public long preparationTimeoutMillis() {
        return preparationTimeoutMillis;
    }

    /** @return scenario preparation mode */
    public RaceScenarioMode scenarioMode() {
        return scenarioMode;
    }

    /** @return auth preparation mode */
    public RaceAuthMode authMode() {
        return authMode;
    }

    /** @return true when no HTTP should be sent */
    public boolean dryRun() {
        return dryRun;
    }

    /** @return true when post-checks run after the race batch */
    public boolean runPostChecksAfterBatch() {
        return runPostChecksAfterBatch;
    }

    /** @return true when teardown runs after post-checks */
    public boolean runTeardownAfterBatch() {
        return runTeardownAfterBatch;
    }

    /** @return true when later iterations stop after the first worker error */
    public boolean stopOnFirstError() {
        return stopOnFirstError;
    }

    /** @return total target requests that will be rendered or sent */
    public int totalTargetRequests() {
        return concurrency * iterations;
    }

    /**
     * Returns a copy with dry-run mode changed.
     *
     * @param value dry-run flag
     * @return updated config
     */
    public RaceRunConfig withDryRun(boolean value) {
        return new RaceRunConfig(testCaseId, concurrency, iterations, synchronizeStart,
                preGateDelayMillis, betweenIterationDelayMillis, preparationTimeoutMillis,
                scenarioMode, authMode, value, runPostChecksAfterBatch, runTeardownAfterBatch, stopOnFirstError);
    }

    private static int validateBounded(int value, String fieldName, int max) {
        if (value < 1) {
            throw new IllegalArgumentException(fieldName + " must be at least 1");
        }
        if (value > max) {
            throw new IllegalArgumentException(fieldName + " must not exceed " + max);
        }
        return value;
    }

    private static long validateNonNegative(long value, String fieldName) {
        if (value < 0L) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
        return value;
    }

    private static long validatePositive(long value, String fieldName) {
        if (value < 1L) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
