package dev.nquangit.corestra.access.scenario;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.runtime.AuthEnsureResult;
import dev.nquangit.corestra.access.auth.runtime.AuthManager;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.id.AccessIdGenerator;
import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.matrix.ScenarioPolicy;
import dev.nquangit.corestra.access.matrix.SetupAuthBinding;
import dev.nquangit.corestra.access.matrix.SetupConfig;
import dev.nquangit.corestra.access.matrix.SetupMode;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.persistence.FlowRepository;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Prepares scenario state for access tests by reusing existing scenarios or running setup flows.
 */
public final class ScenarioManager {
    private final InMemoryAccessRepository repository;
    private final Supplier<List<FlowDefinition>> flowSupplier;
    private final ScenarioSetupFlowRunner setupFlowRunner;
    private final AuthManager authManager;
    private final AccessIdGenerator idGenerator;
    private final Clock clock;

    /**
     * Creates a scenario manager backed by a flow repository.
     *
     * @param repository access repository used to save scenarios
     * @param flowRepository flow repository used to load setup flows
     * @param setupFlowRunner setup flow runner
     * @param authManager optional auth manager for setup auth ensure
     * @param idGenerator id generator for new scenarios
     * @param clock clock used for scenario timestamps
     */
    public ScenarioManager(
            InMemoryAccessRepository repository,
            FlowRepository flowRepository,
            ScenarioSetupFlowRunner setupFlowRunner,
            AuthManager authManager,
            AccessIdGenerator idGenerator,
            Clock clock) {
        this(repository,
                Objects.requireNonNull(flowRepository, "flowRepository")::loadAll,
                setupFlowRunner,
                authManager,
                idGenerator,
                clock);
    }

    /**
     * Creates a scenario manager with an injectable flow supplier.
     *
     * @param repository access repository used to save scenarios
     * @param flowSupplier supplier for setup flow definitions
     * @param setupFlowRunner setup flow runner
     * @param authManager optional auth manager for setup auth ensure
     * @param idGenerator id generator for new scenarios
     * @param clock clock used for scenario timestamps
     */
    public ScenarioManager(
            InMemoryAccessRepository repository,
            Supplier<List<FlowDefinition>> flowSupplier,
            ScenarioSetupFlowRunner setupFlowRunner,
            AuthManager authManager,
            AccessIdGenerator idGenerator,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.flowSupplier = Objects.requireNonNull(flowSupplier, "flowSupplier");
        this.setupFlowRunner = Objects.requireNonNull(setupFlowRunner, "setupFlowRunner");
        this.authManager = authManager;
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Prepares scenario state for an access test case.
     *
     * @param testCase test case requiring scenario state
     * @param workspace access workspace snapshot
     * @param env environment variables
     * @param legacy legacy variables
     * @param renderMode render mode requested by the caller
     * @return scenario preparation result
     */
    public ScenarioPreparationResult prepareScenario(
            AccessTestCase testCase,
            AccessWorkspace workspace,
            Map<String, String> env,
            Map<String, String> legacy,
            RenderMode renderMode) {
        Objects.requireNonNull(testCase, "testCase");
        Objects.requireNonNull(workspace, "workspace");
        RenderMode mode = Objects.requireNonNullElse(renderMode, RenderMode.STRICT);
        SetupConfig setup = testCase.setup();
        if (setup.mode() == SetupMode.NONE) {
            return ScenarioPreparationResult.success(Optional.empty(), "No scenario setup configured", List.of());
        }
        if (setup.mode() == SetupMode.REUSE_SCENARIO) {
            return reuseScenario(testCase, workspace);
        }

        Optional<ScenarioInstance> reusable = reusableScenario(testCase, workspace);
        if (reusable.isPresent()) {
            return ScenarioPreparationResult.success(reusable, "Reused existing scenario", List.of());
        }

        DataProfile dataProfile = dataProfile(testCase, workspace).orElse(null);
        Optional<AuthProfile> setupAuth = resolveSetupAuthProfile(testCase, workspace, dataProfile);
        if (setupAuth.isEmpty()) {
            return ScenarioPreparationResult.failed("Setup auth profile could not be resolved", List.of());
        }
        AuthProfile setupAuthProfile = ensureSetupAuth(setupAuth.get(), testCase, env, mode).orElse(null);
        if (setupAuthProfile == null) {
            return ScenarioPreparationResult.failed("Setup auth ensure failed", List.of());
        }

        FlowDefinition flow = setupFlow(setup.setupFlowId()).orElse(null);
        if (flow == null) {
            return ScenarioPreparationResult.failed("Setup flow not found: " + setup.setupFlowId(), List.of());
        }
        if (!flow.enabled()) {
            return ScenarioPreparationResult.failed("Setup flow is disabled: " + flow.id(), List.of());
        }

        String untilBeforeStepId = setup.mode() == SetupMode.RUN_PREFIX ? setup.untilBeforeStepId() : "";
        ScenarioSetupRunResult runResult = setupFlowRunner.runSetup(
                flow,
                untilBeforeStepId,
                setupAuthProfile,
                dataProfile,
                env,
                legacy,
                mode);
        if (runResult.failed()) {
            return ScenarioPreparationResult.failed(runResult.reason(), runResult.warnings());
        }

        ScenarioInstance scenario = scenarioFromRun(testCase, flow, untilBeforeStepId, dataProfile, runResult);
        repository.saveScenario(scenario);
        return ScenarioPreparationResult.success(Optional.of(scenario), runResult.reason(), runResult.warnings());
    }

    private ScenarioPreparationResult reuseScenario(AccessTestCase testCase, AccessWorkspace workspace) {
        String scenarioId = testCase.target().scenarioInstanceId();
        if (scenarioId.isBlank()) {
            return ScenarioPreparationResult.failed("Scenario instance id is required for REUSE_SCENARIO", List.of());
        }
        return scenarioById(scenarioId, workspace)
                .map(scenario -> ScenarioPreparationResult.success(Optional.of(scenario), "Reused scenario", List.of()))
                .orElseGet(() -> ScenarioPreparationResult.failed("Scenario not found: " + scenarioId, List.of()));
    }

    private Optional<ScenarioInstance> reusableScenario(AccessTestCase testCase, AccessWorkspace workspace) {
        if (testCase.setup().scenarioPolicy() != ScenarioPolicy.REUSE_EXISTING) {
            return Optional.empty();
        }
        String scenarioId = testCase.target().scenarioInstanceId();
        if (scenarioId.isBlank()) {
            return Optional.empty();
        }
        return scenarioById(scenarioId, workspace)
                .filter(scenario -> !scenario.consumed())
                .filter(scenario -> !scenario.isExpired(clock));
    }

    private Optional<ScenarioInstance> scenarioById(String id, AccessWorkspace workspace) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        ScenarioInstance snapshotScenario = workspace.scenarios().get(id.strip());
        if (snapshotScenario != null) {
            return Optional.of(snapshotScenario);
        }
        return repository.scenario(id.strip());
    }

    private Optional<DataProfile> dataProfile(AccessTestCase testCase, AccessWorkspace workspace) {
        String dataProfileId = testCase.target().dataProfileId();
        if (dataProfileId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(workspace.dataProfiles().get(dataProfileId));
    }

    private Optional<AuthProfile> resolveSetupAuthProfile(
            AccessTestCase testCase,
            AccessWorkspace workspace,
            DataProfile dataProfile) {
        String authProfileId = switch (testCase.setup().setupAuthBinding()) {
            case DATA_OWNER -> dataProfile == null ? "" : dataProfile.ownerAuthProfileId();
            case TARGET_ACTOR -> testCase.target().targetAuthProfileId();
            case FIXED_PROFILE -> testCase.setup().setupAuthProfileId();
        };
        if (authProfileId == null || authProfileId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(workspace.authProfiles().get(authProfileId.strip()));
    }

    private Optional<AuthProfile> ensureSetupAuth(
            AuthProfile setupAuthProfile,
            AccessTestCase testCase,
            Map<String, String> env,
            RenderMode mode) {
        if (authManager == null || isManual(setupAuthProfile)) {
            return Optional.of(setupAuthProfile);
        }
        AuthEnsureResult ensureResult = authManager.ensureAuth(
                setupAuthProfile.id(),
                testCase.target().environmentId(),
                env,
                mode);
        if (ensureResult.failed()) {
            return Optional.empty();
        }
        return ensureResult.updatedProfile().or(() -> Optional.of(setupAuthProfile));
    }

    private Optional<FlowDefinition> setupFlow(String flowId) {
        if (flowId == null || flowId.isBlank()) {
            return Optional.empty();
        }
        String normalizedId = flowId.strip();
        return flowSupplier.get().stream()
                .filter(flow -> flow.id().equals(normalizedId))
                .findFirst();
    }

    private ScenarioInstance scenarioFromRun(
            AccessTestCase testCase,
            FlowDefinition flow,
            String untilBeforeStepId,
            DataProfile dataProfile,
            ScenarioSetupRunResult runResult) {
        long now = clock.millis();
        LinkedHashMap<String, ScenarioVariable> variables = new LinkedHashMap<>();
        runResult.scenarioVariables().forEach((key, value) -> {
            ScenarioVariable variable = new ScenarioVariable(
                    key,
                    value,
                    now,
                    0L,
                    false,
                    false,
                    flow.id(),
                    "",
                    dataProfile == null ? "" : dataProfile.id());
            variables.put(variable.key(), variable);
        });
        return new ScenarioInstance(
                idGenerator.newId("scenario"),
                testCase.name() + " setup",
                dataProfile == null ? "" : dataProfile.id(),
                variables,
                testCase.setup().scenarioPolicy(),
                now,
                false,
                flow.id(),
                untilBeforeStepId);
    }

    private boolean isManual(AuthProfile authProfile) {
        return authProfile.providerId().isBlank() || AuthProfile.MANUAL_PROVIDER_ID.equals(authProfile.providerId());
    }
}
