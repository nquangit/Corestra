package dev.nquangit.corestra.access.validation;

import java.util.Objects;

/**
 * Describes one access workspace validation issue.
 */
public final class AccessValidationIssue {
    private final String id;
    private final AccessValidationSeverity severity;
    private final AccessValidationCategory category;
    private final String title;
    private final String message;
    private final String relatedObjectType;
    private final String relatedObjectId;
    private final String suggestedFix;

    /**
     * Creates a validation issue.
     *
     * @param id stable issue identifier for this validation run
     * @param severity issue severity
     * @param category issue category
     * @param title short issue title
     * @param message detailed issue message
     * @param relatedObjectType related object type, or empty
     * @param relatedObjectId related object id, or empty
     * @param suggestedFix suggested remediation, or empty
     */
    public AccessValidationIssue(
            String id,
            AccessValidationSeverity severity,
            AccessValidationCategory category,
            String title,
            String message,
            String relatedObjectType,
            String relatedObjectId,
            String suggestedFix) {
        this.id = normalizeRequiredText(id, "id");
        this.severity = Objects.requireNonNull(severity, "severity");
        this.category = Objects.requireNonNull(category, "category");
        this.title = normalizeRequiredText(title, "title");
        this.message = normalizeOptionalText(message);
        this.relatedObjectType = normalizeOptionalText(relatedObjectType);
        this.relatedObjectId = normalizeOptionalText(relatedObjectId);
        this.suggestedFix = normalizeOptionalText(suggestedFix);
    }

    /** @return stable issue identifier */
    public String id() {
        return id;
    }

    /** @return issue severity */
    public AccessValidationSeverity severity() {
        return severity;
    }

    /** @return issue category */
    public AccessValidationCategory category() {
        return category;
    }

    /** @return short issue title */
    public String title() {
        return title;
    }

    /** @return detailed issue message */
    public String message() {
        return message;
    }

    /** @return related object type, or empty */
    public String relatedObjectType() {
        return relatedObjectType;
    }

    /** @return related object id, or empty */
    public String relatedObjectId() {
        return relatedObjectId;
    }

    /** @return suggested remediation, or empty */
    public String suggestedFix() {
        return suggestedFix;
    }

    /**
     * Compares this issue to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccessValidationIssue that)) {
            return false;
        }
        return Objects.equals(id, that.id)
                && severity == that.severity
                && category == that.category
                && Objects.equals(title, that.title)
                && Objects.equals(message, that.message)
                && Objects.equals(relatedObjectType, that.relatedObjectType)
                && Objects.equals(relatedObjectId, that.relatedObjectId)
                && Objects.equals(suggestedFix, that.suggestedFix);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, severity, category, title, message,
                relatedObjectType, relatedObjectId, suggestedFix);
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }

    private static String normalizeOptionalText(String value) {
        return value == null ? "" : value.strip();
    }
}
