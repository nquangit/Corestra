package dev.nquangit.corestra.access.postcheck;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.nquangit.corestra.access.result.Verdict;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Captures redacted evidence and assertion outcomes for one post-check request.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class PostCheckEvidence {
    private final String postCheckId;
    private final String name;
    private final String redactedRequestRaw;
    private final String redactedResponseRaw;
    private final Verdict verdict;
    private final String reason;
    private final List<PostCheckAssertionResult> assertionResults;
    private final long capturedAtEpochMillis;

    /**
     * Creates post-check evidence.
     *
     * @param postCheckId source post-check id
     * @param name post-check name
     * @param redactedRequestRaw redacted request
     * @param redactedResponseRaw redacted response
     * @param verdict post-check verdict
     * @param reason post-check reason
     * @param assertionResults assertion results
     * @param capturedAtEpochMillis capture timestamp
     */
    @JsonCreator
    public PostCheckEvidence(
            @JsonProperty("postCheckId") String postCheckId,
            @JsonProperty("name") String name,
            @JsonProperty("redactedRequestRaw") String redactedRequestRaw,
            @JsonProperty("redactedResponseRaw") String redactedResponseRaw,
            @JsonProperty("verdict") Verdict verdict,
            @JsonProperty("reason") String reason,
            @JsonProperty("assertionResults") List<PostCheckAssertionResult> assertionResults,
            @JsonProperty("capturedAtEpochMillis") long capturedAtEpochMillis) {
        this.postCheckId = normalizeRequiredText(postCheckId, "postCheckId");
        this.name = normalizeRequiredText(name, "name");
        this.redactedRequestRaw = Objects.requireNonNullElse(redactedRequestRaw, "");
        this.redactedResponseRaw = Objects.requireNonNullElse(redactedResponseRaw, "");
        this.verdict = Objects.requireNonNullElse(verdict, Verdict.REVIEW);
        this.reason = Objects.requireNonNullElse(reason, "");
        this.assertionResults = copyResults(assertionResults);
        this.capturedAtEpochMillis = capturedAtEpochMillis;
    }

    /**
     * Returns the source post-check id.
     *
     * @return post-check id
     */
    public String postCheckId() {
        return postCheckId;
    }

    /**
     * Returns the post-check name.
     *
     * @return post-check name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the redacted request.
     *
     * @return redacted request, or an empty string
     */
    public String redactedRequestRaw() {
        return redactedRequestRaw;
    }

    /**
     * Returns the redacted response.
     *
     * @return redacted response, or an empty string
     */
    public String redactedResponseRaw() {
        return redactedResponseRaw;
    }

    /**
     * Returns the post-check verdict.
     *
     * @return post-check verdict
     */
    public Verdict verdict() {
        return verdict;
    }

    /**
     * Returns the post-check reason.
     *
     * @return reason, or an empty string
     */
    public String reason() {
        return reason;
    }

    /**
     * Returns assertion results.
     *
     * @return immutable assertion results
     */
    public List<PostCheckAssertionResult> assertionResults() {
        return assertionResults;
    }

    /**
     * Returns the capture timestamp.
     *
     * @return capture timestamp
     */
    public long capturedAtEpochMillis() {
        return capturedAtEpochMillis;
    }

    /**
     * Compares this evidence to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PostCheckEvidence that)) {
            return false;
        }
        return capturedAtEpochMillis == that.capturedAtEpochMillis
                && Objects.equals(postCheckId, that.postCheckId)
                && Objects.equals(name, that.name)
                && Objects.equals(redactedRequestRaw, that.redactedRequestRaw)
                && Objects.equals(redactedResponseRaw, that.redactedResponseRaw)
                && verdict == that.verdict
                && Objects.equals(reason, that.reason)
                && Objects.equals(assertionResults, that.assertionResults);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(postCheckId, name, redactedRequestRaw, redactedResponseRaw, verdict,
                reason, assertionResults, capturedAtEpochMillis);
    }

    private static List<PostCheckAssertionResult> copyResults(List<PostCheckAssertionResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        ArrayList<PostCheckAssertionResult> copied = new ArrayList<>();
        results.forEach(result -> copied.add(Objects.requireNonNull(result, "assertion result")));
        return List.copyOf(copied);
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
