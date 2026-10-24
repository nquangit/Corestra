package dev.nquangit.corestra.session;

/** A selectable existing session; contains display metadata only, never tokens or credentials. */
public record SessionChoice(String profileId, String label, long expiresAtEpochMillis) {
    /** Displays an unambiguous operator-facing session name. */
    @Override public String toString() { return label; }
}
