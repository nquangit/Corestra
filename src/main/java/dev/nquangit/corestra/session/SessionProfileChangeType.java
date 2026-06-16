package dev.nquangit.corestra.session;

/**
 * Describes the kind of session profile change emitted by {@link SessionManager}.
 */
public enum SessionProfileChangeType {
    /**
     * Profile configuration was saved.
     */
    PROFILE_SAVED,

    /**
     * Profile configuration was deleted.
     */
    PROFILE_DELETED,

    /**
     * Profile state values were updated.
     */
    STATE_UPDATED,

    /**
     * Profile state values were cleared.
     */
    STATE_CLEARED
}
