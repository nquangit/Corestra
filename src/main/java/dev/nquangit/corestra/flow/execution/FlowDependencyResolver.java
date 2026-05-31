package dev.nquangit.corestra.flow.execution;

import dev.nquangit.corestra.flow.FlowDefinition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves which flows must run before a given flow, in the correct topological order.
 *
 * <p>Transitive prerequisites are resolved recursively. Cyclic dependencies are detected
 * and cause an {@link IllegalStateException} to be thrown with a descriptive message
 * including the cycle path.
 */
public final class FlowDependencyResolver {

    /**
     * Resolves the ordered list of prerequisite flows that must run before {@code target}.
     *
     * <p>The returned list is ordered so that a flow always appears before the flows that
     * depend on it. {@code target} itself is not included in the returned list.
     *
     * @param target   the flow whose prerequisites should be resolved; must not be {@code null}
     * @param allFlows a map of all known flows keyed by flow identifier; must not be {@code null}
     * @return an ordered list of prerequisite flows (prerequisites before their dependents)
     * @throws IllegalArgumentException if a referenced prerequisite flow id is not in {@code allFlows}
     * @throws IllegalStateException    if a cyclic dependency is detected
     */
    public List<FlowDefinition> resolvePrerequisites(
            FlowDefinition target,
            Map<String, FlowDefinition> allFlows) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(allFlows, "allFlows");

        List<FlowDefinition> ordered = new ArrayList<>();
        LinkedHashSet<String> visiting = new LinkedHashSet<>();
        visit(target.id(), target, allFlows, visiting, ordered);
        // Remove the target itself — callers want only prerequisites
        if (!ordered.isEmpty() && ordered.get(ordered.size() - 1).id().equals(target.id())) {
            ordered.remove(ordered.size() - 1);
        }
        return List.copyOf(ordered);
    }

    private void visit(
            String currentId,
            FlowDefinition current,
            Map<String, FlowDefinition> allFlows,
            LinkedHashSet<String> visiting,
            List<FlowDefinition> ordered) {

        if (visiting.contains(currentId)) {
            // Build a readable cycle description
            List<String> cycleIds = new ArrayList<>(visiting);
            int cycleStart = cycleIds.indexOf(currentId);
            List<String> cyclePath = cycleIds.subList(cycleStart, cycleIds.size());
            cyclePath.add(currentId); // close the loop
            throw new IllegalStateException(
                    "Cyclic dependency detected in flow prerequisites: " + String.join(" -> ", cyclePath));
        }

        visiting.add(currentId);

        for (String prereqId : current.triggerConfig().prerequisiteFlowIds()) {
            FlowDefinition prereq = allFlows.get(prereqId);
            if (prereq == null) {
                throw new IllegalArgumentException(
                        "Prerequisite flow not found: '" + prereqId + "' (required by flow '" + currentId + "')");
            }
            // Only visit if not already in the ordered result (avoid duplicate entries)
            boolean alreadyAdded = ordered.stream().anyMatch(f -> f.id().equals(prereqId));
            if (!alreadyAdded) {
                visit(prereqId, prereq, allFlows, visiting, ordered);
            }
        }

        ordered.add(current);
        visiting.remove(currentId);
    }
}
