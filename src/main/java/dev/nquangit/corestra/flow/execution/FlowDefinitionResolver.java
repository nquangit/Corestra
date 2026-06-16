package dev.nquangit.corestra.flow.execution;

import dev.nquangit.corestra.flow.FlowDefinition;

import java.util.Optional;

/**
 * Resolves persisted flow definitions for control actions that trigger another flow.
 */
@FunctionalInterface
public interface FlowDefinitionResolver {

    /**
     * Resolves a flow by identifier.
     *
     * @param flowId the flow identifier
     * @return the resolved flow, or empty when no flow is available
     */
    Optional<FlowDefinition> resolve(String flowId);

    /**
     * Returns a resolver that never resolves a flow.
     *
     * @return an empty resolver
     */
    static FlowDefinitionResolver empty() {
        return flowId -> Optional.empty();
    }
}
