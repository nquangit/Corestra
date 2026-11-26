package dev.nquangit.corestra.session;

import java.util.Objects;

/**
 * Outcome of checking a live Session Handler response against the user's configured
 * "session lost" conditions.
 *
 * @param outcome what happened
 * @param resend whether the failed live request should be sent once more with the current session
 * @param message human-readable explanation for diagnostics
 */
public record LiveAuthRecovery(Outcome outcome, boolean resend, String message) {

    /** What the Session Handler did with a live response. */
    public enum Outcome {
        /** No failure condition is configured, or none matched. The response is left untouched. */
        NOT_AUTH_FAILURE,
        /** A failure rule matched but the configured policy says not to recover. */
        POLICY_NO_RETRY,
        /** A failure rule matched and this request triggered a successful re-authentication. */
        REAUTHENTICATED,
        /** A failure rule matched but another request already re-authenticated since this one was sent. */
        ALREADY_REAUTHENTICATED,
        /** A failure rule matched but re-authentication failed. */
        FAILED,
        /** A failure rule matched but a recent re-authentication failed, so this one is skipped. */
        BACKING_OFF
    }

    /** Validates components. */
    public LiveAuthRecovery {
        Objects.requireNonNull(outcome, "outcome");
        message = Objects.requireNonNullElse(message, "");
    }

    static LiveAuthRecovery notAuthFailure() {
        return new LiveAuthRecovery(Outcome.NOT_AUTH_FAILURE, false, "");
    }

    /**
     * Returns whether a failure condition matched the response.
     *
     * @return true for every outcome except {@link Outcome#NOT_AUTH_FAILURE}
     */
    public boolean detected() {
        return outcome != Outcome.NOT_AUTH_FAILURE;
    }
}
