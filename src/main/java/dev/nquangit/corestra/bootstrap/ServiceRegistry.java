package dev.nquangit.corestra.bootstrap;

import burp.api.montoya.MontoyaApi;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.persistence.FlowRepository;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds shared runtime singletons for the Corestra extension.
 *
 * <p>No Swing component should construct repositories or services directly; obtain them
 * from this registry instead.</p>
 */
public final class ServiceRegistry implements AutoCloseable {
    private final MontoyaApi montoyaApi;
    private final FlowRepository flowRepository;
    private final FlowEngine flowEngine;
    private final CopyOnWriteArrayList<AutoCloseable> closeables = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();

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
     * Registers a service that should be closed when the extension unloads.
     *
     * @param closeable service to close
     * @return this registry
     */
    public ServiceRegistry registerCloseable(AutoCloseable closeable) {
        AutoCloseable checked = Objects.requireNonNull(closeable, "closeable");
        if (closed.get()) {
            closeQuietly(checked);
            return this;
        }
        closeables.add(checked);
        if (closed.get() && closeables.remove(checked)) {
            closeQuietly(checked);
        }
        return this;
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

    /**
     * Closes registered runtime services. This method is idempotent.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (int i = closeables.size() - 1; i >= 0; i--) {
            closeQuietly(closeables.get(i));
        }
        closeables.clear();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Extension unload cleanup must not throw back into Burp.
        }
    }
}
