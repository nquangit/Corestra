package dev.nquangit.corestra.access.repository;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.evaluate.EvaluationProfile;
import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.matrix.RunStatus;
import dev.nquangit.corestra.access.matrix.SetupConfig;
import dev.nquangit.corestra.access.matrix.TargetBinding;
import dev.nquangit.corestra.access.report.SavedFindingDraft;
import dev.nquangit.corestra.access.result.AccessEvidence;
import dev.nquangit.corestra.access.result.AccessTestResult;
import dev.nquangit.corestra.access.result.Verdict;
import dev.nquangit.corestra.access.scenario.ScenarioInstance;
import dev.nquangit.corestra.access.scenario.ScenarioVariable;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.OperationMetadata;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryAccessRepositoryTest {
    @Test
    void savesListsGetsAndDeletesAllModelTypes() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AuthProvider authProvider = provider("provider-1");
        AuthProfile authProfile = AuthProfile.manual("auth-1", "Owner", "admin");
        DataProfile dataProfile = DataProfile.enabled("data-1", "Owner data", "auth-1", Map.of("accountId", "acct-1"));
        AccessRequestTemplate template = template("tmpl-1");
        AccessTestCase testCase = testCase("case-1", template.id(), authProfile.id(), dataProfile.id());
        AccessTestResult result = result("result-1", testCase.id());
        ScenarioInstance scenario = scenario("scenario-1");
        EvaluationProfile evaluationProfile = evaluationProfile("eval-1");

        repository.saveAuthProvider(authProvider);
        repository.saveAuthProfile(authProfile);
        repository.saveDataProfile(dataProfile);
        repository.saveTemplate(template);
        repository.saveTestCase(testCase);
        repository.saveResult(result);
        repository.saveScenario(scenario);
        repository.saveEvaluationProfile(evaluationProfile);

        assertEquals(List.of(authProvider), repository.authProviders());
        assertEquals(List.of(authProfile), repository.authProfiles());
        assertEquals(List.of(dataProfile), repository.dataProfiles());
        assertEquals(List.of(template), repository.templates());
        assertEquals(List.of(testCase), repository.testCases());
        assertEquals(List.of(result), repository.results());
        assertEquals(List.of(scenario), repository.scenarios());
        assertEquals(List.of(evaluationProfile), repository.evaluationProfiles());
        assertEquals(authProvider, repository.authProvider(authProvider.id()).orElseThrow());
        assertEquals(authProfile, repository.authProfile(authProfile.id()).orElseThrow());
        assertEquals(dataProfile, repository.dataProfile(dataProfile.id()).orElseThrow());
        assertEquals(template, repository.template(template.id()).orElseThrow());
        assertEquals(testCase, repository.testCase(testCase.id()).orElseThrow());
        assertEquals(result, repository.result(result.id()).orElseThrow());
        assertEquals(scenario, repository.scenario(scenario.id()).orElseThrow());
        assertEquals(evaluationProfile, repository.evaluationProfile(evaluationProfile.id()).orElseThrow());

        assertTrue(repository.deleteAuthProvider(authProvider.id()));
        assertTrue(repository.deleteAuthProfile(authProfile.id()));
        assertTrue(repository.deleteDataProfile(dataProfile.id()));
        assertTrue(repository.deleteTemplate(template.id()));
        assertTrue(repository.deleteResult(result.id()));
        assertTrue(repository.deleteTestCase(testCase.id()));
        assertTrue(repository.deleteScenario(scenario.id()));
        assertTrue(repository.deleteEvaluationProfile(evaluationProfile.id()));
        assertFalse(repository.authProvider(authProvider.id()).isPresent());
        assertFalse(repository.authProfile(authProfile.id()).isPresent());
        assertFalse(repository.dataProfile(dataProfile.id()).isPresent());
        assertFalse(repository.template(template.id()).isPresent());
        assertFalse(repository.testCase(testCase.id()).isPresent());
        assertFalse(repository.result(result.id()).isPresent());
        assertFalse(repository.scenario(scenario.id()).isPresent());
        assertFalse(repository.evaluationProfile(evaluationProfile.id()).isPresent());
    }

    @Test
    void projectStateMutationsInvokePersistenceSinkButScenarioMutationsDoNot() {
        ArrayList<AccessWorkspace> persisted = new ArrayList<>();
        InMemoryAccessRepository repository = new InMemoryAccessRepository(AccessWorkspace.empty(), persisted::add);
        AuthProvider authProvider = provider("provider-1");
        AuthProfile authProfile = AuthProfile.manual("auth-1", "Owner", "admin");
        DataProfile dataProfile = DataProfile.enabled("data-1", "Owner data", "auth-1", Map.of("accountId", "acct-1"));
        AccessRequestTemplate template = template("tmpl-1");
        EvaluationProfile evaluationProfile = evaluationProfile("eval-1");
        AccessTestCase testCase = testCase("case-1", template.id(), authProfile.id(), dataProfile.id());
        AccessTestResult result = result("result-1", testCase.id());
        ScenarioInstance scenario = scenario("scenario-1");
        AccessEvidence evidence = new AccessEvidence(
                testCase.id(),
                result.id(),
                "GET / HTTP/1.1",
                "GET / HTTP/1.1",
                "HTTP/1.1 200 OK",
                "HTTP/1.1 200 OK",
                1000L);

        repository.saveAuthProvider(authProvider);
        repository.saveAuthProfile(authProfile);
        repository.saveDataProfile(dataProfile);
        repository.saveTemplate(template);
        repository.saveEvaluationProfile(evaluationProfile);

        assertEquals(5, persisted.size());
        AccessWorkspace lastConfigSnapshot = persisted.getLast();
        assertEquals(Map.of(authProvider.id(), authProvider), lastConfigSnapshot.authProviders());
        assertEquals(Map.of(authProfile.id(), authProfile), lastConfigSnapshot.authProfiles());
        assertEquals(Map.of(dataProfile.id(), dataProfile), lastConfigSnapshot.dataProfiles());
        assertEquals(Map.of(template.id(), template), lastConfigSnapshot.templates());
        assertEquals(Map.of(evaluationProfile.id(), evaluationProfile), lastConfigSnapshot.evaluationProfiles());

        repository.saveTestCase(testCase);
        repository.saveResult(result);
        repository.saveEvidence(evidence);
        repository.saveScenario(scenario);

        assertEquals(8, persisted.size());
        AccessWorkspace lastProjectSnapshot = persisted.getLast();
        assertEquals(Map.of(testCase.id(), testCase), lastProjectSnapshot.testCases());
        assertEquals(Map.of(result.id(), result), lastProjectSnapshot.results());
        assertTrue(lastProjectSnapshot.scenarios().isEmpty());
    }

    @Test
    void repositoryDoesNotExposeInternalMutableLists() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AuthProfile first = AuthProfile.manual("auth-1", "Owner", "admin");
        AuthProfile second = AuthProfile.manual("auth-2", "Target", "viewer");
        repository.saveAuthProfile(first);

        List<AuthProfile> listed = repository.authProfiles();
        repository.saveAuthProfile(second);

        assertEquals(List.of(first), listed);
        assertThrows(UnsupportedOperationException.class, () -> listed.add(second));
        assertEquals(List.of(first, second), repository.authProfiles());
    }

    @Test
    void snapshotReturnsImmutableWorkspaceAndReplaceWithReplacesAllObjects() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AuthProfile first = AuthProfile.manual("auth-1", "Owner", "admin");
        AuthProvider provider = provider("provider-1");
        AuthProfile replacement = AuthProfile.manual("auth-2", "Target", "viewer");
        DataProfile dataProfile = DataProfile.enabled("data-1", "Target data", "auth-2", Map.of("accountId", "acct-2"));
        AccessTestResult result = result("result-1", "case-1");
        ScenarioInstance scenario = scenario("scenario-1");
        EvaluationProfile evaluationProfile = evaluationProfile("eval-1");
        AccessEvidence evidence = new AccessEvidence(
                "case-1",
                result.id(),
                "GET / HTTP/1.1",
                "GET / HTTP/1.1",
                "HTTP/1.1 200 OK",
                "HTTP/1.1 200 OK",
                1000L);
        repository.saveAuthProfile(first);
        repository.saveAuthProvider(provider);

        AccessWorkspace snapshot = repository.snapshot();
        assertEquals(Map.of(provider.id(), provider), snapshot.authProviders());
        assertEquals(Map.of(first.id(), first), snapshot.authProfiles());
        AuthProvider anotherProvider = provider("provider-2");
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.authProviders().put(anotherProvider.id(), anotherProvider));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.authProfiles().put(replacement.id(), replacement));

        AuthProvider replacementProvider = anotherProvider;
        repository.replaceWith(AccessWorkspace.builder()
                .authProvider(replacementProvider)
                .authProfile(replacement)
                .dataProfile(dataProfile)
                .result(result)
                .scenario(scenario)
                .evaluationProfile(evaluationProfile)
                .build(), List.of(evidence));

        assertFalse(repository.authProvider(provider.id()).isPresent());
        assertFalse(repository.authProfile(first.id()).isPresent());
        assertEquals(List.of(replacementProvider), repository.authProviders());
        assertEquals(List.of(replacement), repository.authProfiles());
        assertEquals(List.of(dataProfile), repository.dataProfiles());
        assertEquals(List.of(result), repository.results());
        assertEquals(List.of(scenario), repository.scenarios());
        assertEquals(List.of(evaluationProfile), repository.evaluationProfiles());
        assertEquals(List.of(evidence), repository.evidence());
    }

    @Test
    void savesFindsAndDeletesEvidenceByResultId() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AccessTestResult result = result("result-1", "case-1");
        AccessEvidence evidence = new AccessEvidence(
                "case-1",
                result.id(),
                "GET / HTTP/1.1",
                "GET / HTTP/1.1",
                "HTTP/1.1 200 OK",
                "HTTP/1.1 200 OK",
                1000L);

        repository.saveResult(result);
        repository.saveEvidence(evidence);

        assertEquals(List.of(evidence), repository.evidence());
        assertEquals(evidence, repository.evidenceByResultId(result.id()).orElseThrow());

        repository.deleteResult(result.id());

        assertTrue(repository.evidenceByResultId(result.id()).isEmpty());
    }

    @Test
    void savingNewResultForSameTestCaseOverwritesPriorArtifactsAndDraft() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AccessTestResult firstResult = result("result-1", "case-1");
        AccessEvidence firstEvidence = evidence(firstResult);
        SavedFindingDraft firstDraft = new SavedFindingDraft("case-1", firstResult.id(), "first", 1000L);
        AccessTestResult secondResult = result("result-2", "case-1");
        AccessEvidence secondEvidence = evidence(secondResult);

        repository.saveResult(firstResult);
        repository.saveEvidence(firstEvidence);
        repository.saveReportDraft(firstDraft);
        repository.saveResult(secondResult);

        assertEquals(List.of(secondResult), repository.results());
        assertTrue(repository.evidence().isEmpty());
        assertTrue(repository.reportDraft("case-1").isEmpty());

        repository.saveEvidence(secondEvidence);

        assertEquals(List.of(secondEvidence), repository.evidence());
    }

    @Test
    void deletingTestCaseRemovesLatestResultEvidenceAndDraft() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AccessTestCase testCase = testCase("case-1", "tmpl-1", "auth-1", "data-1");
        AccessTestResult result = result("result-1", testCase.id());
        AccessEvidence evidence = evidence(result);
        SavedFindingDraft draft = new SavedFindingDraft(testCase.id(), result.id(), "draft", 1000L);

        repository.saveTestCase(testCase);
        repository.saveResult(result);
        repository.saveEvidence(evidence);
        repository.saveReportDraft(draft);

        assertTrue(repository.deleteTestCase(testCase.id()));

        assertTrue(repository.results().isEmpty());
        assertTrue(repository.evidence().isEmpty());
        assertTrue(repository.reportDraft(testCase.id()).isEmpty());
    }

    private static AccessRequestTemplate template(String id) {
        return new AccessRequestTemplate(
                id,
                "Template",
                new StoredRequestTemplate("GET", "https", "example.test", 443, "/accounts", List.of(), ""),
                java.util.Set.of(),
                OperationMetadata.unknown(),
                "flow-1",
                "step-1",
                "hash-1",
                true);
    }

    private static AuthProvider provider(String id) {
        return new AuthProvider(
                id,
                "Provider " + id,
                "Provider description",
                true,
                "login-flow",
                "refresh-flow",
                "validate-flow",
                List.of(),
                List.of(),
                List.of(),
                AuthFallbackPolicy.MANUAL_ONLY,
                AuthRetryPolicy.NO_RETRY);
    }

    private static AccessTestCase testCase(String id, String templateId, String authProfileId, String dataProfileId) {
        return new AccessTestCase(
                id,
                "Access test",
                templateId,
                "flow-1",
                "step-1",
                SetupConfig.none(),
                new TargetBinding(authProfileId, dataProfileId, "env-1", ""),
                ExpectedResult.DENY,
                List.of(),
                true,
                RunStatus.PENDING,
                0L);
    }

    private static AccessTestResult result(String id, String testCaseId) {
        return new AccessTestResult(
                id,
                testCaseId,
                RunStatus.COMPLETED,
                Verdict.OK,
                "",
                1000L,
                1100L,
                "",
                "",
                List.of());
    }

    private static AccessEvidence evidence(AccessTestResult result) {
        return new AccessEvidence(
                result.testCaseId(),
                result.id(),
                "GET / HTTP/1.1",
                "GET / HTTP/1.1",
                "HTTP/1.1 200 OK",
                "HTTP/1.1 200 OK",
                1000L);
    }

    private static ScenarioInstance scenario(String id) {
        ScenarioVariable variable = new ScenarioVariable(
                "confirmToken",
                "confirm-123",
                1000L,
                0L,
                false,
                false,
                "flow-1",
                "step-1",
                "data-1");
        return new ScenarioInstance(
                id,
                "Scenario " + id,
                "data-1",
                Map.of(variable.key(), variable),
                dev.nquangit.corestra.access.matrix.ScenarioPolicy.REUSE_EXISTING,
                1000L,
                false,
                "flow-1",
                "step-2");
    }

    private static EvaluationProfile evaluationProfile(String id) {
        return new EvaluationProfile(
                id,
                "Evaluation " + id,
                true,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }
}
