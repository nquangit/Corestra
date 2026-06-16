package dev.nquangit.corestra.access.persistence;

import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.evaluate.EvaluationProfile;
import dev.nquangit.corestra.access.flow.AccessFlowTarget;
import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.matrix.RunStatus;
import dev.nquangit.corestra.access.matrix.SetupConfig;
import dev.nquangit.corestra.access.matrix.TargetBinding;
import dev.nquangit.corestra.access.report.SavedFindingDraft;
import dev.nquangit.corestra.access.result.AccessEvidence;
import dev.nquangit.corestra.access.result.AccessTestResult;
import dev.nquangit.corestra.access.result.Verdict;
import dev.nquangit.corestra.access.runner.AccessPlanSummary;
import dev.nquangit.corestra.access.scenario.ScenarioInstance;
import dev.nquangit.corestra.access.scenario.ScenarioVariable;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.OperationMetadata;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.config.CorestraSettingsKeys;
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessWorkspaceProjectRepositoryTest {
    @Test
    void savesAndLoadsProjectStateButExcludesRuntimeScenarios() {
        FakePersistence persistence = new FakePersistence();
        AccessWorkspaceProjectRepository repository = new AccessWorkspaceProjectRepository(persistence.persistence());
        AccessWorkspaceDocument document = documentWithProjectState();

        repository.save(document);

        String storedPayload = persistence.state().getString(CorestraSettingsKeys.ACCESS_WORKSPACE_DOCUMENT);
        assertTrue(storedPayload.contains("provider-1"));
        assertTrue(storedPayload.contains("auth-1"));
        assertTrue(storedPayload.contains("data-1"));
        assertTrue(storedPayload.contains("template-1"));
        assertTrue(storedPayload.contains("flowtarget-1"));
        assertTrue(storedPayload.contains("eval-1"));
        assertTrue(storedPayload.contains("case-1"));
        assertTrue(storedPayload.contains("result-1"));
        assertTrue(storedPayload.contains("draft markdown"));
        assertTrue(storedPayload.contains("latestSummary"));
        assertFalse(storedPayload.contains("scenario-1"));

        AccessWorkspaceDocument restoredDocument = new AccessWorkspaceProjectRepository(persistence.persistence()).load();
        AccessWorkspace restored = restoredDocument.workspace();
        assertEquals("Session Auth", restored.authProviders().get("provider-1").name());
        assertEquals("Owner", restored.authProfiles().get("auth-1").name());
        assertEquals("Owner data", restored.dataProfiles().get("data-1").name());
        assertEquals("Template", restored.templates().get("template-1").name());
        assertEquals("Access Flow Target", restored.flowTargets().get("flowtarget-1").name());
        assertEquals("Business API", restored.evaluationProfiles().get("eval-1").name());
        assertEquals("Access test", restored.testCases().get("case-1").name());
        assertEquals(Verdict.OK, restored.results().get("result-1").verdict());
        assertEquals("GET /accounts", restoredDocument.evidence().getFirst().redactedRequestRaw());
        assertEquals(new AccessPlanSummary(1, 1, 1, 0, 0, 0, 0, 1, 0, 0, false, 1000L, 1100L),
                restoredDocument.latestSummary().orElseThrow());
        assertEquals("draft markdown", restoredDocument.reportDrafts().get("case-1").markdown());
        assertTrue(restored.scenarios().isEmpty());
    }

    @Test
    void savingEmptyProjectStateDeletesStoredPayload() {
        FakePersistence persistence = new FakePersistence();
        persistence.state().seedString(CorestraSettingsKeys.ACCESS_WORKSPACE_DOCUMENT, "stale");

        new AccessWorkspaceProjectRepository(persistence.persistence()).save(AccessWorkspaceDocument.empty());

        assertNull(persistence.state().getString(CorestraSettingsKeys.ACCESS_WORKSPACE_DOCUMENT));
        assertEquals(1, persistence.state().deleteStringCallCount());
    }

    @Test
    void invalidStoredPayloadIsQuarantinedAndReturnsEmptyDocument() {
        FakePersistence persistence = new FakePersistence();
        persistence.state().seedString(CorestraSettingsKeys.ACCESS_WORKSPACE_DOCUMENT, "{not-json");

        AccessWorkspaceDocument restored = new AccessWorkspaceProjectRepository(persistence.persistence()).load();

        assertEquals(AccessWorkspace.empty(), restored.workspace());
        assertTrue(restored.evidence().isEmpty());
        assertTrue(restored.latestSummary().isEmpty());
        assertTrue(restored.reportDrafts().isEmpty());
        assertNull(persistence.state().getString(CorestraSettingsKeys.ACCESS_WORKSPACE_DOCUMENT));
        assertEquals("{not-json",
                persistence.state().getString(CorestraSettingsKeys.ACCESS_WORKSPACE_DOCUMENT_QUARANTINE));
        assertEquals(1, persistence.state().setStringCallCount());
        assertEquals(1, persistence.state().deleteStringCallCount());
    }

    private static AccessWorkspaceDocument documentWithProjectState() {
        AuthProvider provider = new AuthProvider(
                "provider-1",
                "Session Auth",
                "",
                true,
                "login-flow",
                "refresh-flow",
                "validate-flow",
                List.of(),
                List.of(),
                List.of(),
                AuthFallbackPolicy.MANUAL_ONLY,
                AuthRetryPolicy.NO_RETRY);
        AuthProfile authProfile = AuthProfile.manual("auth-1", "Owner", "admin");
        DataProfile dataProfile = DataProfile.enabled("data-1", "Owner data", authProfile.id(), Map.of("accountId", "acct-1"));
        AccessRequestTemplate template = new AccessRequestTemplate(
                "template-1",
                "Template",
                new StoredRequestTemplate("GET", "https", "example.test", 443, "/accounts", List.of(), ""),
                java.util.Set.of(),
                OperationMetadata.unknown(),
                "flow-1",
                "step-1",
                "hash-1",
                true);
        EvaluationProfile evaluationProfile = new EvaluationProfile(
                "eval-1", "Business API", true, List.of(), List.of(), List.of(), List.of());
        AccessFlowTarget flowTarget = new AccessFlowTarget(
                "flowtarget-1",
                "Access Flow Target",
                flow("flow-1"),
                "flow-1",
                "Access Flow",
                "hash-flow",
                "step-1",
                true,
                List.of());
        AccessTestCase testCase = new AccessTestCase(
                "case-1",
                "Access test",
                template.id(),
                "flow-1",
                "step-1",
                SetupConfig.none(),
                new TargetBinding(authProfile.id(), dataProfile.id(), "env-1", ""),
                ExpectedResult.DENY,
                List.of(),
                true,
                RunStatus.PENDING,
                0L);
        AccessTestResult result = new AccessTestResult(
                "result-1",
                testCase.id(),
                RunStatus.COMPLETED,
                Verdict.OK,
                "",
                1000L,
                1100L,
                "",
                "",
                List.of());
        ScenarioVariable variable = new ScenarioVariable(
                "confirmToken", "confirm-123", 1000L, 0L, false, false, "flow-1", "step-1", dataProfile.id());
        ScenarioInstance scenario = new ScenarioInstance(
                "scenario-1",
                "Scenario",
                dataProfile.id(),
                Map.of(variable.key(), variable),
                dev.nquangit.corestra.access.matrix.ScenarioPolicy.REUSE_EXISTING,
                1000L,
                false,
                "flow-1",
                "step-2");

        AccessWorkspace workspace = AccessWorkspace.builder()
                .authProvider(provider)
                .authProfile(authProfile)
                .dataProfile(dataProfile)
                .template(template)
                .flowTarget(flowTarget)
                .evaluationProfile(evaluationProfile)
                .testCase(testCase)
                .result(result)
                .scenario(scenario)
                .build();
        AccessEvidence evidence = new AccessEvidence(
                testCase.id(),
                result.id(),
                "GET /accounts",
                "GET /accounts",
                "HTTP/1.1 403 Forbidden",
                "HTTP/1.1 403 Forbidden",
                1100L);
        AccessPlanSummary summary = new AccessPlanSummary(
                1, 1, 1, 0, 0, 0, 0, 1, 0, 0, false, 1000L, 1100L);
        SavedFindingDraft draft = new SavedFindingDraft(
                testCase.id(),
                result.id(),
                "draft markdown",
                1200L);
        return new AccessWorkspaceDocument(workspace, List.of(evidence), summary, Map.of(draft.testCaseId(), draft));
    }

    private static FlowDefinition flow(String id) {
        return new FlowDefinition(
                id,
                "Access Flow",
                "",
                true,
                List.of(new FlowStep(
                        "step-1",
                        "Step 1",
                        true,
                        "",
                        new StoredRequestTemplate("GET", "https", "example.test", 443, "/accounts", List.of(), ""),
                        List.of(),
                        List.of(),
                        List.of(),
                        FlowStep.StepErrorStrategy.FAIL_FLOW)),
                FlowRuleSet.empty(),
                AggregationPlan.disabled(),
                FlowTriggerConfig.none());
    }

    private static Object handleObjectMethod(Object proxy, String name, Object[] args) {
        return switch (name) {
            case "toString" -> "FakePersistenceProxy";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(name);
        };
    }

    private static final class FakePersistence {
        private final PersistedObjectState state = new PersistedObjectState();
        private final Persistence persistence = (Persistence) Proxy.newProxyInstance(
                Persistence.class.getClassLoader(),
                new Class<?>[]{Persistence.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return handleObjectMethod(proxy, method.getName(), args);
                    }
                    return switch (method.getName()) {
                        case "extensionData" -> state.proxy();
                        default -> throw new UnsupportedOperationException(method.toString());
                    };
                });

        private Persistence persistence() {
            return persistence;
        }

        private PersistedObjectState state() {
            return state;
        }
    }

    private static final class PersistedObjectState {
        private final Map<String, String> stringValues = new HashMap<>();
        private int setStringCallCount;
        private int deleteStringCallCount;
        private final PersistedObject proxy = (PersistedObject) Proxy.newProxyInstance(
                PersistedObject.class.getClassLoader(),
                new Class<?>[]{PersistedObject.class},
                (instance, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return handleObjectMethod(instance, method.getName(), args);
                    }
                    return switch (method.getName()) {
                        case "getString" -> stringValues.get((String) args[0]);
                        case "setString" -> {
                            setStringCallCount++;
                            stringValues.put((String) args[0], (String) args[1]);
                            yield null;
                        }
                        case "deleteString" -> {
                            deleteStringCallCount++;
                            stringValues.remove((String) args[0]);
                            yield null;
                        }
                        default -> throw new UnsupportedOperationException(method.toString());
                    };
                });

        private PersistedObject proxy() {
            return proxy;
        }

        private void seedString(String key, String value) {
            stringValues.put(key, value);
        }

        private String getString(String key) {
            return stringValues.get(key);
        }

        private int setStringCallCount() {
            return setStringCallCount;
        }

        private int deleteStringCallCount() {
            return deleteStringCallCount;
        }
    }
}
