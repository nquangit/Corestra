package dev.nquangit.corestra.access.matrix;

import java.util.Objects;

/**
 * Explains why a matrix generation candidate was skipped.
 */
public final class MatrixGenerationSkipReason {
    private final String objectType;
    private final String objectId;
    private final String reason;

    /**
     * Creates a skip reason.
     *
     * @param objectType object type, such as AuthProfile or DataProfile
     * @param objectId object id
     * @param reason human-readable reason
     */
    public MatrixGenerationSkipReason(String objectType, String objectId, String reason) {
        this.objectType = normalizeOptionalText(objectType);
        this.objectId = normalizeOptionalText(objectId);
        this.reason = normalizeRequiredText(reason, "reason");
    }

    /** @return object type */
    public String objectType() {
        return objectType;
    }

    /** @return object id */
    public String objectId() {
        return objectId;
    }

    /** @return skip reason */
    public String reason() {
        return reason;
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }

    private static String normalizeOptionalText(String value) {
        return Objects.requireNonNullElse(value, "").strip();
    }
}
