package dev.nquangit.corestra.access.repository;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.evaluate.EvaluationProfile;
import dev.nquangit.corestra.access.flow.AccessFlowTarget;
import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.persistence.AccessWorkspaceDocument;
import dev.nquangit.corestra.access.report.SavedFindingDraft;
import dev.nquangit.corestra.access.result.AccessEvidence;
import dev.nquangit.corestra.access.result.AccessTestResult;
import dev.nquangit.corestra.access.runner.AccessPlanSummary;
import dev.nquangit.corestra.access.scenario.ScenarioInstance;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Thread-safe in-memory repository for access-testing workspace objects.
 */
public final class InMemoryAccessRepository {
    private final LinkedHashMap<String, AuthProvider> authProviders = new LinkedHashMap<>();
    private final LinkedHashMap<String, AuthProfile> authProfiles = new LinkedHashMap<>();
    private final LinkedHashMap<String, DataProfile> dataProfiles = new LinkedHashMap<>();
    private final LinkedHashMap<String, AccessRequestTemplate> templates = new LinkedHashMap<>();
    private final LinkedHashMap<String, AccessFlowTarget> flowTargets = new LinkedHashMap<>();
    private final LinkedHashMap<String, AccessTestCase> testCases = new LinkedHashMap<>();
    private final LinkedHashMap<String, AccessTestResult> results = new LinkedHashMap<>();
    private final LinkedHashMap<String, ScenarioInstance> scenarios = new LinkedHashMap<>();
    private final LinkedHashMap<String, EvaluationProfile> evaluationProfiles = new LinkedHashMap<>();
    private final LinkedHashMap<String, AccessEvidence> evidenceByResultId = new LinkedHashMap<>();
    private final LinkedHashMap<String, SavedFindingDraft> reportDrafts = new LinkedHashMap<>();
    private final Consumer<AccessWorkspaceDocument> projectPersistenceSink;
    private AccessPlanSummary latestSummary;

    /**
     * Creates a repository with no backing persistence.
     */
    public InMemoryAccessRepository() {
        this(AccessWorkspaceDocument.empty(), document -> { });
    }

    /**
     * Creates a repository initialized from a workspace and wired to a configuration persistence sink.
     *
     * @param initialWorkspace initial repository contents
     * @param configurationPersistenceSink sink that persists reusable configuration snapshots
     */
    public InMemoryAccessRepository(
            AccessWorkspace initialWorkspace,
            Consumer<AccessWorkspace> configurationPersistenceSink) {
        this(
                new AccessWorkspaceDocument(Objects.requireNonNull(initialWorkspace, "initialWorkspace"), List.of()),
                document -> Objects.requireNonNull(configurationPersistenceSink, "configurationPersistenceSink")
                        .accept(document.workspace()));
    }

    /**
     * Creates a repository initialized from access project data and wired to a project persistence sink.
     *
     * @param initialDocument initial repository contents
     * @param projectPersistenceSink sink that persists project state snapshots
     */
    public InMemoryAccessRepository(
            AccessWorkspaceDocument initialDocument,
            Consumer<AccessWorkspaceDocument> projectPersistenceSink) {
        this.projectPersistenceSink = Objects.requireNonNull(projectPersistenceSink, "projectPersistenceSink");
        replaceMaps(Objects.requireNonNull(initialDocument, "initialDocument").workspace(),
                initialDocument.evidence());
        latestSummary = initialDocument.latestSummary().orElse(null);
        reportDrafts.putAll(initialDocument.reportDrafts());
    }

    /**
     * Returns auth providers in insertion order.
     *
     * @return immutable list copy of auth providers
     */
    public synchronized List<AuthProvider> authProviders() {
        return List.copyOf(authProviders.values());
    }

    /**
     * Finds an auth provider by id.
     *
     * @param id auth provider id
     * @return matching auth provider, or empty
     */
    public synchronized Optional<AuthProvider> authProvider(String id) {
        return Optional.ofNullable(authProviders.get(normalizeId(id)));
    }

    /**
     * Saves an auth provider.
     *
     * @param provider auth provider to save
     */
    public synchronized void saveAuthProvider(AuthProvider provider) {
        Objects.requireNonNull(provider, "provider");
        authProviders.put(provider.id(), provider);
        persistProjectState();
    }

    /**
     * Deletes an auth provider by id.
     *
     * @param id auth provider id
     * @return true when a provider was removed
     */
    public synchronized boolean deleteAuthProvider(String id) {
        boolean removed = authProviders.remove(normalizeId(id)) != null;
        if (removed) {
            persistProjectState();
        }
        return removed;
    }

    /**
     * Returns auth profiles in insertion order.
     *
     * @return immutable list copy of auth profiles
     */
    public synchronized List<AuthProfile> authProfiles() {
        return List.copyOf(authProfiles.values());
    }

    /**
     * Finds an auth profile by id.
     *
     * @param id auth profile id
     * @return matching auth profile, or empty
     */
    public synchronized Optional<AuthProfile> authProfile(String id) {
        return Optional.ofNullable(authProfiles.get(normalizeId(id)));
    }

    /**
     * Saves an auth profile.
     *
     * @param profile auth profile to save
     */
    public synchronized void saveAuthProfile(AuthProfile profile) {
        Objects.requireNonNull(profile, "profile");
        authProfiles.put(profile.id(), profile);
        persistProjectState();
    }

    /**
     * Deletes an auth profile by id.
     *
     * @param id auth profile id
     * @return true when a profile was removed
     */
    public synchronized boolean deleteAuthProfile(String id) {
        boolean removed = authProfiles.remove(normalizeId(id)) != null;
        if (removed) {
            persistProjectState();
        }
        return removed;
    }

    /**
     * Returns data profiles in insertion order.
     *
     * @return immutable list copy of data profiles
     */
    public synchronized List<DataProfile> dataProfiles() {
        return List.copyOf(dataProfiles.values());
    }

    /**
     * Finds a data profile by id.
     *
     * @param id data profile id
     * @return matching data profile, or empty
     */
    public synchronized Optional<DataProfile> dataProfile(String id) {
        return Optional.ofNullable(dataProfiles.get(normalizeId(id)));
    }

    /**
     * Saves a data profile.
     *
     * @param profile data profile to save
     */
    public synchronized void saveDataProfile(DataProfile profile) {
        Objects.requireNonNull(profile, "profile");
        dataProfiles.put(profile.id(), profile);
        persistProjectState();
    }

    /**
     * Deletes a data profile by id.
     *
     * @param id data profile id
     * @return true when a profile was removed
     */
    public synchronized boolean deleteDataProfile(String id) {
        boolean removed = dataProfiles.remove(normalizeId(id)) != null;
        if (removed) {
            persistProjectState();
        }
        return removed;
    }

    /**
     * Returns access request templates in insertion order.
     *
     * @return immutable list copy of templates
     */
    public synchronized List<AccessRequestTemplate> templates() {
        return List.copyOf(templates.values());
    }

    /**
     * Finds an access request template by id.
     *
     * @param id template id
     * @return matching template, or empty
     */
    public synchronized Optional<AccessRequestTemplate> template(String id) {
        return Optional.ofNullable(templates.get(normalizeId(id)));
    }

    /**
     * Saves an access request template.
     *
     * @param template template to save
     */
    public synchronized void saveTemplate(AccessRequestTemplate template) {
        Objects.requireNonNull(template, "template");
        templates.put(template.id(), template);
        persistProjectState();
    }

    /**
     * Deletes an access request template by id.
     *
     * @param id template id
     * @return true when a template was removed
     */
    public synchronized boolean deleteTemplate(String id) {
        boolean removed = templates.remove(normalizeId(id)) != null;
        if (removed) {
            persistProjectState();
        }
        return removed;
    }

    /**
     * Returns access flow targets in insertion order.
     *
     * @return immutable list copy of flow targets
     */
    public synchronized List<AccessFlowTarget> flowTargets() {
        return List.copyOf(flowTargets.values());
    }

    /**
     * Finds an access flow target by id.
     *
     * @param id flow target id
     * @return matching flow target, or empty
     */
    public synchronized Optional<AccessFlowTarget> flowTarget(String id) {
        return Optional.ofNullable(flowTargets.get(normalizeId(id)));
    }

    /**
     * Saves an access flow target.
     *
     * @param flowTarget flow target to save
     */
    public synchronized void saveFlowTarget(AccessFlowTarget flowTarget) {
        Objects.requireNonNull(flowTarget, "flowTarget");
        flowTargets.put(flowTarget.id(), flowTarget);
        persistProjectState();
    }

    /**
     * Deletes an access flow target by id.
     *
     * @param id flow target id
     * @return true when a flow target was removed
     */
    public synchronized boolean deleteFlowTarget(String id) {
        boolean removed = flowTargets.remove(normalizeId(id)) != null;
        if (removed) {
            persistProjectState();
        }
        return removed;
    }

    /**
     * Returns access test cases in insertion order.
     *
     * @return immutable list copy of test cases
     */
    public synchronized List<AccessTestCase> testCases() {
        return List.copyOf(testCases.values());
    }

    /**
     * Finds an access test case by id.
     *
     * @param id test case id
     * @return matching test case, or empty
     */
    public synchronized Optional<AccessTestCase> testCase(String id) {
        return Optional.ofNullable(testCases.get(normalizeId(id)));
    }

    /**
     * Saves an access test case.
     *
     * @param testCase test case to save
     */
    public synchronized void saveTestCase(AccessTestCase testCase) {
        Objects.requireNonNull(testCase, "testCase");
        testCases.put(testCase.id(), testCase);
        persistProjectState();
    }

    /**
     * Deletes an access test case by id.
     *
     * @param id test case id
     * @return true when a test case was removed
     */
    public synchronized boolean deleteTestCase(String id) {
        String normalizedId = normalizeId(id);
        boolean removed = testCases.remove(normalizedId) != null;
        if (removed) {
            removeRunArtifactsForTestCase(normalizedId);
            persistProjectState();
        }
        return removed;
    }

    /**
     * Returns access test results in insertion order.
     *
     * @return immutable list copy of results
     */
    public synchronized List<AccessTestResult> results() {
        return List.copyOf(results.values());
    }

    /**
     * Finds an access test result by id.
     *
     * @param id result id
     * @return matching result, or empty
     */
    public synchronized Optional<AccessTestResult> result(String id) {
        return Optional.ofNullable(results.get(normalizeId(id)));
    }

    /**
     * Saves an access test result.
     *
     * @param result result to save
     */
    public synchronized void saveResult(AccessTestResult result) {
        Objects.requireNonNull(result, "result");
        removeRunArtifactsForTestCase(result.testCaseId());
        results.put(result.id(), result);
        persistProjectState();
    }

    /**
     * Deletes an access test result by id.
     *
     * @param id result id
     * @return true when a result was removed
     */
    public synchronized boolean deleteResult(String id) {
        String normalizedId = normalizeId(id);
        AccessTestResult result = results.get(normalizedId);
        evidenceByResultId.remove(normalizedId);
        boolean removed = results.remove(normalizedId) != null;
        if (removed) {
            if (result != null) {
                reportDrafts.remove(result.testCaseId());
            }
            persistProjectState();
        }
        return removed;
    }

    /**
     * Returns scenario instances in insertion order.
     *
     * @return immutable list copy of scenarios
     */
    public synchronized List<ScenarioInstance> scenarios() {
        return List.copyOf(scenarios.values());
    }

    /**
     * Finds a scenario by id.
     *
     * @param id scenario id
     * @return matching scenario, or empty
     */
    public synchronized Optional<ScenarioInstance> scenario(String id) {
        return Optional.ofNullable(scenarios.get(normalizeId(id)));
    }

    /**
     * Saves a scenario instance.
     *
     * @param scenario scenario to save
     */
    public synchronized void saveScenario(ScenarioInstance scenario) {
        Objects.requireNonNull(scenario, "scenario");
        scenarios.put(scenario.id(), scenario);
    }

    /**
     * Deletes a scenario by id.
     *
     * @param id scenario id
     * @return true when a scenario was removed
     */
    public synchronized boolean deleteScenario(String id) {
        return scenarios.remove(normalizeId(id)) != null;
    }

    /**
     * Returns evaluation profiles in insertion order.
     *
     * @return immutable list copy of evaluation profiles
     */
    public synchronized List<EvaluationProfile> evaluationProfiles() {
        return List.copyOf(evaluationProfiles.values());
    }

    /**
     * Finds an evaluation profile by id.
     *
     * @param id evaluation profile id
     * @return matching evaluation profile, or empty
     */
    public synchronized Optional<EvaluationProfile> evaluationProfile(String id) {
        return Optional.ofNullable(evaluationProfiles.get(normalizeId(id)));
    }

    /**
     * Saves an evaluation profile.
     *
     * @param profile evaluation profile to save
     */
    public synchronized void saveEvaluationProfile(EvaluationProfile profile) {
        Objects.requireNonNull(profile, "profile");
        evaluationProfiles.put(profile.id(), profile);
        persistProjectState();
    }

    /**
     * Deletes an evaluation profile by id.
     *
     * @param id evaluation profile id
     * @return true when a profile was removed
     */
    public synchronized boolean deleteEvaluationProfile(String id) {
        boolean removed = evaluationProfiles.remove(normalizeId(id)) != null;
        if (removed) {
            persistProjectState();
        }
        return removed;
    }

    /**
     * Returns access evidence in insertion order.
     *
     * @return immutable list copy of evidence entries
     */
    public synchronized List<AccessEvidence> evidence() {
        return List.copyOf(evidenceByResultId.values());
    }

    /**
     * Finds evidence by result id.
     *
     * @param resultId result id
     * @return matching evidence, or empty
     */
    public synchronized Optional<AccessEvidence> evidenceByResultId(String resultId) {
        return Optional.ofNullable(evidenceByResultId.get(normalizeId(resultId)));
    }

    /**
     * Saves an evidence entry by its result id.
     *
     * @param evidence evidence to save
     */
    public synchronized void saveEvidence(AccessEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        if (!results.containsKey(evidence.resultId())) {
            return;
        }
        evidenceByResultId.put(evidence.resultId(), evidence);
        persistProjectState();
    }

    /**
     * Returns the latest persisted plan summary.
     *
     * @return latest plan summary, or empty
     */
    public synchronized Optional<AccessPlanSummary> latestSummary() {
        return Optional.ofNullable(latestSummary);
    }

    /**
     * Saves the latest plan summary.
     *
     * @param summary latest plan summary
     */
    public synchronized void saveLatestSummary(AccessPlanSummary summary) {
        latestSummary = Objects.requireNonNull(summary, "summary");
        persistProjectState();
    }

    /**
     * Returns saved report drafts in insertion order.
     *
     * @return immutable report draft list
     */
    public synchronized List<SavedFindingDraft> reportDrafts() {
        return List.copyOf(reportDrafts.values());
    }

    /**
     * Returns saved report drafts keyed by test case id.
     *
     * @return immutable report draft map
     */
    public synchronized Map<String, SavedFindingDraft> reportDraftMap() {
        return Map.copyOf(reportDrafts);
    }

    /**
     * Finds a saved report draft by test case id.
     *
     * @param testCaseId matrix row id
     * @return matching report draft, or empty
     */
    public synchronized Optional<SavedFindingDraft> reportDraft(String testCaseId) {
        if (testCaseId == null || testCaseId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(reportDrafts.get(testCaseId.strip()));
    }

    /**
     * Saves a report draft for its test case.
     *
     * @param draft report draft
     */
    public synchronized void saveReportDraft(SavedFindingDraft draft) {
        Objects.requireNonNull(draft, "draft");
        if (!results.containsKey(draft.resultId())) {
            throw new IllegalArgumentException("Result not found: " + draft.resultId());
        }
        reportDrafts.put(draft.testCaseId(), draft);
        persistProjectState();
    }

    /**
     * Returns an immutable snapshot of the repository contents.
     *
     * @return immutable access workspace snapshot
     */
    public synchronized AccessWorkspace snapshot() {
        return new AccessWorkspace(authProviders, authProfiles, dataProfiles, templates, flowTargets, testCases, results,
                scenarios, evaluationProfiles);
    }

    /** Returns only immutable profile inputs, without copying results or evidence for live traffic. */
    public synchronized AccessWorkspace profileSnapshot() {
        return new AccessWorkspace(authProfiles, dataProfiles, Map.of(), Map.of(), Map.of());
    }

    /**
     * Returns an immutable snapshot of all project-persistent access data.
     *
     * @return access project document snapshot
     */
    public synchronized AccessWorkspaceDocument snapshotDocument() {
        return new AccessWorkspaceDocument(snapshot(), evidence(), latestSummary, reportDraftMap());
    }

    /**
     * Replaces all repository contents from a workspace snapshot.
     *
     * @param workspace replacement workspace
     */
    public synchronized void replaceWith(AccessWorkspace workspace) {
        replaceWith(workspace, List.of());
    }

    /**
     * Replaces all repository contents from a workspace snapshot and evidence entries.
     *
     * @param workspace replacement workspace
     * @param evidence replacement evidence entries
     */
    public synchronized void replaceWith(AccessWorkspace workspace, List<AccessEvidence> evidence) {
        replaceWith(new AccessWorkspaceDocument(workspace, evidence));
    }

    /**
     * Replaces all repository contents from access project data.
     *
     * @param document replacement document
     */
    public synchronized void replaceWith(AccessWorkspaceDocument document) {
        Objects.requireNonNull(document, "document");
        AccessWorkspace workspace = document.workspace();
        List<AccessEvidence> evidence = document.evidence();
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(evidence, "evidence");
        replaceMaps(workspace, evidence);
        latestSummary = document.latestSummary().orElse(null);
        reportDrafts.clear();
        reportDrafts.putAll(document.reportDrafts());
        persistProjectState();
    }

    private void replaceMaps(AccessWorkspace workspace, List<AccessEvidence> evidence) {
        authProviders.clear();
        authProfiles.clear();
        dataProfiles.clear();
        templates.clear();
        flowTargets.clear();
        testCases.clear();
        results.clear();
        scenarios.clear();
        evaluationProfiles.clear();
        evidenceByResultId.clear();
        reportDrafts.clear();
        authProviders.putAll(workspace.authProviders());
        authProfiles.putAll(workspace.authProfiles());
        dataProfiles.putAll(workspace.dataProfiles());
        templates.putAll(workspace.templates());
        flowTargets.putAll(workspace.flowTargets());
        testCases.putAll(workspace.testCases());
        results.putAll(workspace.results());
        scenarios.putAll(workspace.scenarios());
        evaluationProfiles.putAll(workspace.evaluationProfiles());
        for (AccessEvidence entry : evidence) {
            Objects.requireNonNull(entry, "evidence entry");
            evidenceByResultId.put(entry.resultId(), entry);
        }
    }

    private void removeRunArtifactsForTestCase(String testCaseId) {
        String normalizedId = normalizeId(testCaseId);
        results.entrySet().removeIf(entry -> {
            if (entry.getValue().testCaseId().equals(normalizedId)) {
                evidenceByResultId.remove(entry.getKey());
                return true;
            }
            return false;
        });
        reportDrafts.remove(normalizedId);
    }

    private void persistProjectState() {
        projectPersistenceSink.accept(snapshotDocument());
    }

    private static String normalizeId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        return id.strip();
    }
}
