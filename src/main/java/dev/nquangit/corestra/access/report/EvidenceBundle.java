package dev.nquangit.corestra.access.report;

import java.util.Objects;

/**
 * Carries request and response evidence selected for a finding draft.
 */
public final class EvidenceBundle {
    private final String requestEvidence;
    private final String responseEvidence;
    private final boolean redacted;

    /**
     * Creates an evidence bundle.
     *
     * @param requestEvidence request evidence text
     * @param responseEvidence response evidence text
     * @param redacted whether the evidence has been redacted
     */
    public EvidenceBundle(String requestEvidence, String responseEvidence, boolean redacted) {
        this.requestEvidence = Objects.requireNonNullElse(requestEvidence, "");
        this.responseEvidence = Objects.requireNonNullElse(responseEvidence, "");
        this.redacted = redacted;
    }

    /**
     * Returns request evidence text.
     *
     * @return request evidence text, or an empty string
     */
    public String requestEvidence() {
        return requestEvidence;
    }

    /**
     * Returns response evidence text.
     *
     * @return response evidence text, or an empty string
     */
    public String responseEvidence() {
        return responseEvidence;
    }

    /**
     * Returns whether this evidence is redacted.
     *
     * @return true when redacted
     */
    public boolean redacted() {
        return redacted;
    }

    /**
     * Compares this bundle to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EvidenceBundle that)) {
            return false;
        }
        return redacted == that.redacted
                && Objects.equals(requestEvidence, that.requestEvidence)
                && Objects.equals(responseEvidence, that.responseEvidence);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(requestEvidence, responseEvidence, redacted);
    }
}
