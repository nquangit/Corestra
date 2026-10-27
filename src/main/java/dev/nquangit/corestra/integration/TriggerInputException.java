package dev.nquangit.corestra.integration;

/** Stops a triggered request whose required inputs cannot be prepared safely. */
public final class TriggerInputException extends IllegalArgumentException {
    /** Creates a diagnostic containing configuration names, never resolved secret values. */
    public TriggerInputException(String message) { super(message); }
}
