package dev.nquangit.corestra.integration;

import dev.nquangit.corestra.flow.FlowDefinition;

import java.util.List;
import java.util.Objects;

/**
 * Selects which flows should run as prerequisites during Burp session handling.
 *
 * <p>A flow is applicable when it is enabled and its
 * {@link dev.nquangit.corestra.flow.FlowTriggerConfig#sessionHandlingIntegrationEnabled() trigger config}
 * opts into session-handling integration. Declaration order is preserved so prerequisite flows
 * run in a predictable sequence.</p>
 */
public final class SessionHandlingFlowMapper {

    /**
     * Returns the flows that should run for a session-handling invocation, in declaration order.
     *
     * @param allFlows the full set of defined flows
     * @return the applicable flows; never {@code null}
     * @throws NullPointerException if {@code allFlows} is {@code null}
     */
    public List<FlowDefinition> applicableFlows(List<FlowDefinition> allFlows) {
        Objects.requireNonNull(allFlows, "allFlows");
        return allFlows.stream()
                .filter(FlowDefinition::enabled)
                .filter(flow -> flow.triggerConfig().sessionHandlingIntegrationEnabled())
                .toList();
    }
}
