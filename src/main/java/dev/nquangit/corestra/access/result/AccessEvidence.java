package dev.nquangit.corestra.access.result;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.nquangit.corestra.access.postcheck.PostCheckEvidence;
import dev.nquangit.corestra.access.teardown.TeardownEvidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * In-memory request/response evidence captured for one manual access test run.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class AccessEvidence {
    private final String testCaseId;
    private final String resultId;
    private final String renderedRequestRaw;
    private final String redactedRequestRaw;
    private final String responseRaw;
    private final String redactedResponseRaw;
    private final List<FlowStepEvidence> flowStepEvidence;
    private final List<PostCheckEvidence> postCheckEvidence;
    private final List<TeardownEvidence> teardownEvidence;
    private final long capturedAtEpochMillis;

    /**
     * Creates access evidence.
     *
     * @param testCaseId source test case id
     * @param resultId result id
     * @param renderedRequestRaw raw rendered request
     * @param redactedRequestRaw redacted rendered request
     * @param responseRaw raw response
     * @param redactedResponseRaw redacted response
     * @param capturedAtEpochMillis capture timestamp
     */
    public AccessEvidence(
            String testCaseId,
            String resultId,
            String renderedRequestRaw,
            String redactedRequestRaw,
            String responseRaw,
            String redactedResponseRaw,
            long capturedAtEpochMillis) {
        this(testCaseId, resultId, renderedRequestRaw, redactedRequestRaw, responseRaw, redactedResponseRaw,
                List.of(), List.of(), List.of(), capturedAtEpochMillis);
    }

    /**
     * Creates access evidence with post-check evidence.
     *
     * @param testCaseId source test case id
     * @param resultId result id
     * @param renderedRequestRaw raw rendered request
     * @param redactedRequestRaw redacted rendered request
     * @param responseRaw raw response
     * @param redactedResponseRaw redacted response
     * @param postCheckEvidence post-check evidence entries
     * @param capturedAtEpochMillis capture timestamp
     */
    public AccessEvidence(
            String testCaseId,
            String resultId,
            String renderedRequestRaw,
            String redactedRequestRaw,
            String responseRaw,
            String redactedResponseRaw,
            List<PostCheckEvidence> postCheckEvidence,
            long capturedAtEpochMillis) {
        this(testCaseId, resultId, renderedRequestRaw, redactedRequestRaw, responseRaw, redactedResponseRaw,
                List.of(), postCheckEvidence, List.of(), capturedAtEpochMillis);
    }

    /**
     * Creates access evidence with post-check and teardown evidence.
     *
     * @param testCaseId source test case id
     * @param resultId result id
     * @param renderedRequestRaw raw rendered request
     * @param redactedRequestRaw redacted rendered request
     * @param responseRaw raw response
     * @param redactedResponseRaw redacted response
     * @param postCheckEvidence post-check evidence entries
     * @param teardownEvidence teardown evidence entries
     * @param capturedAtEpochMillis capture timestamp
     */
    public AccessEvidence(
            String testCaseId,
            String resultId,
            String renderedRequestRaw,
            String redactedRequestRaw,
            String responseRaw,
            String redactedResponseRaw,
            List<PostCheckEvidence> postCheckEvidence,
            List<TeardownEvidence> teardownEvidence,
            long capturedAtEpochMillis) {
        this(testCaseId, resultId, renderedRequestRaw, redactedRequestRaw, responseRaw, redactedResponseRaw,
                List.of(), postCheckEvidence, teardownEvidence, capturedAtEpochMillis);
    }

    /**
     * Creates access evidence with flow-step, post-check, and teardown evidence.
     *
     * @param testCaseId source test case id
     * @param resultId result id
     * @param renderedRequestRaw raw rendered request
     * @param redactedRequestRaw redacted rendered request
     * @param responseRaw raw response
     * @param redactedResponseRaw redacted response
     * @param flowStepEvidence flow step evidence entries
     * @param postCheckEvidence post-check evidence entries
     * @param teardownEvidence teardown evidence entries
     * @param capturedAtEpochMillis capture timestamp
     */
    @JsonCreator
    public AccessEvidence(
            @JsonProperty("testCaseId") String testCaseId,
            @JsonProperty("resultId") String resultId,
            @JsonProperty("renderedRequestRaw") String renderedRequestRaw,
            @JsonProperty("redactedRequestRaw") String redactedRequestRaw,
            @JsonProperty("responseRaw") String responseRaw,
            @JsonProperty("redactedResponseRaw") String redactedResponseRaw,
            @JsonProperty("flowStepEvidence") List<FlowStepEvidence> flowStepEvidence,
            @JsonProperty("postCheckEvidence") List<PostCheckEvidence> postCheckEvidence,
            @JsonProperty("teardownEvidence") List<TeardownEvidence> teardownEvidence,
            @JsonProperty("capturedAtEpochMillis") long capturedAtEpochMillis) {
        this.testCaseId = normalizeRequiredText(testCaseId, "testCaseId");
        this.resultId = normalizeRequiredText(resultId, "resultId");
        this.renderedRequestRaw = Objects.requireNonNullElse(renderedRequestRaw, "");
        this.redactedRequestRaw = Objects.requireNonNullElse(redactedRequestRaw, "");
        this.responseRaw = Objects.requireNonNullElse(responseRaw, "");
        this.redactedResponseRaw = Objects.requireNonNullElse(redactedResponseRaw, "");
        this.flowStepEvidence = copyFlowStepEvidence(flowStepEvidence);
        this.postCheckEvidence = copyPostCheckEvidence(postCheckEvidence);
        this.teardownEvidence = copyTeardownEvidence(teardownEvidence);
        this.capturedAtEpochMillis = capturedAtEpochMillis;
    }

    /**
     * Returns the source test case id.
     *
     * @return source test case id
     */
    public String testCaseId() {
        return testCaseId;
    }

    /**
     * Returns the result id.
     *
     * @return result id
     */
    public String resultId() {
        return resultId;
    }

    /**
     * Returns the raw rendered request.
     *
     * @return raw rendered request
     */
    public String renderedRequestRaw() {
        return renderedRequestRaw;
    }

    /**
     * Returns the redacted rendered request.
     *
     * @return redacted rendered request
     */
    public String redactedRequestRaw() {
        return redactedRequestRaw;
    }

    /**
     * Returns the raw response.
     *
     * @return raw response
     */
    public String responseRaw() {
        return responseRaw;
    }

    /**
     * Returns the redacted response.
     *
     * @return redacted response
     */
    public String redactedResponseRaw() {
        return redactedResponseRaw;
    }

    /**
     * Returns flow step evidence entries.
     *
     * @return immutable flow step evidence entries
     */
    public List<FlowStepEvidence> flowStepEvidence() {
        return flowStepEvidence;
    }

    /**
     * Returns post-check evidence entries.
     *
     * @return immutable post-check evidence entries
     */
    public List<PostCheckEvidence> postCheckEvidence() {
        return postCheckEvidence;
    }

    /**
     * Returns teardown evidence entries.
     *
     * @return immutable teardown evidence entries
     */
    public List<TeardownEvidence> teardownEvidence() {
        return teardownEvidence;
    }

    /**
     * Returns the capture timestamp in epoch milliseconds.
     *
     * @return capture timestamp
     */
    public long capturedAtEpochMillis() {
        return capturedAtEpochMillis;
    }

    /**
     * Compares this evidence entry to another object.
     *
     * @param other the candidate object
     * @return true when the objects are equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccessEvidence that)) {
            return false;
        }
        return capturedAtEpochMillis == that.capturedAtEpochMillis
                && Objects.equals(testCaseId, that.testCaseId)
                && Objects.equals(resultId, that.resultId)
                && Objects.equals(renderedRequestRaw, that.renderedRequestRaw)
                && Objects.equals(redactedRequestRaw, that.redactedRequestRaw)
                && Objects.equals(responseRaw, that.responseRaw)
                && Objects.equals(redactedResponseRaw, that.redactedResponseRaw)
                && Objects.equals(flowStepEvidence, that.flowStepEvidence)
                && Objects.equals(postCheckEvidence, that.postCheckEvidence)
                && Objects.equals(teardownEvidence, that.teardownEvidence);
    }

    /**
     * Returns the hash code for this evidence entry.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(testCaseId, resultId, renderedRequestRaw, redactedRequestRaw,
                responseRaw, redactedResponseRaw, flowStepEvidence, postCheckEvidence, teardownEvidence,
                capturedAtEpochMillis);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "AccessEvidence{" +
                "testCaseId='" + testCaseId + '\'' +
                ", resultId='" + resultId + '\'' +
                ", capturedAtEpochMillis=" + capturedAtEpochMillis +
                ", renderedRequestRawLength=" + renderedRequestRaw.length() +
                ", responseRawLength=" + responseRaw.length() +
                ", flowStepEvidence=" + flowStepEvidence.size() +
                ", postCheckEvidence=" + postCheckEvidence.size() +
                ", teardownEvidence=" + teardownEvidence.size() +
                '}';
    }

    private static List<FlowStepEvidence> copyFlowStepEvidence(List<FlowStepEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        ArrayList<FlowStepEvidence> copied = new ArrayList<>();
        evidence.forEach(entry -> copied.add(Objects.requireNonNull(entry, "flow step evidence")));
        return List.copyOf(copied);
    }

    private static List<PostCheckEvidence> copyPostCheckEvidence(List<PostCheckEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        ArrayList<PostCheckEvidence> copied = new ArrayList<>();
        evidence.forEach(entry -> copied.add(Objects.requireNonNull(entry, "post-check evidence")));
        return List.copyOf(copied);
    }

    private static List<TeardownEvidence> copyTeardownEvidence(List<TeardownEvidence> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        ArrayList<TeardownEvidence> copied = new ArrayList<>();
        evidence.forEach(entry -> copied.add(Objects.requireNonNull(entry, "teardown evidence")));
        return List.copyOf(copied);
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
