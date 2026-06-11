package dev.nquangit.corestra.access.variable;

import java.util.Objects;

/**
 * Describes a syntactically valid placeholder found in a template.
 *
 * @param placeholder the exact placeholder text from the template
 * @param expression the trimmed expression inside the placeholder
 * @param startOffset inclusive start offset in the template
 * @param endOffset exclusive end offset in the template
 */
public record TemplatePlaceholder(String placeholder, String expression, int startOffset, int endOffset) {
    /**
     * Creates a template placeholder descriptor.
     *
     * @param placeholder the exact placeholder text from the template
     * @param expression the trimmed expression inside the placeholder
     * @param startOffset inclusive start offset in the template
     * @param endOffset exclusive end offset in the template
     */
    public TemplatePlaceholder {
        Objects.requireNonNull(placeholder, "placeholder");
        Objects.requireNonNull(expression, "expression");
        if (expression.isBlank()) {
            throw new IllegalArgumentException("Placeholder expression must not be blank");
        }
        if (startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException("Offsets must be non-negative and endOffset must be >= startOffset");
        }
    }
}
