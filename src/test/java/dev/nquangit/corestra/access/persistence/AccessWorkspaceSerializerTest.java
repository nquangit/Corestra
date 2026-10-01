package dev.nquangit.corestra.access.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthFailureRule;
import dev.nquangit.corestra.access.auth.AuthInjectionLocation;
import dev.nquangit.corestra.access.auth.AuthInjectionMode;
import dev.nquangit.corestra.access.auth.AuthInjectionRule;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.auth.AuthRuleType;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.auth.AuthSuccessRule;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.evaluate.EvaluationProfile;
import dev.nquangit.corestra.access.evaluate.IndicatorType;
import dev.nquangit.corestra.access.evaluate.MaskingRule;
import dev.nquangit.corestra.access.evaluate.ResponseIndicator;
import dev.nquangit.corestra.access.evaluate.SensitiveFieldRule;
import dev.nquangit.corestra.access.importer.ImportFormat;
import dev.nquangit.corestra.access.importer.ImportOptions;
import dev.nquangit.corestra.access.importer.ImportResult;
import dev.nquangit.corestra.access.importer.ImportSource;
import dev.nquangit.corestra.access.importer.PostmanAccessTemplateImporter;
import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.matrix.AssertionType;
import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.matrix.ResponseAssertion;
import dev.nquangit.corestra.access.matrix.RunStatus;
import dev.nquangit.corestra.access.matrix.SetupConfig;
import dev.nquangit.corestra.access.matrix.TargetBinding;
import dev.nquangit.corestra.access.postcheck.PostCheckAssertion;
import dev.nquangit.corestra.access.postcheck.PostCheckAssertionResult;
import dev.nquangit.corestra.access.postcheck.PostCheckAssertionType;
import dev.nquangit.corestra.access.postcheck.PostCheckAuthBinding;
import dev.nquangit.corestra.access.postcheck.PostCheckEvidence;
import dev.nquangit.corestra.access.postcheck.PostCheckRequest;
import dev.nquangit.corestra.access.result.AccessEvidence;
import dev.nquangit.corestra.access.result.AccessTestResult;
import dev.nquangit.corestra.access.result.Verdict;
import dev.nquangit.corestra.access.scenario.ScenarioInstance;
import dev.nquangit.corestra.access.scenario.ScenarioVariable;
import dev.nquangit.corestra.access.security.ExportMode;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.OperationMetadata;
import dev.nquangit.corestra.access.template.OperationType;
import dev.nquangit.corestra.access.teardown.TeardownAuthBinding;
import dev.nquangit.corestra.access.teardown.TeardownConfig;
import dev.nquangit.corestra.access.teardown.TeardownEvidence;
import dev.nquangit.corestra.access.teardown.TeardownMode;
import dev.nquangit.corestra.access.teardown.TeardownStepEvidence;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.VariableScope;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.access.flow.AccessFlowTarget;
import dev.nquangit.corestra.access.flow.FlowInjectionOverride;
import dev.nquangit.corestra.access.flow.FlowInjectionOverrideTarget;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.control.StepConditionActionRule;
import dev.nquangit.corestra.flow.control.StepAction;
import dev.nquangit.corestra.flow.control.StepActionType;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.InjectRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessWorkspaceSerializerTest {
    private static final String REDACTED = "<redacted>";

    private final AccessWorkspaceSerializer serializer = new AccessWorkspaceSerializer();

    @Test
    void safeExportPreservesFlowTargetConditionActions() {
        StepConditionActionRule rule = new StepConditionActionRule("stop", "Stop", true, null,
                List.of(), List.of(new StepAction("break", "Break", true, StepActionType.BREAK_FLOW, Map.of())));
        FlowStep step = new FlowStep("step", "Step", true, "",
                new StoredRequestTemplate("GET", "https", "example.com", 443, "/", List.of(), ""),
                List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW)
                .withConditionActionRules(List.of(rule));
        FlowDefinition flow = new FlowDefinition("flow", "Flow", "", true, List.of(step), null, null, null);
        AccessFlowTarget target = new AccessFlowTarget("target", "Target", flow, "flow", "Flow", "hash",
                "step", true, List.of());
        AccessWorkspaceDocument document = new AccessWorkspaceDocument(
                AccessWorkspace.builder().flowTarget(target).build(), List.of());
        for (ExportMode mode : List.of(ExportMode.SANITIZED_EXPORT, ExportMode.EXCLUDE_SECRETS)) {
            AccessWorkspaceSerializer serializer = new AccessWorkspaceSerializer();
            AccessWorkspaceDocument restored = serializer.deserialize(serializer.serialize(document, mode));
            assertEquals(List.of(rule), restored.workspace().flowTargets().get("target")
                    .flowSnapshot().steps().getFirst().conditionActionRules());
        }
    }

    @Test
    void safeExportRemovesHardcodedSecretsFromRulesOverridesAndCapturedResponses() {
        AuthProvider provider = new AuthProvider(
                "provider", "Provider", "", true, "", "", "",
                List.of(new AuthInjectionRule(
                        "auth-inject", "Authorization", true, AuthInjectionLocation.HEADER,
                        "X-Private-Credential", "auth-rule-secret", AuthInjectionMode.OVERRIDE)),
                List.of(), List.of(), AuthFallbackPolicy.MANUAL_ONLY, AuthRetryPolicy.NO_RETRY);
        InjectRule injectRule = new InjectRule(
                "inject", "Static auth", true, InjectRule.Target.REQUEST_HEADER, "X-Flow-Credential",
                InjectRule.ValueSourceType.STATIC, "", "step-rule-secret",
                InjectRule.WriteStrategy.OVERWRITE);
        String captured = Base64.getEncoder().encodeToString(
                "HTTP/1.1 200 OK\r\nAuthorization: captured-response-secret\r\n\r\n{}"
                        .getBytes(StandardCharsets.ISO_8859_1));
        FlowStep step = new FlowStep(
                "step", "Step", true, "",
                new StoredRequestTemplate("GET", "https", "example.test", 443, "/", List.of(), ""),
                List.of(), List.of(injectRule), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW, captured);
        FlowDefinition flow = new FlowDefinition("flow", "Flow", "", true, List.of(step), null, null, null);
        AccessFlowTarget target = new AccessFlowTarget(
                "target", "Target", flow, "flow", "Flow", "hash", "step", true,
                List.of(new FlowInjectionOverride(
                        "override", "Override", true, FlowInjectionOverrideTarget.RULE_ID,
                        "inject", "override-rule-secret")));
        AccessTestCase testCase = new AccessTestCase(
                "case", "Case", "", "flow", "step", SetupConfig.none(), TargetBinding.empty(),
                ExpectedResult.REVIEW,
                List.of(new ResponseAssertion(
                        "assert", "Token assertion", AssertionType.BODY_CONTAINS,
                        "accessToken", "assertion-secret", true)),
                true, RunStatus.PENDING, 0L);
        AccessWorkspace workspace = AccessWorkspace.builder()
                .authProvider(provider)
                .flowTarget(target)
                .testCase(testCase)
                .build();

        for (ExportMode mode : List.of(ExportMode.SANITIZED_EXPORT, ExportMode.EXCLUDE_SECRETS)) {
            String json = serializer.writeJson(serializer.serialize(workspace, List.of(), mode));
            assertFalse(json.contains("auth-rule-secret"));
            assertFalse(json.contains("step-rule-secret"));
            assertFalse(json.contains("override-rule-secret"));
            assertFalse(json.contains("captured-response-secret"));
            assertFalse(json.contains("assertion-secret"));
        }

        String full = serializer.writeJson(serializer.serialize(workspace, List.of(), ExportMode.FULL_EXPORT));
        assertTrue(full.contains("auth-rule-secret"));
        assertTrue(full.contains("step-rule-secret"));
        assertTrue(full.contains("override-rule-secret"));
        assertTrue(full.contains("assertion-secret"));
    }

    @Test
    void fullExportRoundTripsWorkspaceAndEvidence() {
        AccessWorkspace workspace = sampleWorkspace();
        List<AccessEvidence> evidence = List.of(sampleEvidence());

        ObjectNode serialized = serializer.serialize(workspace, evidence, ExportMode.FULL_EXPORT);
        AccessWorkspaceDocument restored = serializer.deserialize(serialized);

        assertEquals(workspace, restored.workspace());
        assertEquals("Session Auth", restored.workspace().authProviders().get("provider-1").name());
        assertEquals("Business API", restored.workspace().evaluationProfiles().get("eval-1").name());
        assertEquals("eval-1", restored.workspace().testCases().get("case-1").evaluationProfileId());
        assertEquals("post-1", restored.workspace().testCases().get("case-1").postChecks().getFirst().id());
        assertEquals("flow-teardown", restored.workspace().testCases().get("case-1").teardown().teardownFlowId());
        assertEquals(evidence, restored.evidence());
        assertEquals("post-1", restored.evidence().getFirst().postCheckEvidence().getFirst().postCheckId());
        assertEquals("flow-teardown", restored.evidence().getFirst().teardownEvidence().getFirst().flowId());
        assertTrue(restored.evidence().getFirst().renderedRequestRaw().contains("request-access-secret"));
    }

    @Test
    void sanitizedExportRedactsSensitiveValuesAndKeepsSafeValues() {
        ObjectNode serialized = serializer.serialize(
                sampleWorkspace(), List.of(sampleEvidence()), ExportMode.SANITIZED_EXPORT);
        String json = serializer.writeJson(serialized);

        assertDoesNotContainSecrets(json);
        assertTrue(json.contains(REDACTED));
        assertTrue(json.contains("Alice Display"));
        assertTrue(json.contains("trace-1"));

        AccessWorkspaceDocument restored = serializer.deserialize(serialized);
        AuthProvider authProvider = restored.workspace().authProviders().get("provider-1");
        AuthProfile authProfile = restored.workspace().authProfiles().get("auth-1");
        DataProfile dataProfile = restored.workspace().dataProfiles().get("data-1");
        AccessRequestTemplate template = restored.workspace().templates().get("template-1");
        AccessTestResult result = restored.workspace().results().get("result-1");
        ScenarioInstance scenario = restored.workspace().scenarios().get("scenario-1");
        EvaluationProfile evaluationProfile = restored.workspace().evaluationProfiles().get("eval-1");
        AccessEvidence evidence = restored.evidence().getFirst();

        assertEquals("Session Auth", authProvider.name());
        assertEquals("Bearer {{auth.accessToken}}", authProvider.injectionRules().getFirst().valueTemplate());
        assertEquals(AuthFallbackPolicy.REFRESH_THEN_LOGIN, authProvider.fallbackPolicy());
        assertEquals(REDACTED, authProfile.credentials().get("password"));
        assertEquals("alice", authProfile.credentials().get("username"));
        assertEquals(REDACTED, authProfile.state().variables().get("accessToken"));
        assertEquals(REDACTED, authProfile.state().variables().get("refreshToken"));
        assertEquals("Alice Display", dataProfile.values().get("displayName"));
        assertEquals(REDACTED, dataProfile.values().get("accountNumber"));
        assertEquals(REDACTED, dataProfile.values().get("password"));
        assertTrue(template.baseRequest().headerLines().contains("Authorization: " + REDACTED));
        assertTrue(template.baseRequest().headerLines().contains("Cookie: " + REDACTED));
        assertTrue(template.baseRequest().body().contains("\"accessToken\":\"" + REDACTED + "\""));
        assertFalse(result.renderedRequestSummary().contains("summary-access-secret"));
        assertEquals(REDACTED, scenario.variables().get("accessToken").value());
        assertEquals("confirm-safe", scenario.variables().get("confirmToken").value());
        assertEquals("Business API", evaluationProfile.name());
        assertEquals("errorCode", evaluationProfile.denyIndicators().getFirst().selector());
        assertFalse(evidence.renderedRequestRaw().contains("request-access-secret"));
        assertEquals(evidence.renderedRequestRaw(), evidence.redactedRequestRaw());
        assertEquals(evidence.responseRaw(), evidence.redactedResponseRaw());
        assertTrue(evidence.postCheckEvidence().getFirst().redactedRequestRaw().contains(REDACTED));
        assertTrue(evidence.postCheckEvidence().getFirst().redactedResponseRaw().contains(REDACTED));
        assertFalse(evidence.postCheckEvidence().getFirst().redactedRequestRaw().contains("postCheckRequestToken"));
        assertFalse(evidence.postCheckEvidence().getFirst().redactedResponseRaw().contains("postCheckResponseToken"));
        assertTrue(evidence.teardownEvidence().getFirst().steps().getFirst().redactedRequestRaw().contains(REDACTED));
        assertTrue(evidence.teardownEvidence().getFirst().steps().getFirst().redactedResponseRaw().contains(REDACTED));
        assertFalse(evidence.teardownEvidence().getFirst().steps().getFirst().redactedRequestRaw().contains("teardownRequestToken"));
        assertFalse(evidence.teardownEvidence().getFirst().steps().getFirst().redactedResponseRaw().contains("teardownResponseToken"));
    }

    @Test
    void sanitizedExportRedactsSensitiveHeadersFromImportedTemplates() {
        PostmanAccessTemplateImporter importer = new PostmanAccessTemplateImporter(prefix -> prefix + "-import");
        ImportResult imported = importer.importTemplates(new ImportSource("collection.json", """
                {
                  "item": [
                    {
                      "name": "Sensitive Import",
                      "request": {
                        "method": "GET",
                        "header": [
                          {"key": "Authorization", "value": "Bearer import-secret"},
                          {"key": "Cookie", "value": "sessionId=import-cookie-secret"}
                        ],
                        "url": "https://api.example.test/accounts"
                      }
                    }
                  ]
                }
                """, ImportFormat.POSTMAN_COLLECTION_JSON), ImportOptions.defaults());
        AccessWorkspace workspace = AccessWorkspace.builder()
                .template(imported.templates().getFirst())
                .build();

        ObjectNode serialized = serializer.serialize(workspace, List.of(), ExportMode.SANITIZED_EXPORT);
        String json = serializer.writeJson(serialized);
        AccessRequestTemplate restored = serializer.deserialize(serialized).workspace().templates().get("tmpl-import");

        assertFalse(json.contains("import-secret"));
        assertFalse(json.contains("import-cookie-secret"));
        assertTrue(restored.baseRequest().headerLines().contains("Authorization: " + REDACTED));
        assertTrue(restored.baseRequest().headerLines().contains("Cookie: " + REDACTED));
    }

    @Test
    void excludeSecretsStripsCredentialsAuthStateAndEvidence() {
        ObjectNode serialized = serializer.serialize(
                sampleWorkspace(), List.of(sampleEvidence()), ExportMode.EXCLUDE_SECRETS);
        String json = serializer.writeJson(serialized);

        assertDoesNotContainSecrets(json);
        AccessWorkspaceDocument restored = serializer.deserialize(serialized);
        AuthProvider authProvider = restored.workspace().authProviders().get("provider-1");
        AuthProfile authProfile = restored.workspace().authProfiles().get("auth-1");
        DataProfile dataProfile = restored.workspace().dataProfiles().get("data-1");
        AccessTestResult result = restored.workspace().results().get("result-1");
        ScenarioInstance scenario = restored.workspace().scenarios().get("scenario-1");
        EvaluationProfile evaluationProfile = restored.workspace().evaluationProfiles().get("eval-1");

        assertEquals("Session Auth", authProvider.name());
        assertEquals("Bearer {{auth.accessToken}}", authProvider.injectionRules().getFirst().valueTemplate());
        assertEquals(Map.of(), authProfile.credentials());
        assertEquals(Map.of(), authProfile.state().variables());
        assertEquals(Map.of(), authProfile.state().headers());
        assertEquals(Map.of(), authProfile.state().cookies());
        assertEquals("Alice Display", dataProfile.values().get("displayName"));
        assertFalse(dataProfile.values().containsKey("accountNumber"));
        assertFalse(dataProfile.values().containsKey("password"));
        assertEquals("", scenario.variables().get("accessToken").value());
        assertEquals("confirm-safe", scenario.variables().get("confirmToken").value());
        assertEquals("Business API", evaluationProfile.name());
        assertEquals("", result.renderedRequestSummary());
        assertEquals("", result.responseSummary());
        assertEquals(List.of(), restored.evidence());
    }

    @Test
    void missingAccessSectionDeserializesAsEmptyWorkspace() {
        AccessWorkspaceDocument restored = serializer.deserialize(null);

        assertEquals(AccessWorkspace.empty(), restored.workspace());
        assertEquals(List.of(), restored.evidence());
    }

    @Test
    void unknownAccessFieldsAreIgnored() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode node = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "unknownFutureField": "ignored",
                  "authProfiles": {
                    "auth-1": {
                      "id": "auth-1",
                      "name": "Owner",
                      "role": "admin",
                      "providerId": "manual",
                      "credentials": {},
                      "state": {},
                      "enabled": true,
                      "futureAuthField": "ignored"
                    }
                  },
                  "dataProfiles": {},
                  "templates": {},
                  "testCases": {},
                  "results": {},
                  "evidence": []
                }
                """);

        AccessWorkspaceDocument restored = serializer.deserialize(node);

        assertTrue(restored.workspace().authProviders().isEmpty());
        assertTrue(restored.workspace().scenarios().isEmpty());
        assertEquals("Owner", restored.workspace().authProfiles().get("auth-1").name());
        assertEquals(List.of(), restored.evidence());
    }

    @Test
    void oldAccessDocumentWithoutScenariosLoadsEmptyScenarios() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode node = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "authProviders": {},
                  "authProfiles": {},
                  "dataProfiles": {},
                  "templates": {},
                  "testCases": {},
                  "results": {},
                  "evidence": []
                }
                """);

        AccessWorkspaceDocument restored = serializer.deserialize(node);

        assertTrue(restored.workspace().scenarios().isEmpty());
        assertTrue(restored.workspace().evaluationProfiles().isEmpty());
    }

    @Test
    void oldAccessDocumentWithoutPostChecksLoadsEmptyPostChecks() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode node = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "authProviders": {},
                  "authProfiles": {},
                  "dataProfiles": {},
                  "templates": {},
                  "testCases": {
                    "case-1": {
                      "id": "case-1",
                      "name": "Old Case",
                      "templateId": "template-1",
                      "flowId": "",
                      "targetStepId": "",
                      "setup": null,
                      "target": null,
                      "expected": "REVIEW",
                      "assertions": [],
                      "enabled": true,
                      "status": "PENDING",
                      "lastRunAtEpochMillis": 0
                    }
                  },
                  "results": {},
                  "evidence": []
                }
                """);

        AccessWorkspaceDocument restored = serializer.deserialize(node);

        assertTrue(restored.workspace().testCases().get("case-1").postChecks().isEmpty());
        assertEquals(TeardownMode.NONE, restored.workspace().testCases().get("case-1").teardown().mode());
    }

    @Test
    void encryptedExportIsRejectedUntilImplemented() {
        assertThrows(IllegalArgumentException.class,
                () -> serializer.serialize(AccessWorkspace.empty(), List.of(), ExportMode.ENCRYPTED_EXPORT));
    }

    private static AccessWorkspace sampleWorkspace() {
        AuthProvider authProvider = new AuthProvider(
                "provider-1",
                "Session Auth",
                "Uses flow references only",
                true,
                "login-flow",
                "refresh-flow",
                "validate-flow",
                List.of(new AuthInjectionRule(
                        "inj-1",
                        "Bearer token",
                        true,
                        AuthInjectionLocation.HEADER,
                        "Authorization",
                        "Bearer {{auth.accessToken}}",
                        AuthInjectionMode.OVERRIDE)),
                List.of(new AuthSuccessRule(
                        "success-1",
                        "HTTP 200",
                        true,
                        AuthRuleType.STATUS_CODE,
                        "200",
                        "")),
                List.of(new AuthFailureRule(
                        "failure-1",
                        "HTTP 401",
                        true,
                        AuthRuleType.STATUS_CODE,
                        "401",
                        "")),
                AuthFallbackPolicy.REFRESH_THEN_LOGIN,
                AuthRetryPolicy.REFRESH_AND_RETRY_ONCE);
        AuthState state = new AuthState(
                Map.of("accessToken", "state-access-secret", "refreshToken", "refresh-secret"),
                Map.of("Authorization", "Bearer header-secret", "X-Trace", "trace-1"),
                Map.of("sessionId", "cookie-secret"),
                5000L,
                4000L);
        AuthProfile authProfile = new AuthProfile(
                "auth-1",
                "Owner",
                "admin",
                "manual",
                Map.of("username", "alice", "password", "password-secret"),
                state,
                true);
        DataProfile dataProfile = DataProfile.enabled(
                "data-1",
                "Owner Data",
                "auth-1",
                Map.of(
                        "displayName", "Alice Display",
                        "accountNumber", "acct-secret",
                        "password", "data-password-secret"));
        AccessRequestTemplate template = new AccessRequestTemplate(
                "template-1",
                "Transfer",
                new StoredRequestTemplate(
                        "POST",
                        "https",
                        "example.test",
                        443,
                        "/transfer?accessToken=query-secret",
                        List.of(
                                "Authorization: Bearer template-header-secret",
                                "Cookie: sessionId=template-cookie-secret",
                                "X-Trace: trace-1"),
                        "{\"accessToken\":\"template-body-secret\",\"displayName\":\"Alice Display\"}"),
                Set.of(new ScopedVariableRef(VariableScope.AUTH, "accessToken")),
                new OperationMetadata(OperationType.STATE_CHANGING, true, false, "Transfer"),
                "flow-1",
                "step-1",
                "hash-1",
                true);
        EvaluationProfile evaluationProfile = new EvaluationProfile(
                "eval-1",
                "Business API",
                true,
                List.of(new ResponseIndicator(
                        "allow-1",
                        "Success flag",
                        true,
                        IndicatorType.JSON_PATH_EQUALS,
                        "success",
                        "true")),
                List.of(new ResponseIndicator(
                        "deny-1",
                        "Access denied code",
                        true,
                        IndicatorType.JSON_PATH_EQUALS,
                        "errorCode",
                        "ACCESS_DENIED")),
                List.of(new SensitiveFieldRule(
                        "sensitive-1",
                        "Secret token",
                        true,
                        "data.secretToken")),
                List.of(new MaskingRule(
                        "mask-1",
                        "Masked secret token",
                        true,
                        "data.secretToken",
                        "\\*+")));
        AccessTestCase testCase = new AccessTestCase(
                "case-1",
                "Owner can transfer",
                "template-1",
                "flow-1",
                "step-1",
                SetupConfig.none(),
                new TargetBinding("auth-1", "data-1", "env-1", ""),
                ExpectedResult.ALLOW,
                evaluationProfile.id(),
                List.of(new ResponseAssertion(
                        "assert-1",
                        "Status",
                        AssertionType.STATUS_CODE,
                        "status",
                        "200",
                        true)),
                List.of(new PostCheckRequest(
                        "post-1",
                        "Check balance",
                        true,
                        "template-1",
                        "",
                        PostCheckAuthBinding.DATA_OWNER,
                        "",
                        "",
                        List.of(new PostCheckAssertion(
                                "post-assert-1",
                                "Balance unchanged",
                                true,
                                PostCheckAssertionType.JSON_PATH_EQUALS,
                                "balance",
                                "100",
                                false)))),
                new TeardownConfig(
                        TeardownMode.RUN_FLOW,
                        "flow-teardown",
                        TeardownAuthBinding.DATA_OWNER,
                        "",
                        true,
                        true,
                        false),
                true,
                RunStatus.COMPLETED,
                9000L);
        AccessTestResult result = new AccessTestResult(
                "result-1",
                "case-1",
                RunStatus.COMPLETED,
                Verdict.OK,
                "accessToken=reason-secret",
                10L,
                20L,
                "GET / HTTP/1.1\nAuthorization: Bearer summary-access-secret\n\naccessToken=summary-body-secret",
                "HTTP/1.1 200 OK\nSet-Cookie: sessionId=response-cookie-secret\n\n{\"refreshToken\":\"response-refresh-secret\"}",
                List.of("accessToken=warning-secret"));
        ScenarioVariable scenarioAccessToken = new ScenarioVariable(
                "accessToken",
                "scenario-access-secret",
                1000L,
                0L,
                false,
                false,
                "flow-setup",
                "step-2",
                "data-1");
        ScenarioVariable confirmToken = new ScenarioVariable(
                "confirmToken",
                "confirm-safe",
                1000L,
                0L,
                false,
                false,
                "flow-setup",
                "step-2",
                "data-1");
        ScenarioInstance scenario = new ScenarioInstance(
                "scenario-1",
                "Transfer setup",
                "data-1",
                Map.of(scenarioAccessToken.key(), scenarioAccessToken, confirmToken.key(), confirmToken),
                dev.nquangit.corestra.access.matrix.ScenarioPolicy.REUSE_EXISTING,
                1000L,
                false,
                "flow-setup",
                "step-3");

        return AccessWorkspace.builder()
                .authProvider(authProvider)
                .authProfile(authProfile)
                .dataProfile(dataProfile)
                .template(template)
                .evaluationProfile(evaluationProfile)
                .testCase(testCase)
                .result(result)
                .scenario(scenario)
                .build();
    }

    private static AccessEvidence sampleEvidence() {
        return new AccessEvidence(
                "case-1",
                "result-1",
                "GET / HTTP/1.1\nAuthorization: Bearer request-access-secret\n\naccessToken=request-body-secret",
                "GET / HTTP/1.1\nAuthorization: <redacted>\n\naccessToken=<redacted>",
                "HTTP/1.1 200 OK\nSet-Cookie: sessionId=response-secret\n\nrefreshToken=response-body-secret",
                "HTTP/1.1 200 OK\nSet-Cookie: <redacted>\n\nrefreshToken=<redacted>",
                List.of(new PostCheckEvidence(
                        "post-1",
                        "Check balance",
                        "GET /balance HTTP/1.1\n\naccessToken=postCheckRequestToken",
                        "HTTP/1.1 200 OK\n\naccessToken=postCheckResponseToken",
                        Verdict.OK,
                        "Post-check response used accessToken=postCheckResponseToken",
                        List.of(new PostCheckAssertionResult(
                                "post-assert-1",
                                "Balance unchanged",
                                true,
                                "Expected accessToken=postCheckResponseToken to stay masked")),
                        30L)),
                List.of(new TeardownEvidence(
                        "flow-teardown",
                        "Cleanup Flow",
                        true,
                        "Teardown used accessToken=teardownReasonToken",
                        List.of(new TeardownStepEvidence(
                                "cleanup-step",
                                "DELETE /cleanup HTTP/1.1\nAuthorization: Bearer teardownRequestToken\n\n",
                                "HTTP/1.1 200 OK\n\naccessToken=teardownResponseToken",
                                200,
                                true,
                                "Step used accessToken=teardownStepToken",
                                31L)),
                        31L)),
                30L);
    }

    private static void assertDoesNotContainSecrets(String json) {
        assertFalse(json.contains("state-access-secret"));
        assertFalse(json.contains("refresh-secret"));
        assertFalse(json.contains("password-secret"));
        assertFalse(json.contains("data-password-secret"));
        assertFalse(json.contains("header-secret"));
        assertFalse(json.contains("cookie-secret"));
        assertFalse(json.contains("query-secret"));
        assertFalse(json.contains("template-header-secret"));
        assertFalse(json.contains("template-cookie-secret"));
        assertFalse(json.contains("template-body-secret"));
        assertFalse(json.contains("summary-access-secret"));
        assertFalse(json.contains("summary-body-secret"));
        assertFalse(json.contains("response-refresh-secret"));
        assertFalse(json.contains("request-access-secret"));
        assertFalse(json.contains("request-body-secret"));
        assertFalse(json.contains("response-secret"));
        assertFalse(json.contains("response-body-secret"));
        assertFalse(json.contains("warning-secret"));
        assertFalse(json.contains("reason-secret"));
        assertFalse(json.contains("scenario-access-secret"));
        assertFalse(json.contains("postCheckRequestToken"));
        assertFalse(json.contains("postCheckResponseToken"));
        assertFalse(json.contains("teardownRequestToken"));
        assertFalse(json.contains("teardownResponseToken"));
        assertFalse(json.contains("teardownReasonToken"));
        assertFalse(json.contains("teardownStepToken"));
    }
}
