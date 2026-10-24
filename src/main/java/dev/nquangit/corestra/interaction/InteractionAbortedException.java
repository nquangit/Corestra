package dev.nquangit.corestra.interaction;

/** Signals that an interactive operation was cancelled, timed out, or could not be completed. */
public final class InteractionAbortedException extends RuntimeException {
    /** Identifies interactive failures across flow/auth boundaries without recording entered values. */
    public static final String FAILURE_PREFIX = "[INTERACTION_ABORTED] ";

    /** Creates an interruption with a user-facing reason. */
    public InteractionAbortedException(String reason) {
        super(FAILURE_PREFIX + reason);
    }

    /** Whether a flow failure came from an aborted interactive operation. */
    public static boolean isAborted(String reason) {
        return reason != null && reason.contains(FAILURE_PREFIX);
    }
}
