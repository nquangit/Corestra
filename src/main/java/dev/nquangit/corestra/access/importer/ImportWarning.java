package dev.nquangit.corestra.access.importer;

import java.util.Objects;

/**
 * Non-fatal import diagnostic.
 */
public final class ImportWarning {
    private final String location;
    private final String message;

    /**
     * Creates an import warning.
     *
     * @param location source location
     * @param message warning message
     */
    public ImportWarning(String location, String message) {
        this.location = normalizeOptional(location);
        this.message = normalizeRequired(message, "message");
    }

    /**
     * Returns the source location.
     *
     * @return source location, or an empty string
     */
    public String location() {
        return location;
    }

    /**
     * Returns the warning message.
     *
     * @return warning message
     */
    public String message() {
        return message;
    }

    private static String normalizeOptional(String value) {
        return Objects.requireNonNullElse(value, "").strip();
    }

    private static String normalizeRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
