package dev.nquangit.corestra.session;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of resolving traffic to a session profile.
 */
public final class SessionProfileSelection {
    private final SessionProfile profile;
    private final List<String> warnings;

    private SessionProfileSelection(SessionProfile profile, List<String> warnings) {
        this.profile = profile;
        this.warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
    }

    /**
     * Returns an empty selection.
     *
     * @return empty selection
     */
    public static SessionProfileSelection none() {
        return new SessionProfileSelection(null, List.of());
    }

    /**
     * Returns a profile selection.
     *
     * @param profile selected profile
     * @param warnings diagnostic warnings
     * @return profile selection
     */
    public static SessionProfileSelection of(SessionProfile profile, List<String> warnings) {
        return new SessionProfileSelection(Objects.requireNonNull(profile, "profile"), warnings);
    }

    /**
     * Returns the selected profile, if any.
     *
     * @return selected profile
     */
    public Optional<SessionProfile> profile() {
        return Optional.ofNullable(profile);
    }

    /**
     * Returns profile-selection warnings.
     *
     * @return immutable warnings
     */
    public List<String> warnings() {
        return warnings;
    }
}
