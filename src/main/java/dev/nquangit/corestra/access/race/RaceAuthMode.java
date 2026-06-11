package dev.nquangit.corestra.access.race;

/**
 * Controls how target authentication state is prepared before a race gate opens.
 */
public enum RaceAuthMode {
    /**
     * Uses the auth profile state already present in the workspace snapshot.
     */
    SHARED_AUTH_PROFILE_STATE,

    /**
     * Ensures configured auth state before workers wait at the synchronization gate.
     */
    ENSURE_AUTH_BEFORE_GATE
}
