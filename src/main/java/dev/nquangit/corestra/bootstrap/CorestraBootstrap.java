package dev.nquangit.corestra.bootstrap;

import burp.api.montoya.MontoyaApi;
import dev.nquangit.corestra.actions.CorestraContextMenuProvider;
import dev.nquangit.corestra.actions.RunFlowAction;
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.flow.execution.FlowExecutionLog;
import dev.nquangit.corestra.flow.execution.ThreadSleeper;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.http.discovery.ParameterDictionary;
import dev.nquangit.corestra.http.discovery.ParameterDiscoveryHandler;
import dev.nquangit.corestra.integration.AutoTriggerHttpHandler;
import dev.nquangit.corestra.integration.ActorIsolatedHttpClient;
import dev.nquangit.corestra.integration.CorestraSessionHttpHandler;
import dev.nquangit.corestra.integration.CorestraSessionHandlingAction;
import dev.nquangit.corestra.integration.HttpClient;
import dev.nquangit.corestra.integration.MontoyaHttpClient;
import dev.nquangit.corestra.integration.ProxyPipelineHttpClient;
import dev.nquangit.corestra.integration.RoutingHttpClient;
import dev.nquangit.corestra.integration.SessionAwareHttpClient;
import dev.nquangit.corestra.integration.SessionHandlingFlowMapper;
import dev.nquangit.corestra.persistence.FlowRepository;
import dev.nquangit.corestra.persistence.FlowSerializer;
import dev.nquangit.corestra.persistence.PersistenceMigrationService;
import dev.nquangit.corestra.persistence.ProjectMetadataRepository;
import dev.nquangit.corestra.persistence.ProjectDataMaintenanceService;
import dev.nquangit.corestra.persistence.ProjectSerializer;
import dev.nquangit.corestra.persistence.ProjectSettingsRepository;
import dev.nquangit.corestra.session.SessionManager;
import dev.nquangit.corestra.session.SessionProfileRepository;
import dev.nquangit.corestra.session.SessionSchedulerService;
import dev.nquangit.corestra.access.auth.runtime.AuthFlowExecutor;
import dev.nquangit.corestra.access.auth.runtime.AuthInjectionRuleRenderer;
import dev.nquangit.corestra.access.auth.runtime.AuthManager;
import dev.nquangit.corestra.access.auth.runtime.AuthRuleEvaluator;
import dev.nquangit.corestra.access.evaluate.BasicResultEvaluator;
import dev.nquangit.corestra.access.persistence.AccessWorkspaceProjectRepository;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.access.runner.AccessTargetRequestPreparer;
import dev.nquangit.corestra.access.runner.ManualAuthApplier;
import dev.nquangit.corestra.access.runner.AccessPlanRunner;
import dev.nquangit.corestra.access.runner.SingleAccessTestRunner;
import dev.nquangit.corestra.access.runner.StoredRequestHttpRequestAdapter;
import dev.nquangit.corestra.access.race.RaceRunner;
import dev.nquangit.corestra.access.scenario.ScenarioManager;
import dev.nquangit.corestra.access.scenario.ScenarioSetupFlowRunner;
import dev.nquangit.corestra.access.security.RedactionService;
import dev.nquangit.corestra.access.teardown.TeardownFlowRunner;
import dev.nquangit.corestra.access.template.AccessRequestTemplateRenderer;
import dev.nquangit.corestra.access.template.AccessScopedVariableStoreFactory;
import dev.nquangit.corestra.access.variable.DefaultRuntimeValueProvider;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.access.variable.VariableResolver;
import dev.nquangit.corestra.ui.CorestraRootPanel;
import dev.nquangit.corestra.ui.access.AccessTestingPanel;
import dev.nquangit.corestra.ui.access.AuthenticationPanel;
import dev.nquangit.corestra.ui.access.AccessUiController;
import dev.nquangit.corestra.ui.flowise.FlowWorkspaceController;
import dev.nquangit.corestra.ui.flowise.FlowisePanel;
import dev.nquangit.corestra.ui.project.ProjectPanel;
import dev.nquangit.corestra.ui.project.ParameterDictionaryPanel;
import dev.nquangit.corestra.ui.session.SessionHandlerPanel;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
        ProjectSettingsRepository settingsRepo = new ProjectSettingsRepository(api.persistence());
        HttpClient routingHttpClient = new RoutingHttpClient(
                settingsRepo::load,
                new MontoyaHttpClient(api),
                new ProxyPipelineHttpClient(settingsRepo::load));

        // Break the circular dependency (FlowEngine ↔ SessionManager) via a late-bound ref.
        AtomicReference<SessionManager> sessionManagerRef = new AtomicReference<>();
        SessionAwareHttpClient sessionAwareHttpClient = new SessionAwareHttpClient(
                routingHttpClient, sessionManagerRef::get, injectionService);
        HttpClient actorIsolatedHttpClient = new ActorIsolatedHttpClient(routingHttpClient);

        FlowEngine flowEngine = new FlowEngine(
                extractionService,
                injectionService,
                sessionAwareHttpClient,
                flowId -> flowRepository.loadAll().stream()
                        .filter(flow -> flow.id().equals(flowId))
                        .findFirst(),
                new ThreadSleeper());

        // Access Testing owns actor authentication and must never inherit the project-wide
        // Session Handler profile. Use the undecorated transport for its auth/setup/target/
        // teardown paths so an overlapping session scope cannot overwrite the selected actor.
        FlowEngine accessFlowEngine = new FlowEngine(
                extractionService,
                injectionService,
                actorIsolatedHttpClient,
                flowId -> flowRepository.loadAll().stream()
                        .filter(flow -> flow.id().equals(flowId))
                        .findFirst(),
                new ThreadSleeper());
        FlowExecutionLog executionLog = new FlowExecutionLog();
        flowEngine.addExecutionListener(executionLog::record);
        accessFlowEngine.addExecutionListener(executionLog::record);

        ServiceRegistry registry = new ServiceRegistry(api, flowRepository, flowEngine);

        // UI
        FlowWorkspaceController controller = new FlowWorkspaceController(flowRepository, flowEngine);
        seedDefaultFlow(controller);

        ProjectMetadataRepository metadataRepo = new ProjectMetadataRepository(api.persistence());
        ProjectSerializer projectSerializer = new ProjectSerializer(serializer);
        AccessWorkspaceProjectRepository accessProjectRepository = new AccessWorkspaceProjectRepository(api.persistence());
        InMemoryAccessRepository accessRepository = new InMemoryAccessRepository(
                accessProjectRepository.load(),
                accessProjectRepository::save);
        Clock authClock = Clock.systemUTC();
        AuthManager authManager = new AuthManager(
                accessRepository,
                new AuthFlowExecutor(flowRepository, accessFlowEngine, authClock),
                new AuthRuleEvaluator(),
                authClock);
        SessionProfileRepository sessionProfileRepository = new SessionProfileRepository(api.persistence());
        SessionManager sessionManager = new SessionManager(
                sessionProfileRepository,
                flowRepository::loadAll,
                flowEngine,
                extractionService,
                Clock.systemUTC(),
                accessRepository,
                authManager,
                new AuthInjectionRuleRenderer());
        sessionManagerRef.set(sessionManager);  // close the loop — SessionAwareHttpClient can now resolve the manager
        SessionSchedulerService sessionSchedulerService = new SessionSchedulerService(sessionManager, Clock.systemUTC());
        registry.registerCloseable(sessionSchedulerService);
        api.extension().registerUnloadingHandler(registry::close);
        sessionSchedulerService.start();
        TemplateRenderer accessTemplateRenderer = new TemplateRenderer(
                new VariableResolver(new DefaultRuntimeValueProvider()));
        AccessRequestTemplateRenderer accessRequestTemplateRenderer = new AccessRequestTemplateRenderer(accessTemplateRenderer);
        AccessScopedVariableStoreFactory accessStoreFactory = new AccessScopedVariableStoreFactory();
        ScenarioManager scenarioManager = new ScenarioManager(
                accessRepository,
                flowRepository,
                new ScenarioSetupFlowRunner(accessFlowEngine, authClock),
                authManager,
                new dev.nquangit.corestra.access.id.UuidAccessIdGenerator(),
                authClock);
        RedactionService redactionService = new RedactionService();
        StoredRequestHttpRequestAdapter requestAdapter = new StoredRequestHttpRequestAdapter();
        TeardownFlowRunner teardownFlowRunner = new TeardownFlowRunner(
                flowRepository::loadAll,
                extractionService,
                injectionService,
                actorIsolatedHttpClient,
                requestAdapter,
                redactionService,
                authClock);
        AccessTargetRequestPreparer raceRequestPreparer = new AccessTargetRequestPreparer(
                accessRequestTemplateRenderer,
                accessStoreFactory,
                new ManualAuthApplier(accessTemplateRenderer),
                requestAdapter);
        SingleAccessTestRunner accessRunner = new SingleAccessTestRunner(
                accessRequestTemplateRenderer,
                accessStoreFactory,
                actorIsolatedHttpClient,
                new BasicResultEvaluator(),
                redactionService,
                new ManualAuthApplier(accessTemplateRenderer),
                requestAdapter,
                new dev.nquangit.corestra.access.id.UuidAccessIdGenerator(),
                authManager,
                scenarioManager,
                teardownFlowRunner);
        AccessPlanRunner accessPlanRunner = new AccessPlanRunner(accessRepository, accessRunner);
        RaceRunner raceRunner = new RaceRunner(
                accessRepository,
                raceRequestPreparer,
                requestAdapter,
                actorIsolatedHttpClient,
                new BasicResultEvaluator(),
                redactionService,
                new dev.nquangit.corestra.access.id.UuidAccessIdGenerator(),
                accessRunner,
                authManager,
                scenarioManager,
                authClock,
                java.util.concurrent.Executors.newFixedThreadPool(
                        dev.nquangit.corestra.access.race.RaceRunConfig.MAX_CONCURRENCY));
        registry.registerCloseable(raceRunner);
        AccessUiController accessUiController = new AccessUiController(
                accessRepository,
                new dev.nquangit.corestra.access.id.UuidAccessIdGenerator(),
                accessRequestTemplateRenderer,
                accessStoreFactory,
                accessRunner,
                accessPlanRunner,
                flowRepository::loadAll,
                raceRunner,
                controller::updateFlow);
        AccessTestingPanel accessTestingPanel = new AccessTestingPanel(accessUiController);
        AuthenticationPanel authenticationPanel = new AuthenticationPanel(accessUiController);
        FlowisePanel flowisePanel = new FlowisePanel(controller, api, accessUiController, executionLog);
        SessionHandlerPanel sessionHandlerPanel = new SessionHandlerPanel(sessionManager, flowRepository::loadAll);
        ProjectPanel projectPanel = new ProjectPanel(controller, metadataRepo, projectSerializer,
                settingsRepo, accessRepository, sessionProfileRepository, () -> {
                    accessTestingPanel.refreshUi();
                    authenticationPanel.refreshUi();
                    sessionHandlerPanel.refreshProfiles();
                }, new ProjectDataMaintenanceService(api.persistence()), () -> {
                    if (accessUiController.isPlanRunning()) {
                        throw new IllegalStateException("Stop the active Access Testing plan before cleaning project data");
                    }
                    sessionManager.clearAllProfiles();
                });
        ParameterDictionary parameterDictionary = new ParameterDictionary();
        ParameterDiscoveryHandler parameterDiscoveryHandler =
                new ParameterDiscoveryHandler(parameterDictionary, redactionService);
        ParameterDictionaryPanel parameterDictionaryPanel =
                new ParameterDictionaryPanel(parameterDictionary, parameterDiscoveryHandler);
        CorestraRootPanel rootPanel = new CorestraRootPanel(
                flowisePanel, projectPanel, authenticationPanel, sessionHandlerPanel, accessTestingPanel,
                parameterDictionaryPanel, api);

        api.userInterface().registerSuiteTab("Corestra", rootPanel);
        api.userInterface().registerContextMenuItemsProvider(
                new CorestraContextMenuProvider(controller, accessUiController));

        // Session-handling integration: run opted-in prerequisite flows before Burp's request.
        RunFlowAction runFlowAction = new RunFlowAction(flowEngine);
        api.http().registerSessionHandlingAction(new CorestraSessionHandlingAction(
                controller::flows, runFlowAction::run, new SessionHandlingFlowMapper()));

        // Project-level Session Handler: scoped extraction/injection for live Burp/Corestra traffic.
        api.http().registerHttpHandler(new CorestraSessionHttpHandler(sessionManager, injectionService));

        // Observe the pipeline marker before AutoTrigger removes it from requests reaching Burp.
        // This keeps extension-origin proxy traffic out of the passive parameter dictionary.
        api.http().registerHttpHandler(parameterDiscoveryHandler);

        // Auto-trigger: per-step trigger rules inject flow context into matching live requests.
        AutoTriggerHttpHandler autoTriggerHttpHandler = new AutoTriggerHttpHandler(
                controller::flows, flowEngine, extractionService, injectionService);
        registry.registerCloseable(autoTriggerHttpHandler);
        api.http().registerHttpHandler(autoTriggerHttpHandler);

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
