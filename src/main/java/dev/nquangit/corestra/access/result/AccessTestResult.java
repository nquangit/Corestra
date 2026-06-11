package dev.nquangit.corestra.access.result;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.nquangit.corestra.access.matrix.RunStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Summarizes the outcome of one access test execution.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class AccessTestResult {
    private final String id;
    private final String testCaseId;
    private final RunStatus runStatus;
    private final Verdict verdict;
    private final String reason;
    private final long startedAtEpochMillis;
    private final long finishedAtEpochMillis;
    private final String renderedRequestSummary;
    private final String responseSummary;
    private final List<String> warnings;

    /**
     * Creates an access test result.
     *
     * @param id stable result identifier
     * @param testCaseId source test case identifier
     * @param runStatus run lifecycle status
     * @param verdict security verdict
     * @param reason human-readable reason
     * @param startedAtEpochMillis start timestamp in epoch milliseconds
     * @param finishedAtEpochMillis finish timestamp in epoch milliseconds
     * @param renderedRequestSummary sanitized rendered request summary
     * @param responseSummary sanitized response summary
     * @param warnings non-fatal warnings
     */
    @JsonCreator
    public AccessTestResult(
            @JsonProperty("id") String id,
            @JsonProperty("testCaseId") String testCaseId,
            @JsonProperty("runStatus") RunStatus runStatus,
            @JsonProperty("verdict") Verdict verdict,
            @JsonProperty("reason") String reason,
            @JsonProperty("startedAtEpochMillis") long startedAtEpochMillis,
            @JsonProperty("finishedAtEpochMillis") long finishedAtEpochMillis,
            @JsonProperty("renderedRequestSummary") String renderedRequestSummary,
            @JsonProperty("responseSummary") String responseSummary,
            @JsonProperty("warnings") List<String> warnings) {
        this.id = normalizeRequiredText(id, "id");
        this.testCaseId = normalizeRequiredText(testCaseId, "testCaseId");
        this.runStatus = Objects.requireNonNullElse(runStatus, RunStatus.PENDING);
        this.verdict = Objects.requireNonNullElse(verdict, Verdict.NOT_RUN);
        this.reason = normalizeOptionalText(reason);
        this.startedAtEpochMillis = startedAtEpochMillis;
        this.finishedAtEpochMillis = finishedAtEpochMillis;
        this.renderedRequestSummary = normalizeOptionalText(renderedRequestSummary);
        this.responseSummary = normalizeOptionalText(responseSummary);
        this.warnings = copyWarnings(warnings);
    }

    /**
     * Returns the stable result identifier.
     *
     * @return stable result identifier
     */
    public String id() {
        return id;
    }

    /**
     * Returns the source test case identifier.
     *
     * @return source test case identifier
     */
    public String testCaseId() {
        return testCaseId;
    }

    /**
     * Returns the run lifecycle status.
     *
     * @return run status
     */
    public RunStatus runStatus() {
        return runStatus;
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
     * @return reason, or an empty string
     */
    public String reason() {
        return reason;
    }

    /**
     * Returns the start timestamp in epoch milliseconds.
     *
     * @return start timestamp
     */
    public long startedAtEpochMillis() {
        return startedAtEpochMillis;
    }

    /**
     * Returns the finish timestamp in epoch milliseconds.
     *
     * @return finish timestamp
     */
    public long finishedAtEpochMillis() {
        return finishedAtEpochMillis;
    }

    /**
     * Returns a sanitized rendered request summary.
     *
     * @return rendered request summary, or an empty string
     */
    public String renderedRequestSummary() {
        return renderedRequestSummary;
    }

    /**
     * Returns a sanitized response summary.
     *
     * @return response summary, or an empty string
     */
    public String responseSummary() {
        return responseSummary;
    }

    /**
     * Returns non-fatal warnings.
     *
     * @return immutable warnings
     */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * Compares this result to another object.
     *
     * @param other the candidate object
     * @return true when the objects are equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccessTestResult that)) {
            return false;
        }
        return startedAtEpochMillis == that.startedAtEpochMillis
                && finishedAtEpochMillis == that.finishedAtEpochMillis
                && Objects.equals(id, that.id)
                && Objects.equals(testCaseId, that.testCaseId)
                && runStatus == that.runStatus
                && verdict == that.verdict
                && Objects.equals(reason, that.reason)
                && Objects.equals(renderedRequestSummary, that.renderedRequestSummary)
                && Objects.equals(responseSummary, that.responseSummary)
                && Objects.equals(warnings, that.warnings);
    }

    /**
     * Returns the hash code for this result.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, testCaseId, runStatus, verdict, reason,
                startedAtEpochMillis, finishedAtEpochMillis, renderedRequestSummary, responseSummary, warnings);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "AccessTestResult{" +
                "id='" + id + '\'' +
                ", testCaseId='" + testCaseId + '\'' +
                ", runStatus=" + runStatus +
                ", verdict=" + verdict +
                ", reason='" + reason + '\'' +
                ", startedAtEpochMillis=" + startedAtEpochMillis +
                ", finishedAtEpochMillis=" + finishedAtEpochMillis +
                ", renderedRequestSummary='" + renderedRequestSummary + '\'' +
                ", responseSummary='" + responseSummary + '\'' +
                ", warnings=" + warnings +
                '}';
    }

    private static List<String> copyWarnings(List<String> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return List.of();
        }
        List<String> copied = new ArrayList<>();
        for (String warning : warnings) {
            if (warning != null && !warning.isBlank()) {
                copied.add(warning.strip());
            }
        }
        return List.copyOf(copied);
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }

    private static String normalizeOptionalText(String value) {
        return value == null ? "" : value.strip();
    }
}
