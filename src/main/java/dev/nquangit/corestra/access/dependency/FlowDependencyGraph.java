package dev.nquangit.corestra.access.dependency;

import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.VariableScope;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Describes variable dependencies and producers across the ordered steps of one flow.
 */
public final class FlowDependencyGraph {
    private final String flowId;
    private final List<FlowStepVariableMetadata> steps;
    private final List<String> warnings;

    /**
     * Creates a dependency graph with no warnings.
     *
     * @param flowId flow identifier
     * @param steps ordered step metadata
     */
    public FlowDependencyGraph(String flowId, List<FlowStepVariableMetadata> steps) {
        this(flowId, steps, List.of());
    }

    /**
     * Creates a dependency graph.
     *
     * @param flowId flow identifier
     * @param steps ordered step metadata
     * @param warnings non-fatal analysis warnings
     */
    public FlowDependencyGraph(String flowId, List<FlowStepVariableMetadata> steps, List<String> warnings) {
        this.flowId = normalizeRequiredText(flowId, "flowId");
        this.steps = copySteps(steps);
        this.warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
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
     * Returns ordered step metadata.
     *
     * @return immutable step metadata
     */
    public List<FlowStepVariableMetadata> steps() {
        return steps;
    }

    /**
     * Returns analysis warnings.
     *
     * @return immutable warnings
     */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * Finds metadata for one step.
     *
     * @param stepId step identifier
     * @return matching metadata, or empty
     */
    public Optional<FlowStepVariableMetadata> metadataForStep(String stepId) {
        if (stepId == null || stepId.isBlank()) {
            return Optional.empty();
        }
        String normalized = stepId.strip();
        return steps.stream().filter(step -> step.stepId().equals(normalized)).findFirst();
    }

    /**
     * Finds all step metadata entries that produce a variable.
     *
     * @param ref variable reference
     * @return producer metadata in flow order
     */
    public List<FlowStepVariableMetadata> producersFor(ScopedVariableRef ref) {
        Objects.requireNonNull(ref, "ref");
        return steps.stream()
                .filter(step -> step.produces().contains(ref))
                .toList();
    }

    /**
     * Returns scenario-scoped variables required by the target step.
     *
     * @param stepId step identifier
     * @return required scenario variables
     */
    public Set<ScopedVariableRef> requiredScenarioVariablesFor(String stepId) {
        return metadataForStep(stepId)
                .map(FlowStepVariableMetadata::requires)
                .map(refs -> refs.stream()
                        .filter(ref -> ref.scope() == VariableScope.SCENARIO)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)))
                .map(Set::copyOf)
                .orElseGet(Set::of);
    }

    /**
     * Returns the ordered index for a step id.
     *
     * @param stepId step identifier
     * @return zero-based index, or -1 when absent
     */
    public int indexOfStep(String stepId) {
        if (stepId == null || stepId.isBlank()) {
            return -1;
        }
        String normalized = stepId.strip();
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).stepId().equals(normalized)) {
                return index;
            }
        }
        return -1;
    }

    private static List<FlowStepVariableMetadata> copySteps(List<FlowStepVariableMetadata> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        ArrayList<FlowStepVariableMetadata> copied = new ArrayList<>();
        steps.forEach(step -> copied.add(Objects.requireNonNull(step, "step")));
        return List.copyOf(copied);
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
