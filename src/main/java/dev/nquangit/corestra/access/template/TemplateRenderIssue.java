package dev.nquangit.corestra.access.template;

import dev.nquangit.corestra.access.variable.UnresolvedVariable;

import java.util.Objects;

/**
 * Associates an unresolved placeholder with the request part where it appeared.
 */
public final class TemplateRenderIssue {
    private final String location;
    private final UnresolvedVariable unresolvedVariable;

    /**
     * Creates a template render issue.
     *
     * @param location request-part location such as path, header[2], or body
     * @param unresolvedVariable unresolved variable diagnostics
     */
    public TemplateRenderIssue(String location, UnresolvedVariable unresolvedVariable) {
        this.location = normalizeRequiredText(location, "location");
        this.unresolvedVariable = Objects.requireNonNull(unresolvedVariable, "unresolvedVariable");
    }

    /**
     * Returns the request-part location.
     *
     * @return request-part location
     */
    public String location() {
        return location;
    }

    /**
     * Returns the unresolved variable diagnostics.
     *
     * @return unresolved variable diagnostics
     */
    public UnresolvedVariable unresolvedVariable() {
        return unresolvedVariable;
    }

    /**
     * Compares this issue to another object.
     *
     * @param other the candidate object
     * @return true when the objects are equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TemplateRenderIssue that)) {
            return false;
        }
        return Objects.equals(location, that.location)
                && Objects.equals(unresolvedVariable, that.unresolvedVariable);
    }

    /**
     * Returns the hash code for this issue.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(location, unresolvedVariable);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "TemplateRenderIssue{" +
                "location='" + location + '\'' +
                ", unresolvedVariable=" + unresolvedVariable +
                '}';
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
