package dev.nquangit.corestra.access.auth.runtime;

import dev.nquangit.corestra.access.auth.AuthProfile;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result returned by {@link AuthManager#ensureAuth(String, String, java.util.Map, dev.nquangit.corestra.access.variable.RenderMode)}
 * and retry-oriented auth operations.
 */
public final class AuthEnsureResult {
    private final String authProfileId;
    private final AuthEnsureStatus status;
    private final String reason;
    private final Optional<AuthProfile> updatedProfile;
    private final List<String> warnings;

    /**
     * Creates an auth ensure result.
     *
     * @param authProfileId auth profile id
     * @param status ensure status
     * @param reason human-readable reason
     * @param updatedProfile updated profile, if one was available
     * @param warnings warnings emitted during ensure
     */
    public AuthEnsureResult(
            String authProfileId,
            AuthEnsureStatus status,
            String reason,
            Optional<AuthProfile> updatedProfile,
            List<String> warnings) {
        this.authProfileId = normalizeRequiredText(authProfileId, "authProfileId");
        this.status = Objects.requireNonNull(status, "status");
        this.reason = reason == null ? "" : reason;
        this.updatedProfile = Objects.requireNonNull(updatedProfile, "updatedProfile");
        this.warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    /**
     * Returns a successful result.
     *
     * @param authProfileId auth profile id
     * @param status success status
     * @param reason reason
     * @param updatedProfile updated profile
     * @param warnings warnings
     * @return successful result
     */
    public static AuthEnsureResult success(
            String authProfileId,
            AuthEnsureStatus status,
            String reason,
            AuthProfile updatedProfile,
            List<String> warnings) {
        if (status == AuthEnsureStatus.FAILED) {
            throw new IllegalArgumentException("success status must not be FAILED");
        }
        return new AuthEnsureResult(authProfileId, status, reason,
                Optional.ofNullable(updatedProfile), warnings == null ? List.of() : warnings);
    }

    /**
     * Returns a failed result.
     *
     * @param authProfileId auth profile id
     * @param reason failure reason
     * @param updatedProfile profile available at failure time
     * @param warnings warnings
     * @return failed result
     */
    public static AuthEnsureResult failed(
            String authProfileId,
            String reason,
            Optional<AuthProfile> updatedProfile,
            List<String> warnings) {
        return new AuthEnsureResult(authProfileId, AuthEnsureStatus.FAILED, reason,
                Objects.requireNonNull(updatedProfile, "updatedProfile"),
                warnings == null ? List.of() : warnings);
    }

    /**
     * Returns the auth profile id.
     *
     * @return auth profile id
     */
    public String authProfileId() {
        return authProfileId;
    }

    /**
     * Returns the ensure status.
     *
     * @return ensure status
     */
    public AuthEnsureStatus status() {
        return status;
    }

    /**
     * Returns the human-readable reason.
     *
     * @return reason
     */
    public String reason() {
        return reason;
    }

    /**
     * Returns the updated profile when available.
     *
     * @return updated profile
     */
    public Optional<AuthProfile> updatedProfile() {
        return updatedProfile;
    }

    /**
     * Returns warnings emitted during ensure.
     *
     * @return immutable warnings
     */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * Returns whether the ensure operation failed.
     *
     * @return true when failed
     */
    public boolean failed() {
        return status == AuthEnsureStatus.FAILED;
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
