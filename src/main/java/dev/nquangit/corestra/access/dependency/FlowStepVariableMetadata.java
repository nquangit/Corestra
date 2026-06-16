package dev.nquangit.corestra.access.dependency;

import dev.nquangit.corestra.access.variable.ScopedVariableRef;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Captures variable dependencies and outputs for one flow step.
 */
public final class FlowStepVariableMetadata {
    private final String flowId;
    private final String stepId;
    private final Set<ScopedVariableRef> requires;
    private final Set<ScopedVariableRef> produces;

    /**
     * Creates variable metadata for one flow step.
     *
     * @param flowId flow identifier
     * @param stepId step identifier
     * @param requires scoped variables required by the step
     * @param produces scoped variables produced by the step
     */
    public FlowStepVariableMetadata(
            String flowId,
            String stepId,
            Set<ScopedVariableRef> requires,
            Set<ScopedVariableRef> produces) {
        this.flowId = normalizeRequiredText(flowId, "flowId");
        this.stepId = normalizeRequiredText(stepId, "stepId");
        this.requires = copyRefs(requires);
        this.produces = copyRefs(produces);
    }

    /**
     * Returns the flow identifier.
     *
     * @return flow identifier
     */
    public String flowId() {
        return flowId;
    }

    /**
     * Returns the step identifier.
     *
     * @return step identifier
     */
    public String stepId() {
        return stepId;
    }

    /**
     * Returns variables required by the step.
     *
     * @return immutable required variable refs
     */
    public Set<ScopedVariableRef> requires() {
        return requires;
    }

    /**
     * Returns variables produced by the step.
     *
     * @return immutable produced variable refs
     */
    public Set<ScopedVariableRef> produces() {
        return produces;
    }

    /**
     * Compares this metadata to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FlowStepVariableMetadata that)) {
            return false;
        }
        return Objects.equals(flowId, that.flowId)
                && Objects.equals(stepId, that.stepId)
                && Objects.equals(requires, that.requires)
                && Objects.equals(produces, that.produces);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(flowId, stepId, requires, produces);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return diagnostic string
     */
    @Override
    public String toString() {
        return "FlowStepVariableMetadata{" +
                "flowId='" + flowId + '\'' +
                ", stepId='" + stepId + '\'' +
                ", requires=" + requires +
                ", produces=" + produces +
                '}';
    }

    private static Set<ScopedVariableRef> copyRefs(Set<ScopedVariableRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<ScopedVariableRef> copied = new LinkedHashSet<>();
        refs.forEach(ref -> copied.add(Objects.requireNonNull(ref, "ref")));
        return Set.copyOf(copied);
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
