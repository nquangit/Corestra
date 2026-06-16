package dev.nquangit.corestra.access.race;

import dev.nquangit.corestra.access.matrix.RunStatus;
import dev.nquangit.corestra.access.result.Verdict;

import java.util.List;
import java.util.Objects;

/**
 * Outcome for one worker in one race iteration.
 */
public final class RaceWorkerResult {
    private final int workerIndex;
    private final int iteration;
    private final RunStatus runStatus;
    private final Verdict verdict;
    private final int statusCode;
    private final String reason;
    private final String resultId;
    private final long startedAtEpochMillis;
    private final long releasedAtEpochMillis;
    private final long finishedAtEpochMillis;
    private final List<String> warnings;

    /**
     * Creates a race worker result.
     *
     * @param workerIndex zero-based worker index
     * @param iteration one-based iteration number
     * @param runStatus run lifecycle status
     * @param verdict security verdict
     * @param statusCode HTTP response status code, or 0 when not sent
     * @param reason human-readable result reason
     * @param resultId saved access result id, or empty
     * @param startedAtEpochMillis worker start timestamp
     * @param releasedAtEpochMillis gate release timestamp observed by this worker
     * @param finishedAtEpochMillis worker finish timestamp
     * @param warnings non-fatal warnings
     */
    public RaceWorkerResult(
            int workerIndex,
            int iteration,
            RunStatus runStatus,
            Verdict verdict,
            int statusCode,
            String reason,
            String resultId,
            long startedAtEpochMillis,
            long releasedAtEpochMillis,
            long finishedAtEpochMillis,
            List<String> warnings) {
        this.workerIndex = workerIndex;
        this.iteration = iteration;
        this.runStatus = Objects.requireNonNullElse(runStatus, RunStatus.PENDING);
        this.verdict = Objects.requireNonNullElse(verdict, Verdict.NOT_RUN);
        this.statusCode = Math.max(statusCode, 0);
        this.reason = Objects.requireNonNullElse(reason, "");
        this.resultId = Objects.requireNonNullElse(resultId, "").strip();
        this.startedAtEpochMillis = startedAtEpochMillis;
        this.releasedAtEpochMillis = releasedAtEpochMillis;
        this.finishedAtEpochMillis = finishedAtEpochMillis;
        this.warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
    }

    /** @return zero-based worker index */
    public int workerIndex() {
        return workerIndex;
    }

    /** @return one-based iteration number */
    public int iteration() {
        return iteration;
    }

    /** @return run lifecycle status */
    public RunStatus runStatus() {
        return runStatus;
    }

    /** @return security verdict */
    public Verdict verdict() {
        return verdict;
    }

    /** @return HTTP status code, or 0 when not sent */
    public int statusCode() {
        return statusCode;
    }

    /** @return human-readable reason */
    public String reason() {
        return reason;
    }

    /** @return saved access result id, or empty */
    public String resultId() {
        return resultId;
    }

    /** @return worker start timestamp */
    public long startedAtEpochMillis() {
        return startedAtEpochMillis;
    }

    /** @return worker-observed gate release timestamp */
    public long releasedAtEpochMillis() {
        return releasedAtEpochMillis;
    }

    /** @return worker finish timestamp */
    public long finishedAtEpochMillis() {
        return finishedAtEpochMillis;
    }

    /** @return non-fatal warnings */
    public List<String> warnings() {
        return warnings;
    }
}
