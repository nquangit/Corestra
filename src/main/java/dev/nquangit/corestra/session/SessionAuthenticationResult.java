package dev.nquangit.corestra.session;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Describes the observable result of a Session Handler authentication attempt.
 */
public final class SessionAuthenticationResult {
    /** High-level outcome suitable for UI and logging. */
    public enum Outcome {
        SUCCEEDED,
        SKIPPED,
        FAILED
    }

    private final String profileId;
    private final Outcome outcome;
    private final String reason;
    private final Optional<SessionProfile> profile;
    private final List<String> warnings;

    /**
     * Creates a session authentication result.
     *
     * @param profileId session profile id
     * @param outcome authentication outcome
     * @param reason human-readable reason
     * @param profile current session profile, when available
     * @param warnings non-fatal warnings
     */
    public SessionAuthenticationResult(
            String profileId,
            Outcome outcome,
            String reason,
            Optional<SessionProfile> profile,
            List<String> warnings) {
        this.profileId = profileId == null ? "" : profileId;
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.reason = reason == null ? "" : reason;
        this.profile = Objects.requireNonNull(profile, "profile");
        this.warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    /** Returns the requested Session Profile id. */
    public String profileId() {
        return profileId;
    }

    /** Returns the high-level outcome. */
    public Outcome outcome() {
        return outcome;
    }

    /** Returns a human-readable explanation. */
    public String reason() {
        return reason;
    }

    /** Returns the current Session Profile when it exists. */
    public Optional<SessionProfile> profile() {
        return profile;
    }

    /** Returns non-fatal warnings produced by authentication. */
    public List<String> warnings() {
        return warnings;
    }

    /** Returns whether authentication completed successfully. */
    public boolean succeeded() {
        return outcome == Outcome.SUCCEEDED;
    }

    /** Returns whether no authentication flow was run. */
    public boolean skipped() {
        return outcome == Outcome.SKIPPED;
    }

    /** Returns whether authentication failed. */
    public boolean failed() {
        return outcome == Outcome.FAILED;
    }

    static SessionAuthenticationResult succeeded(
            SessionProfile profile, String reason, List<String> warnings) {
        return new SessionAuthenticationResult(profile.id(), Outcome.SUCCEEDED, reason,
                Optional.of(profile), warnings == null ? List.of() : warnings);
    }

    static SessionAuthenticationResult skipped(SessionProfile profile, String reason) {
        return new SessionAuthenticationResult(profile.id(), Outcome.SKIPPED, reason,
                Optional.of(profile), List.of());
    }

    static SessionAuthenticationResult failed(
            String profileId, String reason, Optional<SessionProfile> profile, List<String> warnings) {
        return new SessionAuthenticationResult(profileId, Outcome.FAILED, reason, profile,
                warnings == null ? List.of() : warnings);
    }
}
