package dev.nquangit.corestra.session;

/** Stops a bound execution instead of changing actors or sending captured authentication. */
public final class SessionBindingException extends RuntimeException {
    /** Fatal failure marker preserved across flow execution boundaries. */
    public static final String PREFIX = "[SESSION_BINDING_ABORTED] ";
    /** Creates a safe diagnostic without authentication values. */
    public SessionBindingException(String reason) { super(PREFIX + reason); }
    /** Whether a recorded execution failure requires stopping a bound flow. */
    public static boolean isAborted(String message) { return message != null && message.contains(PREFIX); }
}
