package dev.nquangit.corestra.access.runner;

import dev.nquangit.corestra.access.result.AccessEvidence;
import dev.nquangit.corestra.access.result.AccessTestResult;

import java.util.Objects;
import java.util.Optional;

/**
 * Result returned by a manual single access test run.
 */
public final class AccessRunResult {
    private final AccessTestResult result;
    private final Optional<AccessEvidence> evidence;
    private final boolean requestSent;

    /**
     * Creates a run result.
     *
     * @param result access test result
     * @param evidence captured evidence, if a request was sent
     * @param requestSent whether an HTTP request was sent
     */
    public AccessRunResult(AccessTestResult result, Optional<AccessEvidence> evidence, boolean requestSent) {
        this.result = Objects.requireNonNull(result, "result");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.requestSent = requestSent;
    }

    /**
     * Returns the access test result.
     *
     * @return access test result
     */
    public AccessTestResult result() {
        return result;
    }

    /**
     * Returns captured evidence when a request was sent.
     *
     * @return captured evidence
     */
    public Optional<AccessEvidence> evidence() {
        return evidence;
    }

    /**
     * Returns whether an HTTP request was sent.
     *
     * @return true when a request was sent
     */
    public boolean requestSent() {
        return requestSent;
    }
}
