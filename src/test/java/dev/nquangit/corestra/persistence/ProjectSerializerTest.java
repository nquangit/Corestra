package dev.nquangit.corestra.persistence;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.persistence.AccessWorkspaceSerializer;
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
import dev.nquangit.corestra.access.security.ExportMode;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.OperationMetadata;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.session.SessionAuthConfig;
import dev.nquangit.corestra.session.SessionExtractionRule;
import dev.nquangit.corestra.session.SessionInjectionRule;
import dev.nquangit.corestra.session.SessionProfile;
import dev.nquangit.corestra.session.SessionSchedulerConfig;
import dev.nquangit.corestra.session.SessionScope;
import dev.nquangit.corestra.session.SessionState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProjectSerializerTest {

    private final FlowSerializer flowSerializer = new FlowSerializer();
    private final ProjectSerializer serializer = new ProjectSerializer(flowSerializer);

    @Test
    void serializeProjectRoundTrip() {
        ProjectMetadata meta = new ProjectMetadata("MyProj", "desc", "alice", "note", 1000L, 2000L);
        List<FlowDefinition> flows = List.of(sampleFlow("flow-1", "Login"));
        ProjectDocument doc = new ProjectDocument(1, meta, flows);

        String json = serializer.serializeProject(doc);
        ProjectDocument restored = serializer.deserializeProject(json);

        assertEquals(meta, restored.metadata());
        assertEquals(1, restored.flows().size());
        assertEquals("flow-1", restored.flows().get(0).id());
        assertEquals("Login", restored.flows().get(0).name());
        assertEquals(1, restored.schemaVersion());
        assertEquals(ProjectSettings.defaults(), restored.settings());
        assertEquals(AccessWorkspace.empty(), restored.accessWorkspace());
        assertEquals(List.of(), restored.accessEvidence());
        assertEquals(SessionProfile.GENERAL_ID, restored.sessionProfiles().getFirst().id());
    }

    @Test
    void serializeProjectRoundTripPreservesSettings() {
        ProjectMetadata meta = new ProjectMetadata("MyProj", "desc", "alice", "note", 1000L, 2000L);
        ProjectSettings settings = new ProjectSettings(HttpSendMode.PROXY_PIPELINE, "127.0.0.2", 9090);
        ProjectDocument doc = new ProjectDocument(1, meta, settings, List.of(sampleFlow("flow-1", "Login")),
                AccessWorkspace.empty(), List.of());

        String json = serializer.serializeProject(doc);
        ProjectDocument restored = serializer.deserializeProject(json);

        assertTrue(json.contains("\"settings\""));
        assertEquals(settings, restored.settings());
    }

    @Test
    void serializeProjectDefaultSanitizesAccessWorkspaceAndEvidence() {
        ProjectMetadata meta = new ProjectMetadata("MyProj", "desc", "alice", "note", 1000L, 2000L);
        AccessWorkspace workspace = accessWorkspace();
        List<AccessEvidence> evidence = List.of(accessEvidence());
        ProjectDocument doc = new ProjectDocument(1, meta, List.of(sampleFlow("flow-1", "Login")),
                workspace,
                evidence,
                summary(),
                Map.of("case-1", draft("Finding with accessToken=access-secret")));

        String json = serializer.serializeProject(doc);
        ProjectDocument restored = serializer.deserializeProject(json);

        assertTrue(json.contains("\"access\""));
        assertFalse(json.contains("access-secret"));
        assertFalse(json.contains("password-secret"));
        assertFalse(json.contains("cookie-secret"));
        assertEquals("<redacted>", restored.accessWorkspace()
                .authProfiles().get("auth-1").state().variables().get("accessToken"));
        assertEquals("<redacted>", restored.accessWorkspace()
                .authProfiles().get("auth-1").credentials().get("password"));
        assertEquals("Alice Display", restored.accessWorkspace()
                .dataProfiles().get("data-1").values().get("displayName"));
        assertEquals("Session Auth", restored.accessWorkspace()
                .authProviders().get("provider-1").name());
        assertEquals("Access test", restored.accessWorkspace().testCases().get("case-1").name());
        assertEquals(Verdict.OK, restored.accessWorkspace().results().get("result-1").verdict());
        assertTrue(restored.accessWorkspace().scenarios().isEmpty());
        assertEquals(1, restored.accessEvidence().size());
        assertTrue(restored.accessEvidence().getFirst().redactedRequestRaw().contains("<redacted>"));
        assertEquals(summary(), restored.latestAccessPlanSummary().orElseThrow());
        assertTrue(restored.accessReportDrafts().get("case-1").markdown().contains("<redacted>"));
    }

    @Test
    void serializeProjectFullExportKeepsAccessProjectStateAndSecrets() {
        ProjectMetadata meta = new ProjectMetadata("MyProj", "desc", "alice", "note", 1000L, 2000L);
        ProjectDocument doc = new ProjectDocument(1, meta, List.of(),
                accessWorkspace(),
                List.of(accessEvidence()),
                summary(),
                Map.of("case-1", draft("Finding with accessToken=access-secret")));

        String json = serializer.serializeProject(doc, ExportMode.FULL_EXPORT);
        ProjectDocument restored = serializer.deserializeProject(json);

        assertTrue(json.contains("access-secret"));
        assertTrue(json.contains("password-secret"));
        assertEquals("access-secret", restored.accessWorkspace()
                .authProfiles().get("auth-1").state().variables().get("accessToken"));
        assertEquals("login-flow", restored.accessWorkspace()
                .authProviders().get("provider-1").loginFlowId());
        assertEquals("Access test", restored.accessWorkspace().testCases().get("case-1").name());
        assertEquals(Verdict.OK, restored.accessWorkspace().results().get("result-1").verdict());
        assertTrue(restored.accessWorkspace().scenarios().isEmpty());
        assertEquals(1, restored.accessEvidence().size());
        assertEquals(summary(), restored.latestAccessPlanSummary().orElseThrow());
        assertEquals("Finding with accessToken=access-secret", restored.accessReportDrafts().get("case-1").markdown());
    }

    @Test
    void serializeProjectDefaultSanitizesSessionState() {
        ProjectMetadata meta = new ProjectMetadata("MyProj", "desc", "alice", "note", 1000L, 2000L);
        ProjectDocument doc = new ProjectDocument(
                1,
                meta,
                ProjectSettings.defaults(),
                List.of(),
                AccessWorkspace.empty(),
                List.of(),
                null,
                Map.of(),
                sessionProfiles());

        String json = serializer.serializeProject(doc);
        ProjectDocument restored = serializer.deserializeProject(json);

        assertTrue(json.contains("\"sessions\""));
        assertFalse(json.contains("session-secret"));
        assertFalse(json.contains("plain-session-secret"));
        SessionProfile restoredProfile = restored.sessionProfiles().stream()
                .filter(profile -> profile.id().equals("session-1"))
                .findFirst()
                .orElseThrow();
        assertEquals("<redacted>", restoredProfile.state().values().get("accessToken"));
        assertEquals("<redacted>", restoredProfile.state().values().get("plainValue"));
    }

    @Test
    void serializeProjectFullExportKeepsSessionState() {
        ProjectMetadata meta = new ProjectMetadata("MyProj", "desc", "alice", "note", 1000L, 2000L);
        ProjectDocument doc = new ProjectDocument(
                1,
                meta,
                ProjectSettings.defaults(),
                List.of(),
                AccessWorkspace.empty(),
                List.of(),
                null,
                Map.of(),
                sessionProfiles());

        String json = serializer.serializeProject(doc, ExportMode.FULL_EXPORT);
        ProjectDocument restored = serializer.deserializeProject(json);

        assertTrue(json.contains("session-secret"));
        assertTrue(json.contains("plain-session-secret"));
        SessionProfile restoredProfile = restored.sessionProfiles().stream()
                .filter(profile -> profile.id().equals("session-1"))
                .findFirst()
                .orElseThrow();
        assertEquals("session-secret", restoredProfile.state().values().get("accessToken"));
        assertEquals(1, restoredProfile.injectionRules().size());
        assertEquals(1, restoredProfile.extractionRules().size());
    }

    @Test
    void deserializeProjectWithoutAccessSectionUsesEmptyAccessWorkspace() {
        String json = """
                {
                  "schemaVersion": 1,
                  "metadata": {
                    "name": "Old",
                    "description": "",
                    "owner": "",
                    "note": "",
                    "createdAtEpochMillis": 1,
                    "updatedAtEpochMillis": 1
                  },
                  "flows": []
                }
                """;

        ProjectDocument restored = serializer.deserializeProject(json);

        assertEquals(ProjectSettings.defaults(), restored.settings());
        assertEquals(AccessWorkspace.empty(), restored.accessWorkspace());
        assertEquals(List.of(), restored.accessEvidence());
        assertEquals(SessionProfile.GENERAL_ID, restored.sessionProfiles().getFirst().id());
    }

    @Test
    void deserializeProjectWithInvalidSettingsUsesDefaults() {
        String json = """
                {
                  "schemaVersion": 1,
                  "metadata": {
                    "name": "Invalid Settings",
                    "description": "",
                    "owner": "",
                    "note": "",
                    "createdAtEpochMillis": 1,
                    "updatedAtEpochMillis": 1
                  },
                  "settings": {
                    "sendMode": "PROXY_PIPELINE",
                    "proxyHost": "127.0.0.1",
                    "proxyPort": 70000
                  },
                  "flows": []
                }
                """;

        ProjectDocument restored = serializer.deserializeProject(json);

        assertEquals(ProjectSettings.defaults(), restored.settings());
    }

    @Test
    void deserializeProjectPreservesMatrixResultsEvidenceButIgnoresScenarios() throws Exception {
        String accessJson = new ObjectMapper().writeValueAsString(
                new AccessWorkspaceSerializer().serialize(
                        accessWorkspace(), List.of(accessEvidence()), ExportMode.FULL_EXPORT));
        String json = """
                {
                  "schemaVersion": 1,
                  "metadata": {
                    "name": "Legacy Matrix",
                    "description": "",
                    "owner": "",
                    "note": "",
                    "createdAtEpochMillis": 1,
                    "updatedAtEpochMillis": 1
                  },
                  "flows": [],
                  "access": ACCESS_JSON
                }
                """.replace("ACCESS_JSON", accessJson);

        ProjectDocument restored = serializer.deserializeProject(json);

        assertEquals("Session Auth", restored.accessWorkspace().authProviders().get("provider-1").name());
        assertEquals("Owner", restored.accessWorkspace().authProfiles().get("auth-1").name());
        assertEquals("Access test", restored.accessWorkspace().testCases().get("case-1").name());
        assertEquals(Verdict.OK, restored.accessWorkspace().results().get("result-1").verdict());
        assertTrue(restored.accessWorkspace().scenarios().isEmpty());
        assertEquals(1, restored.accessEvidence().size());
    }

    @Test
    void serializeFlowsRoundTrip() {
        List<FlowDefinition> flows = List.of(sampleFlow("f1", "A"), sampleFlow("f2", "B"));

        String json = serializer.serializeFlows(flows);
        List<FlowDefinition> restored = serializer.deserializeFlows(json);

        assertEquals(2, restored.size());
        assertEquals("f1", restored.get(0).id());
        assertEquals("f2", restored.get(1).id());
    }

    @Test
    void deserializeFlowsAcceptsProjectFile() {
        ProjectMetadata meta = new ProjectMetadata("P", "", "", "", 1L, 1L);
        List<FlowDefinition> flows = List.of(sampleFlow("fx", "X"));
        String projectJson = serializer.serializeProject(new ProjectDocument(1, meta, flows));

        List<FlowDefinition> extracted = serializer.deserializeFlows(projectJson);

        assertEquals(1, extracted.size());
        assertEquals("fx", extracted.get(0).id());
    }

    @Test
    void deserializeProjectMissingMetadataThrows() {
        String json = "{\"schemaVersion\":1,\"flows\":[]}";
        assertThrows(IllegalArgumentException.class, () -> serializer.deserializeProject(json));
    }

    @Test
    void deserializeProjectUnsupportedVersionThrows() {
        String json = "{\"schemaVersion\":99,\"metadata\":{},\"flows\":[]}";
        assertThrows(IllegalArgumentException.class, () -> serializer.deserializeProject(json));
    }

    @Test
    void deserializeFlowsMissingArrayThrows() {
        String json = "{\"schemaVersion\":1}";
        assertThrows(IllegalArgumentException.class, () -> serializer.deserializeFlows(json));
    }

    @Test
    void deserializeMalformedJsonThrows() {
        assertThrows(IllegalArgumentException.class, () -> serializer.deserializeProject("not json"));
        assertThrows(IllegalArgumentException.class, () -> serializer.deserializeFlows("not json"));
    }

    private static FlowDefinition sampleFlow(String id, String name) {
        FlowStep step = new FlowStep(id + "-step", name + " Step", true, "",
                new StoredRequestTemplate("GET", "https", "example.com", 443,
                        "/path", List.of(), ""),
                List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
        return new FlowDefinition(id, name, "", true,
                List.of(step), FlowRuleSet.empty(),
                AggregationPlan.disabled(), FlowTriggerConfig.none());
    }

    private static AccessPlanSummary summary() {
        return new AccessPlanSummary(1, 1, 1, 0, 0, 0, 0, 1, 0, 0, false, 1L, 2L);
    }

    private static SavedFindingDraft draft(String markdown) {
        return new SavedFindingDraft("case-1", "result-1", markdown, 3L);
    }

    private static List<SessionProfile> sessionProfiles() {
        return List.of(new SessionProfile(
                "session-1",
                "Session User",
                true,
                false,
                SessionScope.custom(List.of()),
                new SessionAuthConfig("flow-1", 3600L, List.of()),
                SessionSchedulerConfig.disabled(),
                new SessionState(Map.of(
                        "accessToken", "session-secret",
                        "plainValue", "plain-session-secret"), 10L, 20L, 30L),
                List.of(new SessionInjectionRule(
                        "inject-1",
                        true,
                        InjectRule.Target.REQUEST_HEADER,
                        "Authorization",
                        "Bearer {{session.accessToken}}",
                        InjectRule.WriteStrategy.OVERWRITE,
                        List.of("PROXY"))),
                List.of(new SessionExtractionRule(
                        "extract-1",
                        true,
                        ExtractRule.SourceSide.RESPONSE,
                        ExtractRule.ExtractorType.HEADER,
                        "X-Token",
                        "accessToken",
                        List.of("PROXY")))));
    }

    private static AccessWorkspace accessWorkspace() {
        AuthProvider authProvider = new AuthProvider(
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
        AuthProfile authProfile = new AuthProfile(
                "auth-1",
                "Owner",
                "admin",
                "manual",
                Map.of("username", "alice", "password", "password-secret"),
                new AuthState(
                        Map.of("accessToken", "access-secret"),
                        Map.of("Authorization", "Bearer access-secret"),
                        Map.of("sessionId", "cookie-secret"),
                        0L,
                        0L),
                true);
        DataProfile dataProfile = DataProfile.enabled(
                "data-1",
                "Owner Data",
                "auth-1",
                Map.of("displayName", "Alice Display", "password", "data-password-secret"));
        AccessRequestTemplate template = new AccessRequestTemplate(
                "template-1",
                "Template",
                new StoredRequestTemplate("GET", "https", "example.test", 443,
                        "/accounts?accessToken=access-secret",
                        List.of("Authorization: Bearer access-secret", "Cookie: sessionId=cookie-secret"),
                        "{\"accessToken\":\"access-secret\"}"),
                java.util.Set.of(),
                OperationMetadata.unknown(),
                "flow-1",
                "step-1",
                "hash-1",
                true);
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
                RunStatus.COMPLETED,
                123L);
        AccessTestResult result = new AccessTestResult(
                "result-1",
                testCase.id(),
                RunStatus.COMPLETED,
                Verdict.OK,
                "",
                1L,
                2L,
                "GET / HTTP/1.1\nAuthorization: Bearer access-secret\n\naccessToken=access-secret",
                "HTTP/1.1 200 OK\nSet-Cookie: sessionId=cookie-secret\n\n{}",
                List.of());
        ScenarioVariable scenarioVariable = new ScenarioVariable(
                "confirmToken",
                "confirm-secret",
                1000L,
                0L,
                false,
                false,
                "flow-1",
                "step-1",
                dataProfile.id());
        ScenarioInstance scenario = new ScenarioInstance(
                "scenario-1",
                "Legacy scenario",
                dataProfile.id(),
                Map.of(scenarioVariable.key(), scenarioVariable),
                dev.nquangit.corestra.access.matrix.ScenarioPolicy.REUSE_EXISTING,
                1000L,
                false,
                "flow-1",
                "step-2");
        return AccessWorkspace.builder()
                .authProvider(authProvider)
                .authProfile(authProfile)
                .dataProfile(dataProfile)
                .template(template)
                .testCase(testCase)
                .result(result)
                .scenario(scenario)
                .build();
    }

    private static AccessEvidence accessEvidence() {
        return new AccessEvidence(
                "case-1",
                "result-1",
                "GET / HTTP/1.1\nAuthorization: Bearer access-secret\n\naccessToken=access-secret",
                "GET / HTTP/1.1\nAuthorization: <redacted>\n\naccessToken=<redacted>",
                "HTTP/1.1 200 OK\nSet-Cookie: sessionId=cookie-secret\n\n{}",
                "HTTP/1.1 200 OK\nSet-Cookie: <redacted>\n\n{}",
                3L);
    }
}
