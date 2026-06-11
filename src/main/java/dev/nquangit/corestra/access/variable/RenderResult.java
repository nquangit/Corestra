package dev.nquangit.corestra.access.variable;

import java.util.List;
import java.util.Objects;

/**
 * Result returned after rendering a template.
 *
 * @param original the original template
 * @param rendered the rendered output with unresolved placeholders preserved
 * @param resolvedVariables variables resolved during rendering
 * @param unresolvedVariables variables that could not be resolved
 * @param warnings non-fatal compatibility or diagnostic warnings
 */
public record RenderResult(
        String original,
        String rendered,
        List<ScopedVariableRef> resolvedVariables,
        List<UnresolvedVariable> unresolvedVariables,
        List<String> warnings) {
    /**
     * Creates a render result.
     *
     * @param original the original template
     * @param rendered the rendered output with unresolved placeholders preserved
     * @param resolvedVariables variables resolved during rendering
     * @param unresolvedVariables variables that could not be resolved
     * @param warnings non-fatal compatibility or diagnostic warnings
     */
    public RenderResult {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(rendered, "rendered");
        resolvedVariables = List.copyOf(Objects.requireNonNull(resolvedVariables, "resolvedVariables"));
        unresolvedVariables = List.copyOf(Objects.requireNonNull(unresolvedVariables, "unresolvedVariables"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }

    /**
     * Returns whether the render completed with no unresolved variables.
     *
     * @return true when no unresolved variables were reported
     */
    public boolean isSuccess() {
        return unresolvedVariables.isEmpty();
    }

    /**
     * Returns whether any unresolved variables were reported.
     *
     * @return true when unresolved variables exist
     */
    public boolean hasUnresolvedVariables() {
        return !unresolvedVariables.isEmpty();
    }
}
