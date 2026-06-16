package dev.nquangit.corestra.access.runner;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Summary of a sequential access plan run.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class AccessPlanSummary {
    private final int total;
    private final int attempted;
    private final int completed;
    private final int skipped;
    private final int invalid;
    private final int failed;
    private final int suspicious;
    private final int ok;
    private final int review;
    private final int error;
    private final boolean stopped;
    private final long startedAtEpochMillis;
    private final long finishedAtEpochMillis;

    /**
     * Creates a plan summary.
     *
     * @param total total selected rows
     * @param attempted rows attempted by the runner
     * @param completed rows completed with HTTP/evaluation
     * @param skipped skipped rows
     * @param invalid invalid rows
     * @param failed failed rows
     * @param suspicious suspicious verdict count
     * @param ok OK verdict count
     * @param review review verdict count
     * @param error error verdict count
     * @param stopped whether the plan was stopped early
     * @param startedAtEpochMillis start timestamp
     * @param finishedAtEpochMillis finish timestamp
     */
    @JsonCreator
    public AccessPlanSummary(
            @JsonProperty("total") int total,
            @JsonProperty("attempted") int attempted,
            @JsonProperty("completed") int completed,
            @JsonProperty("skipped") int skipped,
            @JsonProperty("invalid") int invalid,
            @JsonProperty("failed") int failed,
            @JsonProperty("suspicious") int suspicious,
            @JsonProperty("ok") int ok,
            @JsonProperty("review") int review,
            @JsonProperty("error") int error,
            @JsonProperty("stopped") boolean stopped,
            @JsonProperty("startedAtEpochMillis") long startedAtEpochMillis,
            @JsonProperty("finishedAtEpochMillis") long finishedAtEpochMillis) {
        this.total = total;
        this.attempted = attempted;
        this.completed = completed;
        this.skipped = skipped;
        this.invalid = invalid;
        this.failed = failed;
        this.suspicious = suspicious;
        this.ok = ok;
        this.review = review;
        this.error = error;
        this.stopped = stopped;
        this.startedAtEpochMillis = startedAtEpochMillis;
        this.finishedAtEpochMillis = finishedAtEpochMillis;
    }

    /** @return total selected rows */
    public int total() { return total; }

    /** @return attempted rows */
    public int attempted() { return attempted; }

    /** @return completed rows */
    public int completed() { return completed; }

    /** @return skipped rows */
    public int skipped() { return skipped; }

    /** @return invalid rows */
    public int invalid() { return invalid; }

    /** @return failed rows */
    public int failed() { return failed; }

    /** @return suspicious verdict count */
    public int suspicious() { return suspicious; }

    /** @return OK verdict count */
    public int ok() { return ok; }

    /** @return review verdict count */
    public int review() { return review; }

    /** @return error verdict count */
    public int error() { return error; }

    /** @return whether stopped early */
    public boolean stopped() { return stopped; }

    /** @return start timestamp */
    public long startedAtEpochMillis() { return startedAtEpochMillis; }

    /** @return finish timestamp */
    public long finishedAtEpochMillis() { return finishedAtEpochMillis; }

    /**
     * Compares this summary to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccessPlanSummary that)) {
            return false;
        }
        return total == that.total
                && attempted == that.attempted
                && completed == that.completed
                && skipped == that.skipped
                && invalid == that.invalid
                && failed == that.failed
                && suspicious == that.suspicious
                && ok == that.ok
                && review == that.review
                && error == that.error
                && stopped == that.stopped
                && startedAtEpochMillis == that.startedAtEpochMillis
                && finishedAtEpochMillis == that.finishedAtEpochMillis;
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(total, attempted, completed, skipped, invalid, failed, suspicious, ok, review, error,
                stopped, startedAtEpochMillis, finishedAtEpochMillis);
    }

    /**
     * Mutable summary builder used internally by the plan runner.
     */
    static final class Builder {
        private final int total;
        private final long startedAtEpochMillis;
        private int attempted;
        private int completed;
        private int skipped;
        private int invalid;
        private int failed;
        private int suspicious;
        private int ok;
        private int review;
        private int error;

        Builder(int total, long startedAtEpochMillis) {
            this.total = total;
            this.startedAtEpochMillis = startedAtEpochMillis;
        }

        void record(AccessRunResult result) {
            attempted++;
            switch (result.result().runStatus()) {
                case COMPLETED -> completed++;
                case SKIPPED -> skipped++;
                case INVALID -> invalid++;
                case FAILED -> failed++;
                case PENDING, RUNNING -> {
                }
            }
            switch (result.result().verdict()) {
                case OK -> ok++;
                case SUSPICIOUS -> suspicious++;
                case REVIEW -> review++;
                case ERROR -> error++;
                case NOT_RUN -> {
                }
            }
        }

        AccessPlanSummary build(boolean stopped, long finishedAtEpochMillis) {
            return new AccessPlanSummary(total, attempted, completed, skipped, invalid, failed,
                    suspicious, ok, review, error, stopped, startedAtEpochMillis, finishedAtEpochMillis);
        }
    }
}
