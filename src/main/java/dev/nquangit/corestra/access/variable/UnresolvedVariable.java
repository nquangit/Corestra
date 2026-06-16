package dev.nquangit.corestra.access.variable;

import java.util.Objects;
import java.util.Optional;

/**
 * Reports a placeholder that was preserved because it could not be resolved.
 *
 * @param placeholder the exact placeholder text from the template
 * @param expression the trimmed expression inside the placeholder
 * @param ref the parsed reference when one is available
 * @param errorType the unresolved error category
 * @param message human-readable detail for diagnostics
 * @param startOffset inclusive start offset in the template
 * @param endOffset exclusive end offset in the template
 */
public record UnresolvedVariable(
        String placeholder,
        String expression,
        Optional<ScopedVariableRef> ref,
        VariableErrorType errorType,
        String message,
        int startOffset,
        int endOffset) {
    /**
     * Creates unresolved variable diagnostics.
     *
     * @param placeholder the exact placeholder text from the template
     * @param expression the trimmed expression inside the placeholder
     * @param ref the parsed reference when one is available
     * @param errorType the unresolved error category
     * @param message human-readable detail for diagnostics
     * @param startOffset inclusive start offset in the template
     * @param endOffset exclusive end offset in the template
     */
    public UnresolvedVariable {
        Objects.requireNonNull(placeholder, "placeholder");
        Objects.requireNonNull(expression, "expression");
        ref = Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(errorType, "errorType");
        Objects.requireNonNull(message, "message");
        if (startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException("Offsets must be non-negative and endOffset must be >= startOffset");
        }
    }
}
