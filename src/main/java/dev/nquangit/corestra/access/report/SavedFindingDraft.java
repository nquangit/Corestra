package dev.nquangit.corestra.access.report;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Persisted edited finding draft markdown for the latest result of one matrix row.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class SavedFindingDraft {
    private final String testCaseId;
    private final String resultId;
    private final String markdown;
    private final long updatedAtEpochMillis;

    /**
     * Creates a saved finding draft.
     *
     * @param testCaseId matrix row id
     * @param resultId result id the draft was created from
     * @param markdown edited Markdown content
     * @param updatedAtEpochMillis update timestamp
     */
    @JsonCreator
    public SavedFindingDraft(
            @JsonProperty("testCaseId") String testCaseId,
            @JsonProperty("resultId") String resultId,
            @JsonProperty("markdown") String markdown,
            @JsonProperty("updatedAtEpochMillis") long updatedAtEpochMillis) {
        this.testCaseId = normalizeRequiredText(testCaseId, "testCaseId");
        this.resultId = normalizeRequiredText(resultId, "resultId");
        this.markdown = Objects.requireNonNullElse(markdown, "");
        this.updatedAtEpochMillis = updatedAtEpochMillis;
    }

    /**
     * Returns the matrix row id.
     *
     * @return matrix row id
     */
    public String testCaseId() {
        return testCaseId;
    }

    /**
     * Returns the result id the draft was created from.
     *
     * @return result id
     */
    public String resultId() {
        return resultId;
    }

    /**
     * Returns edited Markdown content.
     *
     * @return Markdown content
     */
    public String markdown() {
        return markdown;
    }

    /**
     * Returns the update timestamp.
     *
     * @return epoch milliseconds
     */
    public long updatedAtEpochMillis() {
        return updatedAtEpochMillis;
    }

    /**
     * Compares this draft to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SavedFindingDraft that)) {
            return false;
        }
        return updatedAtEpochMillis == that.updatedAtEpochMillis
                && Objects.equals(testCaseId, that.testCaseId)
                && Objects.equals(resultId, that.resultId)
                && Objects.equals(markdown, that.markdown);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(testCaseId, resultId, markdown, updatedAtEpochMillis);
    }

    /**
     * Returns a diagnostic string.
     *
     * @return diagnostic string
     */
    @Override
    public String toString() {
        return "SavedFindingDraft{" +
                "testCaseId='" + testCaseId + '\'' +
                ", resultId='" + resultId + '\'' +
                ", updatedAtEpochMillis=" + updatedAtEpochMillis +
                '}';
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
