package dev.nquangit.corestra.access.race;

import java.util.List;
import java.util.Objects;

/**
 * Summary for an explicitly triggered race run.
 */
public final class RaceRunResult {
    private final String id;
    private final String testCaseId;
    private final RaceRunStatus status;
    private final int concurrency;
    private final int iterations;
    private final List<RaceWorkerResult> workerResults;
    private final List<String> warnings;
    private final long startedAtEpochMillis;
    private final long finishedAtEpochMillis;

    /**
     * Creates a race run result.
     *
     * @param id stable race result id
     * @param testCaseId selected matrix row id
     * @param status race run status
     * @param concurrency configured worker count
     * @param iterations configured iterations
     * @param workerResults worker results
     * @param warnings runner-level warnings
     * @param startedAtEpochMillis run start timestamp
     * @param finishedAtEpochMillis run finish timestamp
     */
    public RaceRunResult(
            String id,
            String testCaseId,
            RaceRunStatus status,
            int concurrency,
            int iterations,
            List<RaceWorkerResult> workerResults,
            List<String> warnings,
            long startedAtEpochMillis,
            long finishedAtEpochMillis) {
        this.id = normalizeRequiredText(id, "id");
        this.testCaseId = normalizeRequiredText(testCaseId, "testCaseId");
        this.status = Objects.requireNonNullElse(status, RaceRunStatus.PENDING);
        this.concurrency = concurrency;
        this.iterations = iterations;
        this.workerResults = List.copyOf(Objects.requireNonNullElse(workerResults, List.of()));
        this.warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
        this.startedAtEpochMillis = startedAtEpochMillis;
        this.finishedAtEpochMillis = finishedAtEpochMillis;
    }

    /** @return stable race result id */
    public String id() {
        return id;
    }

    /** @return selected matrix row id */
    public String testCaseId() {
        return testCaseId;
    }

    /** @return race run status */
    public RaceRunStatus status() {
        return status;
    }

    /** @return configured worker count */
    public int concurrency() {
        return concurrency;
    }

    /** @return configured iterations */
    public int iterations() {
        return iterations;
    }

    /** @return worker results */
    public List<RaceWorkerResult> workerResults() {
        return workerResults;
    }

    /** @return runner-level warnings */
    public List<String> warnings() {
        return warnings;
    }

    /** @return run start timestamp */
    public long startedAtEpochMillis() {
        return startedAtEpochMillis;
    }

    /** @return run finish timestamp */
    public long finishedAtEpochMillis() {
        return finishedAtEpochMillis;
    }

    /** @return total worker results with 2xx status codes */
    public long twoXxCount() {
        return workerResults.stream().filter(result -> result.statusCode() >= 200 && result.statusCode() < 300).count();
    }

    /** @return total worker results with 4xx status codes */
    public long fourXxCount() {
        return workerResults.stream().filter(result -> result.statusCode() >= 400 && result.statusCode() < 500).count();
    }

    /** @return total worker results with 5xx status codes */
    public long fiveXxCount() {
        return workerResults.stream().filter(result -> result.statusCode() >= 500 && result.statusCode() < 600).count();
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
