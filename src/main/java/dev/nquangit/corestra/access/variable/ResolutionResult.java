package dev.nquangit.corestra.access.variable;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of resolving one template expression.
 *
 * @param value the resolved value when available
 * @param ref the resolved or attempted variable reference when available
 * @param unresolved unresolved diagnostics when resolution failed
 * @param warning optional non-fatal warning text
 */
public record ResolutionResult(
        Optional<String> value,
        Optional<ScopedVariableRef> ref,
        Optional<UnresolvedVariable> unresolved,
        Optional<String> warning) {
    /**
     * Creates a resolution result.
     *
     * @param value the resolved value when available
     * @param ref the resolved or attempted variable reference when available
     * @param unresolved unresolved diagnostics when resolution failed
     * @param warning optional non-fatal warning text
     */
    public ResolutionResult {
        value = Objects.requireNonNull(value, "value");
        ref = Objects.requireNonNull(ref, "ref");
        unresolved = Objects.requireNonNull(unresolved, "unresolved");
        warning = Objects.requireNonNull(warning, "warning");
    }

    /**
     * Creates a successful resolution result.
     *
     * @param value the resolved value
     * @param ref the variable reference that resolved
     * @param warning optional non-fatal warning text
     * @return a successful resolution result
     */
    public static ResolutionResult resolved(String value, ScopedVariableRef ref, Optional<String> warning) {
        return new ResolutionResult(
                Optional.of(Objects.requireNonNull(value, "value")),
                Optional.of(Objects.requireNonNull(ref, "ref")),
                Optional.empty(),
                Objects.requireNonNull(warning, "warning"));
    }

    /**
     * Creates a failed resolution result.
     *
     * @param unresolved unresolved diagnostics
     * @return a failed resolution result
     */
    public static ResolutionResult unresolved(UnresolvedVariable unresolved) {
        return new ResolutionResult(
                Optional.empty(),
                unresolved.ref(),
                Optional.of(Objects.requireNonNull(unresolved, "unresolved")),
                Optional.empty());
    }

    /**
     * Returns whether the expression resolved to a value.
     *
     * @return true when a value is available
     */
    public boolean resolved() {
        return value.isPresent();
    }
}
