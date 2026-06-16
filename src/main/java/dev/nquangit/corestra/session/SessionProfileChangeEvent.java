package dev.nquangit.corestra.session;

import java.util.Objects;

/**
 * Event emitted when a session profile or its state changes.
 *
 * @param profileId profile id that changed
 * @param type change type
 */
public record SessionProfileChangeEvent(String profileId, SessionProfileChangeType type) {
    /**
     * Creates a session profile change event.
     */
    public SessionProfileChangeEvent {
        if (profileId == null || profileId.isBlank()) {
            throw new IllegalArgumentException("profileId must not be blank");
        }
        profileId = profileId.strip();
        type = Objects.requireNonNull(type, "type");
    }
}
