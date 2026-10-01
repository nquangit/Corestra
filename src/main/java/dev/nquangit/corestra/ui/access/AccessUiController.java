package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.dependency.SetupSuggestionEngine;
import dev.nquangit.corestra.access.dependency.SetupSuggestionResult;
import dev.nquangit.corestra.access.evaluate.EvaluationProfile;
import dev.nquangit.corestra.access.flow.AccessFlowTarget;
import dev.nquangit.corestra.access.flow.AccessTargetType;
import dev.nquangit.corestra.access.flow.FlowMatrixRowsFromFlowRequest;
import dev.nquangit.corestra.access.flow.FlowMatrixRowsFromFlowResult;
import dev.nquangit.corestra.access.flow.FlowInjectionOverride;
import dev.nquangit.corestra.access.flow.FlowStepMatrixSelection;
import dev.nquangit.corestra.access.flow.FlowTargetHasher;
import dev.nquangit.corestra.access.flow.FlowTargetRunMode;
import dev.nquangit.corestra.access.id.AccessIdGenerator;
import dev.nquangit.corestra.access.id.UuidAccessIdGenerator;
import dev.nquangit.corestra.access.importer.AccessTemplateImportService;
import dev.nquangit.corestra.access.importer.ImportOptions;
import dev.nquangit.corestra.access.importer.ImportResult;
import dev.nquangit.corestra.access.importer.ImportSource;
import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.matrix.MatrixGenerationRequest;
import dev.nquangit.corestra.access.matrix.MatrixGenerationResult;
import dev.nquangit.corestra.access.matrix.MatrixGenerator;
import dev.nquangit.corestra.access.matrix.MatrixGenerationStrategy;
import dev.nquangit.corestra.access.matrix.RunStatus;
import dev.nquangit.corestra.access.matrix.SetupConfig;
import dev.nquangit.corestra.access.matrix.SetupMode;
import dev.nquangit.corestra.access.postcheck.PostCheckRequest;
import dev.nquangit.corestra.access.race.RaceRunConfig;
import dev.nquangit.corestra.access.race.RaceRunResult;
import dev.nquangit.corestra.access.race.RaceRunner;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.access.report.FindingDraft;
import dev.nquangit.corestra.access.report.FindingDraftBuilder;
import dev.nquangit.corestra.access.report.SavedFindingDraft;
import dev.nquangit.corestra.access.result.AccessEvidence;
import dev.nquangit.corestra.access.result.AccessTestResult;
import dev.nquangit.corestra.access.scenario.ScenarioInstance;
import dev.nquangit.corestra.access.runner.AccessPlanListener;
import dev.nquangit.corestra.access.runner.AccessPlanPreview;
import dev.nquangit.corestra.access.runner.AccessPlanRunConfig;
import dev.nquangit.corestra.access.runner.AccessPlanRunner;
import dev.nquangit.corestra.access.runner.AccessPlanSummary;
import dev.nquangit.corestra.access.runner.AccessRunResult;
import dev.nquangit.corestra.access.runner.PlanSafetySummary;
import dev.nquangit.corestra.access.runner.PlanValidationIssue;
import dev.nquangit.corestra.access.runner.PlanValidationSeverity;
import dev.nquangit.corestra.access.runner.SingleAccessTestRunner;
import dev.nquangit.corestra.access.template.AccessRequestTemplateAnalyzer;
import dev.nquangit.corestra.access.template.AccessRequestTemplateFactory;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.AccessRequestTemplateRenderer;
import dev.nquangit.corestra.access.template.AccessScopedVariableStoreFactory;
import dev.nquangit.corestra.access.template.OperationMetadata;
import dev.nquangit.corestra.access.template.OperationType;
import dev.nquangit.corestra.access.template.RequestTemplateHasher;
import dev.nquangit.corestra.access.template.RenderedAccessRequest;
import dev.nquangit.corestra.access.teardown.TeardownMode;
import dev.nquangit.corestra.access.validation.AccessProjectValidator;
import dev.nquangit.corestra.access.validation.AccessValidationReport;
import dev.nquangit.corestra.access.variable.DefaultRuntimeValueProvider;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.ScopedVariableStore;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.access.variable.VariableResolver;
import dev.nquangit.corestra.access.variable.VariableScope;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Coordinates the minimal Access Testing UI with the in-memory access repository.
 */
public final class AccessUiController {
    private final InMemoryAccessRepository repository;
    private final AccessIdGenerator idGenerator;
    private final AccessRequestTemplateRenderer templateRenderer;
    private final AccessScopedVariableStoreFactory storeFactory;
    private final SingleAccessTestRunner runner;
    private final AccessPlanRunner planRunner;
    private final MatrixGenerator matrixGenerator;
    private final SetupSuggestionEngine setupSuggestionEngine;
    private final FindingDraftBuilder findingDraftBuilder;
    private final AccessProjectValidator validator;
    private final RaceRunner raceRunner;
    private final AccessTemplateImportService importService;
    private final Supplier<List<FlowDefinition>> flowSupplier;
    private final FlowTargetHasher flowTargetHasher = new FlowTargetHasher();

    /**
     * Creates an access UI controller with default rendering services.
     *
     * @param repository in-memory access repository
     */
    public AccessUiController(InMemoryAccessRepository repository) {
        this(
                repository,
                new UuidAccessIdGenerator(),
                new AccessRequestTemplateRenderer(new TemplateRenderer(
                        new VariableResolver(new DefaultRuntimeValueProvider()))),
                new AccessScopedVariableStoreFactory(),
                null,
                null);
    }

    /**
     * Creates an access UI controller.
     *
     * @param repository in-memory access repository
     * @param idGenerator identifier generator
     * @param templateRenderer access template renderer
     * @param storeFactory scoped variable store factory
     */
    public AccessUiController(
            InMemoryAccessRepository repository,
            AccessIdGenerator idGenerator,
            AccessRequestTemplateRenderer templateRenderer,
            AccessScopedVariableStoreFactory storeFactory) {
        this(repository, idGenerator, templateRenderer, storeFactory, null, null);
    }

    /**
     * Creates an access UI controller.
     *
     * @param repository in-memory access repository
     * @param idGenerator identifier generator
     * @param templateRenderer access template renderer
     * @param storeFactory scoped variable store factory
     * @param runner manual single test runner
     */
    public AccessUiController(
            InMemoryAccessRepository repository,
            AccessIdGenerator idGenerator,
            AccessRequestTemplateRenderer templateRenderer,
            AccessScopedVariableStoreFactory storeFactory,
            SingleAccessTestRunner runner) {
        this(repository, idGenerator, templateRenderer, storeFactory, runner, null);
    }

    /**
     * Creates an access UI controller.
     *
     * @param repository in-memory access repository
     * @param idGenerator identifier generator
     * @param templateRenderer access template renderer
     * @param storeFactory scoped variable store factory
     * @param runner manual single test runner
     * @param planRunner sequential plan runner
     */
    public AccessUiController(
            InMemoryAccessRepository repository,
            AccessIdGenerator idGenerator,
            AccessRequestTemplateRenderer templateRenderer,
            AccessScopedVariableStoreFactory storeFactory,
            SingleAccessTestRunner runner,
            AccessPlanRunner planRunner) {
        this(repository, idGenerator, templateRenderer, storeFactory, runner, planRunner, () -> List.of(), null);
    }

    /**
     * Creates an access UI controller.
     *
     * @param repository in-memory access repository
     * @param idGenerator identifier generator
     * @param templateRenderer access template renderer
     * @param storeFactory scoped variable store factory
     * @param runner manual single test runner
     * @param planRunner sequential plan runner
     * @param flowSupplier supplier for persisted flow definitions
     */
    public AccessUiController(
            InMemoryAccessRepository repository,
            AccessIdGenerator idGenerator,
            AccessRequestTemplateRenderer templateRenderer,
            AccessScopedVariableStoreFactory storeFactory,
            SingleAccessTestRunner runner,
            AccessPlanRunner planRunner,
            Supplier<List<FlowDefinition>> flowSupplier) {
        this(repository, idGenerator, templateRenderer, storeFactory, runner, planRunner, flowSupplier, null);
    }

    /**
     * Creates an access UI controller.
     *
     * @param repository in-memory access repository
     * @param idGenerator identifier generator
     * @param templateRenderer access template renderer
     * @param storeFactory scoped variable store factory
     * @param runner manual single test runner
     * @param planRunner sequential plan runner
     * @param flowSupplier supplier for persisted flow definitions
     * @param raceRunner explicit race runner
     */
    public AccessUiController(
            InMemoryAccessRepository repository,
            AccessIdGenerator idGenerator,
            AccessRequestTemplateRenderer templateRenderer,
            AccessScopedVariableStoreFactory storeFactory,
            SingleAccessTestRunner runner,
            AccessPlanRunner planRunner,
            Supplier<List<FlowDefinition>> flowSupplier,
            RaceRunner raceRunner) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
        this.storeFactory = Objects.requireNonNull(storeFactory, "storeFactory");
        this.runner = runner;
        this.planRunner = planRunner;
        this.matrixGenerator = new MatrixGenerator(idGenerator);
        this.setupSuggestionEngine = new SetupSuggestionEngine();
        this.findingDraftBuilder = new FindingDraftBuilder();
        this.validator = new AccessProjectValidator();
        this.raceRunner = raceRunner;
        this.importService = new AccessTemplateImportService(idGenerator);
        this.flowSupplier = Objects.requireNonNull(flowSupplier, "flowSupplier");
    }

    /**
     * Returns auth providers in UI order.
     *
     * @return auth providers
     */
    public List<AuthProvider> authProviders() {
        return repository.authProviders();
    }

    List<AuthProviderOption> authProviderOptions() {
        java.util.ArrayList<AuthProviderOption> options = new java.util.ArrayList<>();
        options.add(AuthProviderOption.manual());
        repository.authProviders().forEach(provider -> options.add(AuthProviderOption.from(provider)));
        return List.copyOf(options);
    }

    /**
     * Returns auth profiles in UI order.
     *
     * @return auth profiles
     */
    public List<AuthProfile> authProfiles() {
        return repository.authProfiles();
    }

    /**
     * Returns data profiles in UI order.
     *
     * @return data profiles
     */
    public List<DataProfile> dataProfiles() {
        return repository.dataProfiles();
    }

    /**
     * Returns access templates in UI order.
     *
     * @return access templates
     */
    public List<AccessRequestTemplate> templates() {
        return repository.templates();
    }

    /**
     * Returns access flow targets in UI order.
     *
     * @return access flow targets
     */
    public List<AccessFlowTarget> flowTargets() {
        return repository.flowTargets();
    }

    /**
     * Returns matrix test cases in UI order.
     *
     * @return access test cases
     */
    public List<AccessTestCase> testCases() {
        return repository.testCases();
    }

    /**
     * Returns access test results in UI order.
     *
     * @return access test results
     */
    public List<AccessTestResult> results() {
        return repository.results();
    }

    /**
     * Returns the latest saved plan summary.
     *
     * @return latest plan summary, or empty
     */
    public Optional<AccessPlanSummary> latestPlanSummary() {
        return repository.latestSummary();
    }

    /**
     * Returns scenario instances in UI order.
     *
     * @return scenario instances
     */
    public List<ScenarioInstance> scenarios() {
        return repository.scenarios();
    }

    /**
     * Returns evaluation profiles in UI order.
     *
     * @return evaluation profiles
     */
    public List<EvaluationProfile> evaluationProfiles() {
        return repository.evaluationProfiles();
    }

    /**
     * Validates the current access workspace snapshot without mutating it.
     *
     * @return validation report
     */
    public AccessValidationReport validateAccessWorkspace() {
        return validator.validate(repository.snapshot());
    }

    /**
     * Returns flow definitions available for setup suggestions.
     *
     * @return flow definitions
     */
    public List<FlowDefinition> flows() {
        return List.copyOf(flowSupplier.get());
    }

    /**
     * Finds evidence by result id.
     *
     * @param resultId result id
     * @return matching evidence
     */
    public Optional<AccessEvidence> evidenceByResultId(String resultId) {
        if (resultId == null || resultId.isBlank()) {
            return Optional.empty();
        }
        return repository.evidenceByResultId(resultId);
    }

    /**
     * Builds a finding draft for one access test result using redacted evidence.
     *
     * @param resultId result id
     * @return generated finding draft
     */
    public FindingDraft createFindingDraft(String resultId) {
        AccessTestResult result = repository.result(resultId)
                .orElseThrow(() -> new IllegalArgumentException("Result not found"));
        return findingDraftBuilder.build(result, repository.evidenceByResultId(result.id()), repository.snapshot());
    }

    /**
     * Finds a saved Markdown finding draft for a result.
     *
     * @param resultId result id
     * @return saved finding draft, or empty
     */
    public Optional<SavedFindingDraft> savedFindingDraft(String resultId) {
        if (resultId == null || resultId.isBlank()) {
            return Optional.empty();
        }
        return repository.result(resultId)
                .flatMap(result -> repository.reportDraft(result.testCaseId()))
                .filter(draft -> draft.resultId().equals(resultId));
    }

    /**
     * Saves edited Markdown for a finding draft.
     *
     * @param resultId result id
     * @param markdown edited Markdown
     * @return saved finding draft
     */
    public SavedFindingDraft saveFindingDraft(String resultId, String markdown) {
        AccessTestResult result = repository.result(resultId)
                .orElseThrow(() -> new IllegalArgumentException("Result not found"));
        SavedFindingDraft draft = new SavedFindingDraft(
                result.testCaseId(),
                result.id(),
                markdown,
                System.currentTimeMillis());
        repository.saveReportDraft(draft);
        return draft;
    }

    /**
     * Saves an auth provider from a form model.
     *
     * @param existingId existing provider id, or empty to create a new id
     * @param form form model
     * @return saved auth provider
     */
    public AuthProvider saveAuthProvider(String existingId, AuthProviderFormModel form) {
        ensureEditable();
        Objects.requireNonNull(form, "form");
        String id = normalizeExistingOrNew(existingId, "provider");
        AuthProvider provider = form.toAuthProvider(id);
        repository.saveAuthProvider(provider);
        return provider;
    }

    /**
     * Deletes an auth provider.
     *
     * @param id provider id
     * @return true when deleted
     */
    public boolean deleteAuthProvider(String id) {
        ensureEditable();
        return repository.deleteAuthProvider(id);
    }

    /**
     * Saves an auth profile from a form model.
     *
     * @param existingId existing profile id, or empty to create a new id
     * @param form form model
     * @return saved auth profile
     */
    public AuthProfile saveAuthProfile(String existingId, AuthProfileFormModel form) {
        ensureEditable();
        Objects.requireNonNull(form, "form");
        String id = normalizeExistingOrNew(existingId, "auth");
        AuthProfile profile = form.toAuthProfile(id);
        repository.saveAuthProfile(profile);
        return profile;
    }

    /**
     * Adds or replaces one credential on an existing profile, or creates a new profile.
     * This is the narrow integration used by the Flowise injection editor.
     */
    public AuthProfile saveCredentialValue(
            String existingId,
            String newProfileName,
            String role,
            String providerId,
            String credentialKey,
            String value) {
        ensureEditable();
        String key = normalizeScopedKey(credentialKey, "credentials.", "credential");
        AuthProfile current = existingId == null || existingId.isBlank()
                ? null
                : repository.authProfile(existingId.strip()).orElseThrow(
                        () -> new IllegalArgumentException("Auth profile not found: " + existingId));
        LinkedHashMap<String, String> credentials = new LinkedHashMap<>(
                current == null ? Map.of() : current.credentials());
        credentials.put(key, Objects.requireNonNullElse(value, ""));
        AuthProfile saved = current == null
                ? new AuthProfile(
                        normalizeExistingOrNew("", "auth"),
                        normalizeRequiredText(newProfileName, "profile name"),
                        role,
                        normalizeProviderForSave(providerId),
                        credentials,
                        AuthState.empty(),
                        true)
                : new AuthProfile(
                        current.id(), current.name(), current.role(), current.providerId(), credentials,
                        current.state(), current.enabled());
        repository.saveAuthProfile(saved);
        return saved;
    }

    /** Clears cached tokens, headers, cookies, and expiry while preserving actor credentials. */
    public AuthProfile clearAuthProfileState(String id) {
        ensureEditable();
        String normalizedId = id == null ? "" : id.strip();
        if (normalizedId.isEmpty()) {
            throw new IllegalArgumentException("authProfileId must not be blank");
        }
        AuthProfile current = repository.authProfile(normalizedId)
                .orElseThrow(() -> new IllegalArgumentException("Auth profile not found: " + id));
        AuthProfile cleared = new AuthProfile(
                current.id(), current.name(), current.role(), current.providerId(), current.credentials(),
                dev.nquangit.corestra.access.auth.AuthState.empty(), current.enabled());
        repository.saveAuthProfile(cleared);
        return cleared;
    }

    /**
     * Deletes an auth profile.
     *
     * @param id profile id
     * @return true when deleted
     */
    public boolean deleteAuthProfile(String id) {
        ensureEditable();
        return repository.deleteAuthProfile(id);
    }

    /**
     * Saves a data profile from a form model.
     *
     * @param existingId existing profile id, or empty to create a new id
     * @param form form model
     * @return saved data profile
     */
    public DataProfile saveDataProfile(String existingId, DataProfileFormModel form) {
        ensureEditable();
        Objects.requireNonNull(form, "form");
        String id = normalizeExistingOrNew(existingId, "data");
        DataProfile profile = form.toDataProfile(id);
        repository.saveDataProfile(profile);
        return profile;
    }

    /** Adds or replaces one value on an existing data profile, or creates a new profile. */
    public DataProfile saveDataValue(
            String existingId,
            String newProfileName,
            String ownerAuthProfileId,
            String dataKey,
            String value) {
        ensureEditable();
        String key = normalizeScopedKey(dataKey, "data.", "data");
        DataProfile current = existingId == null || existingId.isBlank()
                ? null
                : repository.dataProfile(existingId.strip()).orElseThrow(
                        () -> new IllegalArgumentException("Data profile not found: " + existingId));
        LinkedHashMap<String, String> values = new LinkedHashMap<>(
                current == null ? Map.of() : current.values());
        values.put(key, Objects.requireNonNullElse(value, ""));
        DataProfile saved = current == null
                ? new DataProfile(
                        normalizeExistingOrNew("", "data"),
                        normalizeRequiredText(newProfileName, "profile name"),
                        normalizeOwnerProfileId(ownerAuthProfileId),
                        values,
                        true)
                : new DataProfile(
                        current.id(), current.name(), current.ownerAuthProfileId(), values, current.enabled());
        repository.saveDataProfile(saved);
        return saved;
    }

    /**
     * Deletes a data profile.
     *
     * @param id profile id
     * @return true when deleted
     */
    public boolean deleteDataProfile(String id) {
        ensureEditable();
        return repository.deleteDataProfile(id);
    }

    /**
     * Saves an evaluation profile from a form model.
     *
     * @param existingId existing profile id, or empty to create a new id
     * @param form form model
     * @return saved evaluation profile
     */
    public EvaluationProfile saveEvaluationProfile(String existingId, EvaluationProfileFormModel form) {
        ensureEditable();
        Objects.requireNonNull(form, "form");
        String id = normalizeExistingOrNew(existingId, "eval");
        EvaluationProfile profile = form.toEvaluationProfile(id);
        repository.saveEvaluationProfile(profile);
        return profile;
    }

    /**
     * Deletes an evaluation profile.
     *
     * @param id profile id
     * @return true when deleted
     */
    public boolean deleteEvaluationProfile(String id) {
        ensureEditable();
        return repository.deleteEvaluationProfile(id);
    }

    /**
     * Saves a manually entered access request template.
     *
     * @param name display name
     * @param request stored request template
     * @param metadata operation metadata
     * @return saved access request template
     */
    public AccessRequestTemplate saveManualTemplate(String name, StoredRequestTemplate request, OperationMetadata metadata) {
        ensureEditable();
        Objects.requireNonNull(request, "request");
        AccessRequestTemplate template = templateFactory().fromStoredRequest(name, request, metadata);
        repository.saveTemplate(template);
        return template;
    }

    /**
     * Saves a disabled Access Template from a captured request without modifying any Flowise flow.
     *
     * @param name display name
     * @param request captured request
     * @param metadata operation metadata
     * @return saved disabled access request template
     */
    public AccessRequestTemplate saveCapturedRequestTemplate(
            String name,
            StoredRequestTemplate request,
            OperationMetadata metadata) {
        ensureEditable();
        Objects.requireNonNull(request, "request");
        AccessRequestTemplate template = disabled(templateFactory().fromStoredRequest(name, request, metadata));
        repository.saveTemplate(template);
        return template;
    }

    /**
     * Imports templates for preview without saving them.
     *
     * @param source import source
     * @param options import options
     * @return imported template preview and diagnostics
     */
    public ImportResult previewImportedTemplates(ImportSource source, ImportOptions options) {
        return importService.importTemplates(source, options);
    }

    /**
     * Saves confirmed imported templates.
     *
     * @param templates templates selected in the import preview
     * @return saved templates and diagnostics
     */
    public ImportResult saveImportedTemplates(List<AccessRequestTemplate> templates) {
        ensureEditable();
        if (templates == null || templates.isEmpty()) {
            return new ImportResult(List.of(), List.of(), List.of(), 0);
        }
        java.util.ArrayList<AccessRequestTemplate> saved = new java.util.ArrayList<>();
        for (AccessRequestTemplate template : templates) {
            repository.saveTemplate(Objects.requireNonNull(template, "template"));
            saved.add(template);
        }
        return new ImportResult(saved, List.of(), List.of(), 0);
    }

    /**
     * Creates a disabled access template from an existing flow step.
     *
     * @param flow source flow
     * @param step source step
     * @param metadata operation metadata, or null for unknown metadata
     * @return saved disabled access request template
     */
    public AccessRequestTemplate createTemplateFromFlowStep(
            FlowDefinition flow,
            FlowStep step,
            OperationMetadata metadata) {
        ensureEditable();
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(step, "step");
        AccessRequestTemplate template = disabled(templateFactory().fromFlowStep(flow, step, metadata));
        repository.saveTemplate(template);
        return template;
    }

    /**
     * Adds a disabled matrix target row for an existing flow step.
     *
     * <p>If the flow step was not previously converted to an access template, this method creates
     * a disabled template first. No request is sent.</p>
     *
     * @param flow source flow
     * @param step source step
     * @param targetAuthProfileId target auth profile id
     * @param dataProfileId data profile id, or empty
     * @param expected expected result
     * @return saved disabled access test case
     */
    public AccessTestCase addFlowStepAsMatrixTarget(
            FlowDefinition flow,
            FlowStep step,
            String targetAuthProfileId,
            String dataProfileId,
            ExpectedResult expected) {
        ensureEditable();
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(step, "step");
        AccessRequestTemplate template = templateForFlowStep(flow.id(), step.id())
                .orElseGet(() -> createTemplateFromFlowStep(flow, step, OperationMetadata.unknown()));
        return saveTestCase("", new MatrixRowFormModel(
                "Target: " + step.name(),
                template.id(),
                targetAuthProfileId,
                dataProfileId,
                "",
                Objects.requireNonNullElse(expected, ExpectedResult.REVIEW),
                false));
    }

    /**
     * Creates a disabled Access flow target from a Flowise flow snapshot.
     *
     * @param flow source flow
     * @param targetStepId selected target step id, or blank to use the last enabled step
     * @param overrides injection overrides to apply during execution
     * @return saved disabled flow target
     */
    public AccessFlowTarget createFlowTargetFromFlow(
            FlowDefinition flow,
            String targetStepId,
            List<FlowInjectionOverride> overrides) {
        ensureEditable();
        Objects.requireNonNull(flow, "flow");
        String resolvedStepId = resolveFlowTargetStepId(flow, targetStepId);
        AccessFlowTarget target = newFlowTarget(flow, resolvedStepId, FlowTargetRunMode.STOP_AT_STEP, overrides);
        repository.saveFlowTarget(target);
        return target;
    }

    private AccessFlowTarget newFlowTarget(
            FlowDefinition flow,
            String resolvedStepId,
            FlowTargetRunMode runMode,
            List<FlowInjectionOverride> overrides) {
        return new AccessFlowTarget(
                idGenerator.newId("flowtarget"),
                "Flow Target: " + flow.name() + " / " + stepName(flow, resolvedStepId),
                flow,
                flow.id(),
                flow.name(),
                flowTargetHasher.hash(flow),
                resolvedStepId,
                Objects.requireNonNullElse(runMode, FlowTargetRunMode.STOP_AT_STEP),
                false,
                overrides == null ? List.of() : overrides);
    }

    /**
     * Creates disabled Access Templates from every enabled step in a flow.
     *
     * @param flow source flow
     * @return saved templates
     */
    public List<AccessRequestTemplate> createTemplatesFromFlowSteps(FlowDefinition flow) {
        ensureEditable();
        Objects.requireNonNull(flow, "flow");
        java.util.ArrayList<AccessRequestTemplate> saved = new java.util.ArrayList<>();
        for (FlowStep step : flow.steps()) {
            if (!step.enabled()) {
                continue;
            }
            AccessRequestTemplate template = templateForFlowStep(flow.id(), step.id())
                    .orElseGet(() -> createTemplateFromFlowStep(flow, step, OperationMetadata.unknown()));
            saved.add(template);
        }
        return List.copyOf(saved);
    }

    /**
     * Adds a disabled matrix row for a saved Access flow target.
     *
     * @param flowTarget flow target
     * @param targetAuthProfileId target auth profile id
     * @param dataProfileId data profile id, or empty
     * @param expected expected result
     * @return saved disabled access test case
     */
    public AccessTestCase addFlowTargetAsMatrixTarget(
            AccessFlowTarget flowTarget,
            String targetAuthProfileId,
            String dataProfileId,
            ExpectedResult expected) {
        ensureEditable();
        Objects.requireNonNull(flowTarget, "flowTarget");
        if (repository.flowTarget(flowTarget.id()).isEmpty()) {
            repository.saveFlowTarget(flowTarget);
        }
        return saveTestCase("", new MatrixRowFormModel(
                AccessTargetType.FLOW_TARGET,
                flowTarget.id(),
                "Flow Target: " + flowTarget.name(),
                "",
                targetAuthProfileId,
                dataProfileId,
                "",
                SetupMode.NONE,
                "",
                "",
                dev.nquangit.corestra.access.matrix.SetupAuthBinding.DATA_OWNER,
                "",
                dev.nquangit.corestra.access.matrix.ScenarioPolicy.REUSE_EXISTING,
                "",
                Objects.requireNonNullElse(expected, ExpectedResult.REVIEW),
                "",
                List.of(),
                dev.nquangit.corestra.access.teardown.TeardownConfig.none(),
                false));
    }

    /**
     * Creates disabled Matrix rows from selected enabled steps in one Flowise flow.
     *
     * @param request selected flow-step generation request
     * @return saved rows, duplicate count, and warnings
     */
    public FlowMatrixRowsFromFlowResult createMatrixRowsFromFlow(FlowMatrixRowsFromFlowRequest request) {
        ensureEditable();
        Objects.requireNonNull(request, "request");
        FlowDefinition flow = flows().stream()
                .filter(candidate -> candidate.id().equals(request.flowId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Flow is required"));
        if (repository.authProfile(request.targetAuthProfileId()).isEmpty()) {
            throw new IllegalArgumentException("Target auth profile is required");
        }
        if (!request.dataProfileId().isBlank() && repository.dataProfile(request.dataProfileId()).isEmpty()) {
            throw new IllegalArgumentException("Data profile was not found");
        }

        java.util.ArrayList<AccessTestCase> savedRows = new java.util.ArrayList<>();
        java.util.ArrayList<String> warnings = new java.util.ArrayList<>();
        int duplicates = 0;
        String flowHash = flowTargetHasher.hash(flow);

        for (FlowStepMatrixSelection selection : request.selections()) {
            if (!selection.selected()) {
                continue;
            }
            FlowStep step = flow.steps().stream()
                    .filter(candidate -> candidate.id().equals(selection.stepId()))
                    .findFirst()
                    .orElse(null);
            if (step == null) {
                warnings.add("Selected step was not found: " + selection.stepId());
                continue;
            }
            if (!step.enabled()) {
                warnings.add("Selected step is disabled and was skipped: " + step.name());
                continue;
            }

            AccessFlowTarget flowTarget = equivalentFlowTarget(flow.id(), flowHash, step.id(), selection.runMode())
                    .orElseGet(() -> {
                        AccessFlowTarget created = newFlowTarget(flow, step.id(), selection.runMode(), List.of());
                        repository.saveFlowTarget(created);
                        return created;
                    });
            AccessTestCase row = flowStepMatrixRow(flow, step, flowTarget, request, selection);
            if (matrixGenerator.isDuplicate(row, repository.testCases())) {
                duplicates++;
                continue;
            }
            repository.saveTestCase(row);
            savedRows.add(row);
        }

        if (savedRows.isEmpty() && duplicates == 0 && warnings.isEmpty()) {
            warnings.add("No flow steps were selected.");
        }
        return new FlowMatrixRowsFromFlowResult(savedRows, duplicates, warnings);
    }

    private AccessTestCase flowStepMatrixRow(
            FlowDefinition flow,
            FlowStep step,
            AccessFlowTarget flowTarget,
            FlowMatrixRowsFromFlowRequest request,
            FlowStepMatrixSelection selection) {
        MatrixRowFormModel form = new MatrixRowFormModel(
                AccessTargetType.FLOW_TARGET,
                flowTarget.id(),
                flow.name() + " / " + step.name(),
                "",
                request.targetAuthProfileId(),
                request.dataProfileId(),
                "",
                SetupMode.NONE,
                "",
                "",
                dev.nquangit.corestra.access.matrix.SetupAuthBinding.DATA_OWNER,
                "",
                dev.nquangit.corestra.access.matrix.ScenarioPolicy.REUSE_EXISTING,
                "",
                selection.expected(),
                "",
                List.of(),
                dev.nquangit.corestra.access.teardown.TeardownConfig.none(),
                !request.createDisabledRows());
        return form.toTestCase(idGenerator.newId("case"), flow.id(), step.id(), RunStatus.PENDING, 0L);
    }

    private Optional<AccessFlowTarget> equivalentFlowTarget(
            String sourceFlowId,
            String sourceFlowHash,
            String targetStepId,
            FlowTargetRunMode runMode) {
        return repository.flowTargets().stream()
                .filter(target -> target.sourceFlowId().equals(sourceFlowId))
                .filter(target -> target.sourceFlowHash().equals(sourceFlowHash))
                .filter(target -> target.targetStepId().equals(targetStepId))
                .filter(target -> target.runMode() == Objects.requireNonNullElse(runMode, FlowTargetRunMode.STOP_AT_STEP))
                .filter(target -> target.injectionOverrides().isEmpty())
                .findFirst();
    }

    /**
     * Deletes an access request template.
     *
     * @param id template id
     * @return true when deleted
     */
    public boolean deleteTemplate(String id) {
        ensureEditable();
        return repository.deleteTemplate(id);
    }

    /**
     * Saves an Access flow target.
     *
     * @param flowTarget flow target to save
     * @return saved flow target
     */
    public AccessFlowTarget saveFlowTarget(AccessFlowTarget flowTarget) {
        ensureEditable();
        repository.saveFlowTarget(Objects.requireNonNull(flowTarget, "flowTarget"));
        return flowTarget;
    }

    /**
     * Deletes an Access flow target.
     *
     * @param id flow target id
     * @return true when deleted
     */
    public boolean deleteFlowTarget(String id) {
        ensureEditable();
        return repository.deleteFlowTarget(id);
    }

    /**
     * Saves a matrix row from a form model.
     *
     * @param existingId existing test case id, or empty to create a new id
     * @param form form model
     * @return saved access test case
     */
    public AccessTestCase saveTestCase(String existingId, MatrixRowFormModel form) {
        ensureEditable();
        Objects.requireNonNull(form, "form");
        if (form.targetAuthProfileId().isBlank()) {
            throw new IllegalArgumentException("Target auth profile is required");
        }
        AccessRequestTemplate template = null;
        AccessFlowTarget flowTarget = null;
        if (form.targetType() == AccessTargetType.FLOW_TARGET) {
            if (form.flowTargetId().isBlank()) {
                throw new IllegalArgumentException("Flow target is required");
            }
            flowTarget = repository.flowTarget(form.flowTargetId())
                    .orElseThrow(() -> new IllegalArgumentException("Flow target is required"));
        } else {
            if (form.templateId().isBlank()) {
                throw new IllegalArgumentException("Template is required");
            }
            template = repository.template(form.templateId())
                    .orElseThrow(() -> new IllegalArgumentException("Template is required"));
            if (templateUsesData(template) && form.dataProfileId().isBlank()) {
                throw new IllegalArgumentException("Data profile is required for templates using data.* placeholders");
            }
        }
        if (repository.authProfile(form.targetAuthProfileId()).isEmpty()) {
            throw new IllegalArgumentException("Target auth profile is required");
        }
        if (!form.dataProfileId().isBlank() && repository.dataProfile(form.dataProfileId()).isEmpty()) {
            throw new IllegalArgumentException("Data profile was not found");
        }

        String id = normalizeExistingOrNew(existingId, "case");
        RunStatus status = repository.testCase(id).map(AccessTestCase::status).orElse(RunStatus.PENDING);
        long lastRunAt = repository.testCase(id).map(AccessTestCase::lastRunAtEpochMillis).orElse(0L);
        String flowId = flowTarget == null ? template.sourceFlowId() : flowTarget.sourceFlowId();
        String targetStepId = flowTarget == null ? template.sourceStepId() : flowTarget.targetStepId();
        AccessTestCase testCase = form.toTestCase(id, flowId, targetStepId, status, lastRunAt);
        repository.saveTestCase(testCase);
        return testCase;
    }

    /**
     * Deletes a matrix row.
     *
     * @param id test case id
     * @return true when deleted
     */
    public boolean deleteTestCase(String id) {
        ensureEditable();
        return repository.deleteTestCase(id);
    }

    /**
     * Deletes a scenario instance.
     *
     * @param id scenario id
     * @return true when deleted
     */
    public boolean deleteScenario(String id) {
        ensureEditable();
        return repository.deleteScenario(id);
    }

    /**
     * Duplicates an existing matrix row.
     *
     * @param id source test case id
     * @return duplicated test case
     */
    public AccessTestCase duplicateTestCase(String id) {
        ensureEditable();
        AccessTestCase original = repository.testCase(id)
                .orElseThrow(() -> new IllegalArgumentException("Test case not found"));
        MatrixRowFormModel form = MatrixRowFormModel.from(original);
        AccessTestCase duplicated = form.toTestCase(
                idGenerator.newId("case"),
                original.flowId(),
                original.targetStepId(),
                RunStatus.PENDING,
                0L);
        repository.saveTestCase(duplicated);
        return duplicated;
    }

    /**
     * Generates suggested matrix rows without saving or running them.
     *
     * @param request generation request
     * @return generated matrix rows and diagnostics
     */
    public MatrixGenerationResult generateMatrix(MatrixGenerationRequest request) {
        return matrixGenerator.generate(request, repository.snapshot());
    }

    /**
     * Saves confirmed generated matrix rows, skipping rows that have become duplicates.
     *
     * @param rows generated rows to save
     * @return saved rows and duplicate diagnostics
     */
    public MatrixGenerationResult saveGeneratedMatrixRows(List<AccessTestCase> rows) {
        ensureEditable();
        if (rows == null || rows.isEmpty()) {
            return new MatrixGenerationResult(List.of(), 0, List.of());
        }
        java.util.ArrayList<AccessTestCase> saved = new java.util.ArrayList<>();
        int duplicates = 0;
        for (AccessTestCase row : rows) {
            if (matrixGenerator.isDuplicate(row, repository.testCases())) {
                duplicates++;
                continue;
            }
            repository.saveTestCase(row);
            saved.add(row);
        }
        return new MatrixGenerationResult(saved, duplicates, List.of());
    }

    /**
     * Generates and saves disabled owner-vs-others matrix rows for one selected template.
     *
     * @param templateId selected template id
     * @param adminExpectedResult expected admin row outcome
     * @return saved rows and duplicate diagnostics
     */
    public MatrixGenerationResult createOwnerVsOthersRowsForTemplate(
            String templateId,
            ExpectedResult adminExpectedResult) {
        ensureEditable();
        if (templateId == null || templateId.isBlank() || repository.template(templateId).isEmpty()) {
            throw new IllegalArgumentException("Template is required");
        }
        MatrixGenerationResult generated = generateMatrix(new MatrixGenerationRequest(
                MatrixGenerationStrategy.OWNER_VS_OTHERS,
                Set.of(templateId.strip()),
                Set.of(),
                Objects.requireNonNullElse(adminExpectedResult, ExpectedResult.REVIEW),
                ExpectedResult.REVIEW,
                false));
        MatrixGenerationResult saved = saveGeneratedMatrixRows(generated.rows());
        return new MatrixGenerationResult(saved.rows(), saved.duplicateCount() + generated.duplicateCount(),
                generated.warnings());
    }

    /**
     * Suggests setup configuration for a target step in a flow.
     *
     * @param flowId flow identifier
     * @param targetStepId target step identifier
     * @param mode render mode
     * @return setup suggestion result
     */
    public SetupSuggestionResult suggestSetupFromFlow(
            String flowId,
            String targetStepId,
            RenderMode mode) {
        Optional<FlowDefinition> flow = flows().stream()
                .filter(candidate -> candidate.id().equals(flowId == null ? "" : flowId.strip()))
                .findFirst();
        if (flow.isEmpty()) {
            return new SetupSuggestionResult(
                    false,
                    SetupConfig.none(),
                    java.util.Set.of(),
                    Map.of(),
                    List.of("Flow not found: " + Objects.requireNonNullElse(flowId, "")),
                    "Flow not found");
        }
        return setupSuggestionEngine.suggestForTargetStep(
                flow.get(),
                targetStepId,
                Objects.requireNonNullElse(mode, RenderMode.STRICT),
                repository.templates());
    }

    /**
     * Applies a confirmed setup suggestion to an existing matrix row.
     *
     * @param testCaseId test case id
     * @param suggestion confirmed setup suggestion
     * @return updated test case
     */
    public AccessTestCase applySetupSuggestion(String testCaseId, SetupSuggestionResult suggestion) {
        Objects.requireNonNull(suggestion, "suggestion");
        return applySetupConfig(testCaseId, suggestion.suggestedSetup());
    }

    /**
     * Applies a confirmed setup configuration to an existing matrix row.
     *
     * @param testCaseId test case id
     * @param setup setup configuration
     * @return updated test case
     */
    public AccessTestCase applySetupConfig(String testCaseId, SetupConfig setup) {
        ensureEditable();
        AccessTestCase original = repository.testCase(testCaseId)
                .orElseThrow(() -> new IllegalArgumentException("Test case not found"));
        AccessTestCase updated = new AccessTestCase(
                original.id(),
                original.name(),
                original.targetType(),
                original.templateId(),
                original.flowTargetId(),
                original.flowId(),
                original.targetStepId(),
                setup == null ? SetupConfig.none() : setup,
                original.target(),
                original.expected(),
                original.evaluationProfileId(),
                original.assertions(),
                original.postChecks(),
                original.teardown(),
                original.enabled(),
                original.status(),
                original.lastRunAtEpochMillis());
        repository.saveTestCase(updated);
        return updated;
    }

    /**
     * Runs one selected access test case.
     *
     * @param testCaseId test case id
     * @param env environment variables
     * @param scenario scenario variables
     * @param legacy legacy variables
     * @param mode render mode
     * @return saved access test result
     */
    public AccessTestResult runTestCase(
            String testCaseId,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode mode) {
        return runTestCase(testCaseId, env, scenario, legacy, mode, false);
    }

    /**
     * Runs one selected access test case or renders it without sending HTTP.
     *
     * @param testCaseId test case id
     * @param env environment variables
     * @param scenario scenario variables
     * @param legacy legacy variables
     * @param mode render mode
     * @param dryRun true to render and validate without sending HTTP
     * @return saved access test result
     */
    public AccessTestResult runTestCase(
            String testCaseId,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode mode,
            boolean dryRun) {
        if (runner == null) {
            throw new IllegalStateException("Manual access test runner is not configured");
        }
        AccessTestCase original = repository.testCase(testCaseId)
                .orElseThrow(() -> new IllegalArgumentException("Test case not found"));
        repository.saveTestCase(copyWithStatus(original, RunStatus.RUNNING, original.lastRunAtEpochMillis()));
        AccessWorkspace snapshot = repository.snapshot();
        Map<String, String> safeEnv = Objects.requireNonNullElse(env, Map.of());
        Map<String, String> safeScenario = Objects.requireNonNullElse(scenario, Map.of());
        Map<String, String> safeLegacy = Objects.requireNonNullElse(legacy, Map.of());
        RenderMode safeMode = Objects.requireNonNullElse(mode, RenderMode.STRICT);
        AccessRunResult runResult = dryRun
                ? runner.dryRun(original, snapshot, safeEnv, safeScenario, safeLegacy, safeMode)
                : runner.run(original, snapshot, safeEnv, safeScenario, safeLegacy, safeMode);
        repository.saveResult(runResult.result());
        runResult.evidence().ifPresent(repository::saveEvidence);
        repository.saveTestCase(copyWithStatus(original, runResult.result().runStatus(), runResult.result().finishedAtEpochMillis()));
        return runResult.result();
    }

    /**
     * Runs one explicitly selected race configuration.
     *
     * @param config race configuration
     * @return race run result
     */
    public RaceRunResult runRace(RaceRunConfig config) {
        if (raceRunner == null) {
            throw new IllegalStateException("Race runner is not configured");
        }
        return raceRunner.run(config, Map.of(), Map.of(), RenderMode.STRICT);
    }

    /**
     * Runs the enabled matrix rows sequentially.
     *
     * @param config plan run config
     * @param listener plan listener
     * @return plan summary
     */
    public AccessPlanSummary runPlan(AccessPlanRunConfig config, AccessPlanListener listener) {
        if (planRunner == null) {
            throw new IllegalStateException("Access plan runner is not configured");
        }
        AccessPlanSummary summary = planRunner.run(config, listener);
        repository.saveLatestSummary(summary);
        return summary;
    }

    /**
     * Builds a pre-run safety summary and diagnostics from a stable workspace snapshot.
     *
     * @param config plan run config
     * @return immutable plan preview
     */
    public AccessPlanPreview previewPlan(AccessPlanRunConfig config) {
        AccessPlanRunConfig runConfig = config == null ? AccessPlanRunConfig.defaults() : config;
        AccessWorkspace snapshot = repository.snapshot();
        List<AccessTestCase> rows = snapshot.testCases().values().stream()
                .filter(row -> runConfig.includeDisabledRows() || row.enabled())
                .toList();
        int setupRows = 0;
        int postCheckRows = 0;
        int stateChangingOrConsumableRows = 0;
        java.util.ArrayList<PlanValidationIssue> issues = new java.util.ArrayList<>();
        for (AccessTestCase row : rows) {
            if (row.setup().mode() != SetupMode.NONE) {
                setupRows++;
            }
            if (row.postChecks().stream().anyMatch(PostCheckRequest::enabled)) {
                postCheckRows++;
            }
            AccessRequestTemplate template = row.targetType() == AccessTargetType.REQUEST_TEMPLATE
                    ? snapshot.templates().get(row.templateId())
                    : null;
            AccessFlowTarget flowTarget = row.targetType() == AccessTargetType.FLOW_TARGET
                    ? snapshot.flowTargets().get(row.flowTargetId())
                    : null;
            if ((template != null && safetySensitive(template))
                    || (flowTarget != null && safetySensitive(flowTarget))) {
                stateChangingOrConsumableRows++;
            }
            validatePlanRow(row, snapshot, runConfig, issues);
        }
        return new AccessPlanPreview(
                new PlanSafetySummary(rows.size(), setupRows, postCheckRows, stateChangingOrConsumableRows),
                issues);
    }

    /**
     * Requests the active plan to stop before the next row starts.
     */
    public void requestStopPlan() {
        if (planRunner != null) {
            planRunner.requestStop();
        }
    }

    /**
     * Returns whether a plan is currently running.
     *
     * @return true when running
     */
    public boolean isPlanRunning() {
        return planRunner != null && planRunner.isRunning();
    }

    /**
     * Renders a template preview using selected auth and data profiles.
     *
     * @param templateId template id
     * @param authProfileId auth profile id
     * @param dataProfileId data profile id
     * @param env environment variables
     * @param scenario scenario variables
     * @param legacy legacy variables
     * @param mode render mode
     * @return rendered preview
     */
    public AccessPreview preview(
            String templateId,
            String authProfileId,
            String dataProfileId,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode mode) {
        AccessRequestTemplate template = repository.template(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Template is required"));
        AuthProfile authProfile = repository.authProfile(authProfileId)
                .orElseThrow(() -> new IllegalArgumentException("Auth profile is required"));
        DataProfile dataProfile = dataProfileId == null || dataProfileId.isBlank()
                ? null
                : repository.dataProfile(dataProfileId)
                .orElseThrow(() -> new IllegalArgumentException("Data profile was not found"));

        ScopedVariableStore store = storeFactory.from(env, authProfile.state(), dataProfile, scenario, legacy);
        RenderedAccessRequest rendered = templateRenderer.render(
                template,
                store,
                Objects.requireNonNullElse(mode, RenderMode.STRICT));
        return new AccessPreview(
                rendered,
                requestSummary(template.baseRequest()),
                requestSummary(rendered.renderedRequest()));
    }

    /**
     * Looks up an auth profile by id.
     *
     * @param id profile id
     * @return matching profile
     */
    public Optional<AuthProfile> authProfile(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return repository.authProfile(id);
    }

    /**
     * Looks up an auth provider by id.
     *
     * @param id provider id
     * @return matching provider
     */
    public Optional<AuthProvider> authProvider(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return repository.authProvider(id);
    }

    /**
     * Looks up a data profile by id.
     *
     * @param id profile id
     * @return matching profile
     */
    public Optional<DataProfile> dataProfile(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return repository.dataProfile(id);
    }

    /**
     * Looks up a scenario by id.
     *
     * @param id scenario id
     * @return matching scenario
     */
    public Optional<ScenarioInstance> scenario(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return repository.scenario(id);
    }

    /**
     * Looks up an evaluation profile by id.
     *
     * @param id profile id
     * @return matching profile
     */
    public Optional<EvaluationProfile> evaluationProfile(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return repository.evaluationProfile(id);
    }

    private void validatePlanRow(
            AccessTestCase row,
            AccessWorkspace snapshot,
            AccessPlanRunConfig runConfig,
            List<PlanValidationIssue> issues) {
        AccessRequestTemplate template = row.targetType() == AccessTargetType.REQUEST_TEMPLATE
                ? snapshot.templates().get(row.templateId())
                : null;
        AccessFlowTarget flowTarget = row.targetType() == AccessTargetType.FLOW_TARGET
                ? snapshot.flowTargets().get(row.flowTargetId())
                : null;
        AuthProfile authProfile = snapshot.authProfiles().get(row.target().targetAuthProfileId());
        DataProfile dataProfile = resolveDataProfile(row.target().dataProfileId(), snapshot, row, issues);

        if (row.targetType() == AccessTargetType.FLOW_TARGET) {
            if (flowTarget == null) {
                issue(row, issues, PlanValidationSeverity.ERROR, "Flow Target",
                        "Missing flow target: " + row.flowTargetId());
            }
        } else if (template == null) {
            issue(row, issues, PlanValidationSeverity.ERROR, "Template",
                    "Missing template: " + row.templateId());
        }
        if (authProfile == null) {
            issue(row, issues, PlanValidationSeverity.ERROR, "Auth",
                    "Missing target auth profile: " + row.target().targetAuthProfileId());
        } else if (!isManual(authProfile) && snapshot.authProviders().get(authProfile.providerId()) == null) {
            issue(row, issues, PlanValidationSeverity.WARNING, "Auth",
                    "Auth provider is missing for target profile: " + authProfile.providerId());
        }
        if (row.targetType() == AccessTargetType.REQUEST_TEMPLATE
                && template != null
                && templateUsesData(template)
                && row.target().dataProfileId().isBlank()) {
            issue(row, issues, PlanValidationSeverity.ERROR, "Data",
                    "Missing data profile for template data.* placeholders");
        }
        validateSetup(row, snapshot, issues);
        validateTeardown(row, snapshot, authProfile, dataProfile, issues);
        if (!row.evaluationProfileId().isBlank()
                && !snapshot.evaluationProfiles().containsKey(row.evaluationProfileId())) {
            issue(row, issues, PlanValidationSeverity.WARNING, "Evaluation",
                    "Evaluation profile is missing: " + row.evaluationProfileId());
        }

        if (template != null && authProfile != null && (row.target().dataProfileId().isBlank() || dataProfile != null)) {
            Map<String, String> scenario = validationScenario(row, snapshot, runConfig.scenario())
                    .orElse(runConfig.scenario());
            validateRender(row, "Target", template, authProfile, dataProfile, runConfig.env(), scenario,
                    runConfig.legacy(), runConfig.renderMode(), issues);
        } else if (flowTarget != null && authProfile != null) {
            validateFlowTarget(row, flowTarget, authProfile, dataProfile, runConfig, issues);
        }
        validatePostChecks(row, snapshot, authProfile, dataProfile, runConfig, issues);
    }

    private void validateFlowTarget(
            AccessTestCase row,
            AccessFlowTarget flowTarget,
            AuthProfile authProfile,
            DataProfile dataProfile,
            AccessPlanRunConfig runConfig,
            List<PlanValidationIssue> issues) {
        ScopedVariableStore store = storeFactory.from(
                runConfig.env(),
                authProfile.state(),
                dataProfile,
                runConfig.scenario(),
                runConfig.legacy());
        TemplateRenderer renderer = new TemplateRenderer(new VariableResolver(new DefaultRuntimeValueProvider()));
        for (FlowInjectionOverride override : flowTarget.injectionOverrides()) {
            if (!override.enabled()) {
                continue;
            }
            dev.nquangit.corestra.access.variable.RenderResult rendered = renderer.render(
                    override.valueTemplate(),
                    store,
                    runConfig.renderMode());
            if (rendered.hasUnresolvedVariables()) {
                String unresolved = String.join("; ", rendered.unresolvedVariables().stream()
                        .map(item -> item.placeholder() + " " + item.errorType())
                        .distinct()
                        .toList());
                issue(row, issues, PlanValidationSeverity.ERROR, "Unresolved",
                        "Flow override '" + override.name() + "' has unresolved placeholders: "
                                + (unresolved.isBlank() ? "unknown" : unresolved));
            }
            rendered.warnings().forEach(warning ->
                    issue(row, issues, PlanValidationSeverity.WARNING, "Render",
                            "Flow override '" + override.name() + "': " + warning));
        }
    }

    private DataProfile resolveDataProfile(
            String dataProfileId,
            AccessWorkspace snapshot,
            AccessTestCase row,
            List<PlanValidationIssue> issues) {
        if (dataProfileId == null || dataProfileId.isBlank()) {
            return null;
        }
        DataProfile dataProfile = snapshot.dataProfiles().get(dataProfileId);
        if (dataProfile == null) {
            issue(row, issues, PlanValidationSeverity.ERROR, "Data", "Missing data profile: " + dataProfileId);
        }
        return dataProfile;
    }

    private void validateSetup(AccessTestCase row, AccessWorkspace snapshot, List<PlanValidationIssue> issues) {
        switch (row.setup().mode()) {
            case NONE -> {
                if (!row.target().scenarioInstanceId().isBlank()
                        && !snapshot.scenarios().containsKey(row.target().scenarioInstanceId())) {
                    issue(row, issues, PlanValidationSeverity.ERROR, "Scenario",
                            "Missing scenario: " + row.target().scenarioInstanceId());
                }
            }
            case REUSE_SCENARIO -> {
                if (row.target().scenarioInstanceId().isBlank()) {
                    issue(row, issues, PlanValidationSeverity.ERROR, "Scenario",
                            "REUSE_SCENARIO requires a scenario id");
                } else if (!snapshot.scenarios().containsKey(row.target().scenarioInstanceId())) {
                    issue(row, issues, PlanValidationSeverity.ERROR, "Scenario",
                            "Missing scenario: " + row.target().scenarioInstanceId());
                }
            }
            case RUN_PREFIX -> {
                if (row.setup().setupFlowId().isBlank()) {
                    issue(row, issues, PlanValidationSeverity.ERROR, "Setup",
                            "RUN_PREFIX requires a setup flow id");
                }
                if (row.setup().untilBeforeStepId().isBlank()) {
                    issue(row, issues, PlanValidationSeverity.ERROR, "Setup",
                            "RUN_PREFIX requires untilBeforeStepId");
                }
            }
            case RUN_SETUP_FLOW -> {
                if (row.setup().setupFlowId().isBlank()) {
                    issue(row, issues, PlanValidationSeverity.ERROR, "Setup",
                            "RUN_SETUP_FLOW requires a setup flow id");
                }
            }
        }
    }

    private Optional<Map<String, String>> validationScenario(
            AccessTestCase row,
            AccessWorkspace snapshot,
            Map<String, String> scenario) {
        if (row.target().scenarioInstanceId().isBlank()) {
            return Optional.of(Objects.requireNonNullElse(scenario, Map.of()));
        }
        ScenarioInstance instance = snapshot.scenarios().get(row.target().scenarioInstanceId());
        if (instance == null) {
            return Optional.empty();
        }
        java.util.LinkedHashMap<String, String> merged =
                new java.util.LinkedHashMap<>(Objects.requireNonNullElse(scenario, Map.of()));
        merged.putAll(instance.toVariableMap());
        return Optional.of(Map.copyOf(merged));
    }

    private void validateRender(
            AccessTestCase row,
            String label,
            AccessRequestTemplate template,
            AuthProfile authProfile,
            DataProfile dataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode mode,
            List<PlanValidationIssue> issues) {
        ScopedVariableStore store = storeFactory.from(env, authProfile.state(), dataProfile, scenario, legacy);
        RenderedAccessRequest rendered = templateRenderer.render(template, store, mode);
        if (!rendered.success()) {
            String unresolved = String.join("; ", rendered.unresolvedVariables().stream()
                    .map(item -> item.placeholder() + " " + item.errorType())
                    .distinct()
                    .toList());
            issue(row, issues, PlanValidationSeverity.ERROR, "Unresolved",
                    label + " has unresolved placeholders: " + (unresolved.isBlank() ? "unknown" : unresolved));
        }
        rendered.warnings().forEach(warning ->
                issue(row, issues, PlanValidationSeverity.WARNING, "Render", label + ": " + warning));
    }

    private void validatePostChecks(
            AccessTestCase row,
            AccessWorkspace snapshot,
            AuthProfile targetAuthProfile,
            DataProfile targetDataProfile,
            AccessPlanRunConfig runConfig,
            List<PlanValidationIssue> issues) {
        for (PostCheckRequest postCheck : row.postChecks()) {
            if (!postCheck.enabled()) {
                continue;
            }
            AccessRequestTemplate template = snapshot.templates().get(postCheck.templateId());
            if (template == null) {
                issue(row, issues, PlanValidationSeverity.ERROR, "Post-check",
                        "Post-check '" + postCheck.name() + "' missing template: " + postCheck.templateId());
                continue;
            }
            DataProfile dataProfile = !postCheck.dataProfileId().isBlank()
                    ? snapshot.dataProfiles().get(postCheck.dataProfileId())
                    : targetDataProfile;
            if (!postCheck.dataProfileId().isBlank() && dataProfile == null) {
                issue(row, issues, PlanValidationSeverity.ERROR, "Post-check",
                        "Post-check '" + postCheck.name() + "' missing data profile: " + postCheck.dataProfileId());
            }
            AuthProfile authProfile = resolvePostCheckAuthProfile(postCheck, targetAuthProfile, dataProfile, snapshot)
                    .orElse(null);
            if (authProfile == null) {
                issue(row, issues, PlanValidationSeverity.ERROR, "Post-check",
                        "Post-check '" + postCheck.name() + "' auth profile could not be resolved");
            } else if (!isManual(authProfile) && snapshot.authProviders().get(authProfile.providerId()) == null) {
                issue(row, issues, PlanValidationSeverity.WARNING, "Post-check",
                        "Post-check '" + postCheck.name() + "' auth provider is missing: " + authProfile.providerId());
            }
            if (templateUsesData(template) && dataProfile == null) {
                issue(row, issues, PlanValidationSeverity.ERROR, "Post-check",
                        "Post-check '" + postCheck.name() + "' requires a data profile");
            }
            Optional<Map<String, String>> scenario = postCheckScenario(postCheck, runConfig.scenario(), snapshot);
            if (scenario.isEmpty()) {
                issue(row, issues, PlanValidationSeverity.ERROR, "Post-check",
                        "Post-check '" + postCheck.name() + "' missing scenario: " + postCheck.scenarioInstanceId());
            }
            if (authProfile != null && (!templateUsesData(template) || dataProfile != null) && scenario.isPresent()) {
                validateRender(row, "Post-check '" + postCheck.name() + "'", template, authProfile, dataProfile,
                        runConfig.env(), scenario.orElseThrow(), runConfig.legacy(), runConfig.renderMode(), issues);
            }
        }
    }

    private void validateTeardown(
            AccessTestCase row,
            AccessWorkspace snapshot,
            AuthProfile targetAuthProfile,
            DataProfile targetDataProfile,
            List<PlanValidationIssue> issues) {
        if (row.teardown().mode() == TeardownMode.NONE) {
            return;
        }
        if (row.teardown().teardownFlowId().isBlank()) {
            issue(row, issues, PlanValidationSeverity.ERROR, "Teardown", "Teardown flow id is required");
        } else {
            Optional<FlowDefinition> flow = flows().stream()
                    .filter(candidate -> candidate.id().equals(row.teardown().teardownFlowId()))
                    .findFirst();
            if (flow.isEmpty()) {
                issue(row, issues, PlanValidationSeverity.ERROR, "Teardown",
                        "Teardown flow not found: " + row.teardown().teardownFlowId());
            } else {
                if (!flow.get().enabled()) {
                    issue(row, issues, PlanValidationSeverity.ERROR, "Teardown",
                            "Teardown flow is disabled: " + flow.get().id());
                }
                if (flow.get().steps().stream().noneMatch(dev.nquangit.corestra.flow.FlowStep::enabled)) {
                    issue(row, issues, PlanValidationSeverity.ERROR, "Teardown",
                            "Teardown flow has no enabled steps: " + flow.get().id());
                }
            }
        }
        AuthProfile teardownAuth = switch (row.teardown().authBinding()) {
            case TARGET_ACTOR -> targetAuthProfile;
            case DATA_OWNER -> targetDataProfile == null || targetDataProfile.ownerAuthProfileId().isBlank()
                    ? null
                    : snapshot.authProfiles().get(targetDataProfile.ownerAuthProfileId());
            case FIXED_PROFILE -> row.teardown().teardownAuthProfileId().isBlank()
                    ? null
                    : snapshot.authProfiles().get(row.teardown().teardownAuthProfileId());
        };
        if (teardownAuth == null) {
            issue(row, issues, PlanValidationSeverity.ERROR, "Teardown",
                    "Teardown auth profile could not be resolved");
        }
    }

    private Optional<AuthProfile> resolvePostCheckAuthProfile(
            PostCheckRequest postCheck,
            AuthProfile targetAuthProfile,
            DataProfile dataProfile,
            AccessWorkspace snapshot) {
        return switch (postCheck.authBinding()) {
            case TARGET_ACTOR -> Optional.ofNullable(targetAuthProfile);
            case DATA_OWNER -> dataProfile == null || dataProfile.ownerAuthProfileId().isBlank()
                    ? Optional.empty()
                    : Optional.ofNullable(snapshot.authProfiles().get(dataProfile.ownerAuthProfileId()));
            case FIXED_PROFILE -> postCheck.authProfileId().isBlank()
                    ? Optional.empty()
                    : Optional.ofNullable(snapshot.authProfiles().get(postCheck.authProfileId()));
        };
    }

    private Optional<Map<String, String>> postCheckScenario(
            PostCheckRequest postCheck,
            Map<String, String> scenario,
            AccessWorkspace snapshot) {
        if (postCheck.scenarioInstanceId().isBlank()) {
            return Optional.of(Objects.requireNonNullElse(scenario, Map.of()));
        }
        ScenarioInstance instance = snapshot.scenarios().get(postCheck.scenarioInstanceId());
        if (instance == null) {
            return Optional.empty();
        }
        java.util.LinkedHashMap<String, String> merged =
                new java.util.LinkedHashMap<>(Objects.requireNonNullElse(scenario, Map.of()));
        merged.putAll(instance.toVariableMap());
        return Optional.of(Map.copyOf(merged));
    }

    private void issue(
            AccessTestCase row,
            List<PlanValidationIssue> issues,
            PlanValidationSeverity severity,
            String category,
            String message) {
        issues.add(new PlanValidationIssue(row.id(), row.name(), severity, category, message));
    }

    private boolean safetySensitive(AccessRequestTemplate template) {
        OperationMetadata metadata = template.operationMetadata();
        return metadata.stateChanging()
                || metadata.consumable()
                || metadata.operationType() == OperationType.STATE_CHANGING
                || metadata.operationType() == OperationType.CONSUMABLE;
    }

    private boolean safetySensitive(AccessFlowTarget flowTarget) {
        return flowTarget.flowSnapshot().steps().stream()
                .filter(FlowStep::enabled)
                .anyMatch(step -> switch (step.requestTemplate().method().toUpperCase(java.util.Locale.ROOT)) {
                    case "GET", "HEAD", "OPTIONS" -> false;
                    default -> true;
                });
    }

    private static boolean isManual(AuthProfile profile) {
        return profile.providerId().isBlank() || AuthProfile.MANUAL_PROVIDER_ID.equals(profile.providerId());
    }

    private void ensureEditable() {
        if (isPlanRunning()) {
            throw new IllegalStateException("Access plan is running; configuration editing is disabled");
        }
    }

    private boolean templateUsesData(AccessRequestTemplate template) {
        return template.placeholders().stream().map(ScopedVariableRef::scope).anyMatch(VariableScope.DATA::equals);
    }

    private Optional<AccessRequestTemplate> templateForFlowStep(String flowId, String stepId) {
        String normalizedFlowId = flowId == null ? "" : flowId.strip();
        String normalizedStepId = stepId == null ? "" : stepId.strip();
        return repository.templates().stream()
                .filter(template -> template.sourceFlowId().equals(normalizedFlowId))
                .filter(template -> template.sourceStepId().equals(normalizedStepId))
                .findFirst();
    }

    private String resolveFlowTargetStepId(FlowDefinition flow, String targetStepId) {
        if (targetStepId != null && !targetStepId.isBlank()) {
            String normalized = targetStepId.strip();
            if (!flow.hasStep(normalized)) {
                throw new IllegalArgumentException("Target step is not part of the selected flow");
            }
            return normalized;
        }
        for (int index = flow.steps().size() - 1; index >= 0; index--) {
            FlowStep step = flow.steps().get(index);
            if (step.enabled()) {
                return step.id();
            }
        }
        throw new IllegalArgumentException("Flow has no enabled steps to target");
    }

    private String stepName(FlowDefinition flow, String stepId) {
        return flow.steps().stream()
                .filter(step -> step.id().equals(stepId))
                .map(FlowStep::name)
                .findFirst()
                .orElse(stepId);
    }

    private AccessRequestTemplateFactory templateFactory() {
        return new AccessRequestTemplateFactory(
                idGenerator,
                new AccessRequestTemplateAnalyzer(new TemplateRenderer(
                        new VariableResolver(new DefaultRuntimeValueProvider()))),
                new RequestTemplateHasher(),
                RenderMode.STRICT);
    }

    private AccessRequestTemplate disabled(AccessRequestTemplate template) {
        return new AccessRequestTemplate(
                template.id(),
                template.name(),
                template.baseRequest(),
                template.placeholders(),
                template.operationMetadata(),
                template.sourceFlowId(),
                template.sourceStepId(),
                template.sourceRequestHash(),
                false);
    }

    private AccessTestCase copyWithStatus(AccessTestCase original, RunStatus status, long lastRunAtEpochMillis) {
        return new AccessTestCase(
                original.id(),
                original.name(),
                original.targetType(),
                original.templateId(),
                original.flowTargetId(),
                original.flowId(),
                original.targetStepId(),
                original.setup(),
                original.target(),
                original.expected(),
                original.evaluationProfileId(),
                original.assertions(),
                original.postChecks(),
                original.teardown(),
                original.enabled(),
                status,
                lastRunAtEpochMillis);
    }

    private String normalizeExistingOrNew(String existingId, String prefix) {
        if (existingId == null || existingId.isBlank()) {
            return idGenerator.newId(prefix);
        }
        return existingId.strip();
    }

    private static String normalizeScopedKey(String value, String prefix, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " key must not be blank");
        }
        String key = value.strip();
        if (!key.startsWith(prefix)) {
            throw new IllegalArgumentException(label + " key must start with " + prefix);
        }
        key = key.substring(prefix.length()).strip();
        if (key.isEmpty() || key.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(
                    label + " key must not contain whitespace");
        }
        return key;
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }

    private String normalizeProviderForSave(String providerId) {
        String normalized = providerId == null || providerId.isBlank()
                ? AuthProfile.MANUAL_PROVIDER_ID
                : providerId.strip();
        if (!AuthProfile.MANUAL_PROVIDER_ID.equals(normalized)
                && repository.authProvider(normalized).isEmpty()) {
            throw new IllegalArgumentException("Auth provider not found: " + normalized);
        }
        return normalized;
    }

    private String normalizeOwnerProfileId(String authProfileId) {
        if (authProfileId == null || authProfileId.isBlank()) {
            return "";
        }
        String normalized = authProfileId.strip();
        if (repository.authProfile(normalized).isEmpty()) {
            throw new IllegalArgumentException("Owner auth profile not found: " + normalized);
        }
        return normalized;
    }

    private static String requestSummary(StoredRequestTemplate request) {
        StringBuilder summary = new StringBuilder();
        summary.append(request.method()).append(' ')
                .append(request.scheme()).append("://")
                .append(request.host()).append(':')
                .append(request.port())
                .append(request.pathAndQuery())
                .append('\n');
        for (String header : request.headerLines()) {
            summary.append(header).append('\n');
        }
        if (!request.body().isEmpty()) {
            summary.append('\n').append(request.body());
        }
        return summary.toString();
    }
}
