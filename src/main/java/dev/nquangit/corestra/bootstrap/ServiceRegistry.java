package dev.nquangit.corestra.bootstrap;

import burp.api.montoya.MontoyaApi;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.persistence.FlowRepository;

import java.util.Objects;

/**
 * Holds shared runtime singletons for the Corestra extension.
 *
 * <p>No Swing component should construct repositories or services directly; obtain them
 * from this registry instead.</p>
 */
public final class ServiceRegistry {
    private final MontoyaApi montoyaApi;
    private final FlowRepository flowRepository;
    private final FlowEngine flowEngine;

    /**
     * Creates a shared runtime registry.
     *
     * @param montoyaApi     the Montoya API instance for the running extension
     * @param flowRepository the shared flow repository backed by Burp persistence
     * @param flowEngine     the shared flow execution engine
     * @throws NullPointerException if any argument is {@code null}
     */
    public ServiceRegistry(MontoyaApi montoyaApi, FlowRepository flowRepository, FlowEngine flowEngine) {
        this.montoyaApi = Objects.requireNonNull(montoyaApi, "montoyaApi");
        this.flowRepository = Objects.requireNonNull(flowRepository, "flowRepository");
        this.flowEngine = Objects.requireNonNull(flowEngine, "flowEngine");
    }

    /**
     * Returns the shared Montoya API instance for the running extension.
     *
     * @return the shared Montoya API instance
     */
    public MontoyaApi montoyaApi() {
        return montoyaApi;
    }

    /**
     * Returns the shared flow repository.
     *
     * @return the shared flow repository
     */
    public FlowRepository flowRepository() {
        return flowRepository;
    }

    /**
     * Returns the shared flow execution engine.
     *
     * @return the shared flow execution engine
     */
    public FlowEngine flowEngine() {
        return flowEngine;
    }
}
