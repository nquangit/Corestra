package dev.nquangit.corestra.bootstrap;

import burp.api.montoya.MontoyaApi;
import dev.nquangit.corestra.actions.CorestraContextMenuProvider;
import dev.nquangit.corestra.actions.RunFlowAction;
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.integration.AutoTriggerHttpHandler;
import dev.nquangit.corestra.integration.CorestraSessionHandlingAction;
import dev.nquangit.corestra.integration.MontoyaHttpClient;
import dev.nquangit.corestra.integration.SessionHandlingFlowMapper;
import dev.nquangit.corestra.persistence.FlowRepository;
import dev.nquangit.corestra.persistence.FlowSerializer;
import dev.nquangit.corestra.persistence.PersistenceMigrationService;
import dev.nquangit.corestra.persistence.ProjectMetadataRepository;
import dev.nquangit.corestra.persistence.ProjectSerializer;
import dev.nquangit.corestra.ui.CorestraRootPanel;
import dev.nquangit.corestra.ui.flowise.FlowWorkspaceController;
import dev.nquangit.corestra.ui.flowise.FlowisePanel;
import dev.nquangit.corestra.ui.project.ProjectPanel;

import java.util.List;
import java.util.UUID;

/**
 * Creates the shared runtime wiring for the Corestra extension and registers the root Burp tab.
 */
public final class CorestraBootstrap {

    /**
     * Initializes shared runtime services, builds the UI, and registers the Corestra tab in
     * the Burp Suite window.
     *
     * @param api the Montoya API provided by Burp Suite
     * @return the shared service registry for the running extension instance
     */
    public ServiceRegistry initialize(MontoyaApi api) {
        // Persistence layer
        FlowSerializer serializer = new FlowSerializer();
        PersistenceMigrationService migration = new PersistenceMigrationService();
        FlowRepository flowRepository = new FlowRepository(api.persistence(), serializer, migration);

        // Flow execution engine wired to the Montoya HTTP transport.
        ExtractionService extractionService = new ExtractionService();
        InjectionService injectionService = new InjectionService();
        FlowEngine flowEngine = new FlowEngine(
                extractionService, injectionService, new MontoyaHttpClient(api));

        ServiceRegistry registry = new ServiceRegistry(api, flowRepository, flowEngine);

        // UI
        FlowWorkspaceController controller = new FlowWorkspaceController(flowRepository, flowEngine);
        seedDefaultFlow(controller);

        ProjectMetadataRepository metadataRepo = new ProjectMetadataRepository(api.persistence());
        ProjectSerializer projectSerializer = new ProjectSerializer(serializer);
        FlowisePanel flowisePanel = new FlowisePanel(controller, api);
        ProjectPanel projectPanel = new ProjectPanel(controller, metadataRepo, projectSerializer);
        CorestraRootPanel rootPanel = new CorestraRootPanel(flowisePanel, projectPanel, api);

        api.userInterface().registerSuiteTab("Corestra", rootPanel);
        api.userInterface().registerContextMenuItemsProvider(
                new CorestraContextMenuProvider(controller));

        // Session-handling integration: run opted-in prerequisite flows before Burp's request.
        RunFlowAction runFlowAction = new RunFlowAction(flowEngine);
        api.http().registerSessionHandlingAction(new CorestraSessionHandlingAction(
                controller::flows, runFlowAction::run, new SessionHandlingFlowMapper()));

        // Auto-trigger: per-step trigger rules inject flow context into matching live requests.
        api.http().registerHttpHandler(new AutoTriggerHttpHandler(
                controller::flows, flowEngine, extractionService, injectionService));

        return registry;
    }

    private static void seedDefaultFlow(FlowWorkspaceController controller) {
        if (!controller.flows().isEmpty()) {
            return;
        }
        FlowDefinition defaultFlow = new FlowDefinition(
                UUID.randomUUID().toString(),
                "New Flow",
                "",
                true,
                List.of(),
                FlowRuleSet.empty(),
                AggregationPlan.disabled(),
                FlowTriggerConfig.none());
        controller.addFlow(defaultFlow);
        controller.setActiveFlow(defaultFlow);
    }
}
