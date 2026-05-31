package dev.nquangit.corestra;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import dev.nquangit.corestra.bootstrap.CorestraBootstrap;
import dev.nquangit.corestra.bootstrap.ServiceRegistry;

import java.util.Objects;

/**
 * Montoya entrypoint for the Corestra extension.
 */
public final class CorestraExtension implements BurpExtension {
    private static final String EXTENSION_NAME = "Corestra";

    private final BootstrapDelegate bootstrapDelegate;
    private ServiceRegistry serviceRegistry;

    /**
     * Creates the default Corestra extension entrypoint.
     */
    public CorestraExtension() {
        this(new CorestraBootstrap()::initialize);
    }

    CorestraExtension(BootstrapDelegate bootstrapDelegate) {
        this.bootstrapDelegate = Objects.requireNonNull(bootstrapDelegate, "bootstrapDelegate");
    }

    /**
     * Initializes the extension name and delegates shared service wiring to the bootstrap.
     *
     * @param api the Montoya API provided by Burp Suite
     */
    @Override
    public void initialize(MontoyaApi api) {
        MontoyaApi checkedApi = Objects.requireNonNull(api, "api");
        checkedApi.extension().setName(EXTENSION_NAME);
        serviceRegistry = Objects.requireNonNull(bootstrapDelegate.initialize(checkedApi), "serviceRegistry");
    }

    /**
     * Minimal bootstrap seam for wiring the extension runtime during initialization.
     */
    @FunctionalInterface
    interface BootstrapDelegate {
        /**
         * Initializes shared runtime services for the supplied Montoya API.
         *
         * @param api the Montoya API provided by Burp Suite
         * @return the shared service registry for the running extension instance
         */
        ServiceRegistry initialize(MontoyaApi api);
    }
}
