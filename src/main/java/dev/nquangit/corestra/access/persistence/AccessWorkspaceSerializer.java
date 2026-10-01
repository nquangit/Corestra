package dev.nquangit.corestra.access.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthFailureRule;
import dev.nquangit.corestra.access.auth.AuthInjectionRule;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.auth.AuthSuccessRule;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.evaluate.EvaluationProfile;
import dev.nquangit.corestra.access.flow.AccessFlowTarget;
import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.matrix.ResponseAssertion;
import dev.nquangit.corestra.access.postcheck.PostCheckAssertion;
import dev.nquangit.corestra.access.postcheck.PostCheckRequest;
import dev.nquangit.corestra.access.postcheck.PostCheckAssertionResult;
import dev.nquangit.corestra.access.postcheck.PostCheckEvidence;
import dev.nquangit.corestra.access.report.SavedFindingDraft;
import dev.nquangit.corestra.access.result.AccessEvidence;
import dev.nquangit.corestra.access.result.AccessTestResult;
import dev.nquangit.corestra.access.result.FlowStepEvidence;
import dev.nquangit.corestra.access.runner.AccessPlanSummary;
import dev.nquangit.corestra.access.scenario.ScenarioInstance;
import dev.nquangit.corestra.access.scenario.ScenarioVariable;
import dev.nquangit.corestra.access.security.ExportMode;
import dev.nquangit.corestra.access.security.RedactionService;
import dev.nquangit.corestra.access.teardown.TeardownEvidence;
import dev.nquangit.corestra.access.teardown.TeardownStepEvidence;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.persistence.FlowExportSanitizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Serializes and deserializes access-testing workspace data for project files.
 */
public final class AccessWorkspaceSerializer {
    private static final int CURRENT_SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;
    private final RedactionService redactionService;
    private final FlowExportSanitizer flowExportSanitizer;
    private final JavaType authProviderMapType;
    private final JavaType authProfileMapType;
    private final JavaType dataProfileMapType;
    private final JavaType templateMapType;
    private final JavaType flowTargetMapType;
    private final JavaType testCaseMapType;
    private final JavaType resultMapType;
    private final JavaType scenarioMapType;
    private final JavaType evaluationProfileMapType;
    private final JavaType evidenceListType;
    private final JavaType reportDraftMapType;

    /**
     * Creates a serializer with the default redaction service.
     */
    public AccessWorkspaceSerializer() {
        this(new RedactionService());
    }

    /**
     * Creates a serializer.
     *
     * @param redactionService redaction service used by safe export modes
     */
    public AccessWorkspaceSerializer(RedactionService redactionService) {
        this.redactionService = Objects.requireNonNull(redactionService, "redactionService");
        this.flowExportSanitizer = new FlowExportSanitizer(redactionService);
        this.objectMapper = JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();
        this.authProviderMapType = mapType(AuthProvider.class);
        this.authProfileMapType = mapType(AuthProfile.class);
        this.dataProfileMapType = mapType(DataProfile.class);
        this.templateMapType = mapType(AccessRequestTemplate.class);
        this.flowTargetMapType = mapType(AccessFlowTarget.class);
        this.testCaseMapType = mapType(AccessTestCase.class);
        this.resultMapType = mapType(AccessTestResult.class);
        this.scenarioMapType = mapType(ScenarioInstance.class);
        this.evaluationProfileMapType = mapType(EvaluationProfile.class);
        this.evidenceListType = objectMapper.getTypeFactory()
                .constructCollectionType(List.class, AccessEvidence.class);
        this.reportDraftMapType = mapType(SavedFindingDraft.class);
    }

    /**
     * Serializes access workspace data into a project-file access section.
     *
     * @param workspace access workspace snapshot
     * @param evidence optional evidence entries
     * @param exportMode selected export mode
     * @return JSON object containing access workspace data
     */
    public ObjectNode serialize(AccessWorkspace workspace, List<AccessEvidence> evidence, ExportMode exportMode) {
        return serialize(new AccessWorkspaceDocument(workspace, evidence), exportMode);
    }

    /**
     * Serializes access project data into a project-file access section.
     *
     * @param document access project document
     * @param exportMode selected export mode
     * @return JSON object containing access project data
     */
    public ObjectNode serialize(AccessWorkspaceDocument document, ExportMode exportMode) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(exportMode, "exportMode");
        if (exportMode == ExportMode.ENCRYPTED_EXPORT) {
            throw new IllegalArgumentException("Encrypted access export is not implemented");
        }

        AccessWorkspace exportWorkspace = workspaceForMode(document.workspace(), exportMode);
        List<AccessEvidence> exportEvidence = evidenceForMode(document.evidence(), exportMode);
        Map<String, SavedFindingDraft> exportReportDrafts = reportDraftsForMode(document.reportDrafts(), exportMode);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", CURRENT_SCHEMA_VERSION);
        root.set("authProviders", objectMapper.valueToTree(exportWorkspace.authProviders()));
        root.set("authProfiles", objectMapper.valueToTree(exportWorkspace.authProfiles()));
        root.set("dataProfiles", objectMapper.valueToTree(exportWorkspace.dataProfiles()));
        root.set("templates", objectMapper.valueToTree(exportWorkspace.templates()));
        root.set("flowTargets", objectMapper.valueToTree(exportWorkspace.flowTargets()));
        root.set("testCases", objectMapper.valueToTree(exportWorkspace.testCases()));
        root.set("results", objectMapper.valueToTree(exportWorkspace.results()));
        root.set("scenarios", objectMapper.valueToTree(exportWorkspace.scenarios()));
        root.set("evaluationProfiles", objectMapper.valueToTree(exportWorkspace.evaluationProfiles()));
        root.set("evidence", objectMapper.valueToTree(exportEvidence));
        root.set("latestSummary", objectMapper.valueToTree(document.latestSummary().orElse(null)));
        root.set("reportDrafts", objectMapper.valueToTree(exportReportDrafts));
        return root;
    }

    /**
     * Deserializes a project-file access section.
     *
     * @param accessNode access section node, or missing/null for an empty workspace
     * @return deserialized access workspace document
     */
    public AccessWorkspaceDocument deserialize(JsonNode accessNode) {
        if (accessNode == null || accessNode.isMissingNode() || accessNode.isNull()) {
            return AccessWorkspaceDocument.empty();
        }
        if (!accessNode.isObject()) {
            throw new IllegalArgumentException("Project access section must be a JSON object");
        }

        try {
            AccessWorkspace workspace = new AccessWorkspace(
                    readMap(accessNode.get("authProviders"), authProviderMapType),
                    readMap(accessNode.get("authProfiles"), authProfileMapType),
                    readMap(accessNode.get("dataProfiles"), dataProfileMapType),
                    readMap(accessNode.get("templates"), templateMapType),
                    readMap(accessNode.get("flowTargets"), flowTargetMapType),
                    readMap(accessNode.get("testCases"), testCaseMapType),
                    readMap(accessNode.get("results"), resultMapType),
                    readMap(accessNode.get("scenarios"), scenarioMapType),
                    readMap(accessNode.get("evaluationProfiles"), evaluationProfileMapType));
            List<AccessEvidence> evidence = readEvidence(accessNode.get("evidence"));
            AccessPlanSummary latestSummary = readLatestSummary(accessNode.get("latestSummary"));
            Map<String, SavedFindingDraft> reportDrafts = readMap(accessNode.get("reportDrafts"), reportDraftMapType);
            return new AccessWorkspaceDocument(workspace, evidence, latestSummary, reportDrafts);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Failed to parse access workspace: " + exception.getMessage(), exception);
        }
    }

    private JavaType mapType(Class<?> valueType) {
        return objectMapper.getTypeFactory()
                .constructMapType(LinkedHashMap.class, String.class, valueType);
    }

    @SuppressWarnings("unchecked")
    private <T> Map<String, T> readMap(JsonNode node, JavaType mapType) throws IllegalArgumentException {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("access workspace map fields must be JSON objects");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, T>) objectMapper.convertValue(node, mapType)));
    }

    private List<AccessEvidence> readEvidence(JsonNode node) throws IllegalArgumentException {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("access evidence must be a JSON array");
        }
        return List.copyOf(objectMapper.convertValue(node, evidenceListType));
    }

    private AccessPlanSummary readLatestSummary(JsonNode node) throws IllegalArgumentException {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("access latestSummary must be a JSON object");
        }
        return objectMapper.convertValue(node, AccessPlanSummary.class);
    }

    private AccessWorkspace workspaceForMode(AccessWorkspace workspace, ExportMode exportMode) {
        return switch (exportMode) {
            case FULL_EXPORT -> workspace;
            case SANITIZED_EXPORT -> transformWorkspace(workspace, false);
            case EXCLUDE_SECRETS -> transformWorkspace(workspace, true);
            case ENCRYPTED_EXPORT -> throw new IllegalArgumentException("Encrypted access export is not implemented");
        };
    }

    private AccessWorkspace transformWorkspace(AccessWorkspace workspace, boolean excludeSecrets) {
        AccessWorkspace.Builder builder = AccessWorkspace.builder();
        workspace.authProviders().values().forEach(provider ->
                builder.authProvider(transformAuthProvider(provider, excludeSecrets)));
        workspace.authProfiles().values().forEach(profile -> builder.authProfile(transformAuthProfile(profile, excludeSecrets)));
        workspace.dataProfiles().values().forEach(profile -> builder.dataProfile(transformDataProfile(profile, excludeSecrets)));
        workspace.templates().values().forEach(template ->
                builder.template(transformTemplate(template, excludeSecrets)));
        workspace.flowTargets().values().forEach(flowTarget ->
                builder.flowTarget(transformFlowTarget(flowTarget, excludeSecrets)));
        workspace.testCases().values().forEach(testCase ->
                builder.testCase(transformTestCase(testCase, excludeSecrets)));
        workspace.results().values().forEach(result -> builder.result(transformResult(result, excludeSecrets)));
        workspace.scenarios().values().forEach(scenario -> builder.scenario(transformScenario(scenario, excludeSecrets)));
        workspace.evaluationProfiles().values().forEach(builder::evaluationProfile);
        return builder.build();
    }

    private AuthProvider transformAuthProvider(AuthProvider provider, boolean excludeSecrets) {
        return new AuthProvider(
                provider.id(), provider.name(), redactionService.redactInlineSecrets(provider.description()),
                provider.enabled(), provider.loginFlowId(), provider.refreshFlowId(), provider.validateFlowId(),
                provider.injectionRules().stream()
                        .map(rule -> transformAuthInjectionRule(rule, excludeSecrets)).toList(),
                provider.successRules().stream()
                        .map(rule -> transformAuthSuccessRule(rule, excludeSecrets)).toList(),
                provider.failureRules().stream()
                        .map(rule -> transformAuthFailureRule(rule, excludeSecrets)).toList(),
                provider.fallbackPolicy(), provider.targetRetryPolicy(), provider.stateTtlSeconds());
    }

    private AuthInjectionRule transformAuthInjectionRule(AuthInjectionRule rule, boolean excludeSecrets) {
        return new AuthInjectionRule(
                rule.id(), rule.name(), rule.enabled(), rule.location(), rule.target(),
                flowExportSanitizer.sanitizeConfiguredValue(
                        rule.target(), rule.valueTemplate(), excludeSecrets, true),
                rule.mode());
    }

    private AuthSuccessRule transformAuthSuccessRule(AuthSuccessRule rule, boolean excludeSecrets) {
        return new AuthSuccessRule(
                rule.id(), rule.name(), rule.enabled(), rule.type(), rule.expression(),
                flowExportSanitizer.sanitizeConfiguredValue(
                        rule.expression(), rule.expectedValue(), excludeSecrets, false));
    }

    private AuthFailureRule transformAuthFailureRule(AuthFailureRule rule, boolean excludeSecrets) {
        return new AuthFailureRule(
                rule.id(), rule.name(), rule.enabled(), rule.type(), rule.expression(),
                flowExportSanitizer.sanitizeConfiguredValue(
                        rule.expression(), rule.expectedValue(), excludeSecrets, false));
    }

    private AuthProfile transformAuthProfile(AuthProfile profile, boolean excludeSecrets) {
        AuthState sourceState = profile.state();
        AuthState state = excludeSecrets
                ? new AuthState(Map.of(), Map.of(), Map.of(),
                sourceState.expiresAtEpochMillis(), sourceState.lastUpdatedAtEpochMillis())
                : new AuthState(
                redactionService.redactMap(sourceState.variables()),
                redactionService.redactMap(sourceState.headers()),
                redactionService.redactMap(sourceState.cookies()),
                sourceState.expiresAtEpochMillis(),
                sourceState.lastUpdatedAtEpochMillis());
        Map<String, String> credentials = excludeSecrets
                ? Map.of()
                : redactionService.redactMap(profile.credentials());
        return new AuthProfile(profile.id(), profile.name(), profile.role(), profile.providerId(),
                credentials, state, profile.enabled());
    }

    private DataProfile transformDataProfile(DataProfile profile, boolean excludeSecrets) {
        Map<String, String> values = excludeSecrets
                ? excludeSensitiveValues(profile.values())
                : redactionService.redactMap(profile.values());
        return new DataProfile(profile.id(), profile.name(), profile.ownerAuthProfileId(),
                values, profile.enabled());
    }

    private Map<String, String> excludeSensitiveValues(Map<String, String> values) {
        if (values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> retained = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!redactionService.isSensitiveKey(key)) {
                retained.put(key, value);
            }
        });
        return Map.copyOf(retained);
    }

    private AccessRequestTemplate transformTemplate(AccessRequestTemplate template, boolean excludeSecrets) {
        return new AccessRequestTemplate(
                template.id(),
                template.name(),
                flowExportSanitizer.sanitizeRequest(template.baseRequest(), excludeSecrets),
                template.placeholders(),
                template.operationMetadata(),
                template.sourceFlowId(),
                template.sourceStepId(),
                template.sourceRequestHash(),
                template.enabled());
    }

    private AccessFlowTarget transformFlowTarget(AccessFlowTarget flowTarget, boolean excludeSecrets) {
        return new AccessFlowTarget(
                flowTarget.id(),
                flowTarget.name(),
                flowExportSanitizer.sanitizeFlow(flowTarget.flowSnapshot(), excludeSecrets),
                flowTarget.sourceFlowId(),
                flowTarget.sourceFlowName(),
                flowTarget.sourceFlowHash(),
                flowTarget.targetStepId(),
                flowTarget.runMode(),
                flowTarget.enabled(),
                flowTarget.injectionOverrides().stream()
                        .map(override -> new dev.nquangit.corestra.access.flow.FlowInjectionOverride(
                                override.id(), override.name(), override.enabled(), override.targetType(),
                                override.targetKey(), flowExportSanitizer.sanitizeConfiguredValue(
                                        override.targetKey(), override.valueTemplate(), excludeSecrets, true)))
                        .toList());
    }

    private AccessTestCase transformTestCase(AccessTestCase testCase, boolean excludeSecrets) {
        return new AccessTestCase(
                testCase.id(), testCase.name(), testCase.targetType(), testCase.templateId(),
                testCase.flowTargetId(), testCase.flowId(), testCase.targetStepId(), testCase.setup(),
                testCase.target(), testCase.expected(), testCase.evaluationProfileId(),
                testCase.assertions().stream()
                        .map(rule -> transformResponseAssertion(rule, excludeSecrets)).toList(),
                testCase.postChecks().stream()
                        .map(check -> transformPostCheck(check, excludeSecrets)).toList(),
                testCase.teardown(), testCase.enabled(), testCase.status(), testCase.lastRunAtEpochMillis());
    }

    private ResponseAssertion transformResponseAssertion(ResponseAssertion rule, boolean excludeSecrets) {
        return new ResponseAssertion(
                rule.id(), rule.name(), rule.type(), rule.expression(),
                flowExportSanitizer.sanitizeConfiguredValue(
                        rule.expression(), rule.expectedValue(), excludeSecrets, false),
                rule.enabled());
    }

    private PostCheckRequest transformPostCheck(PostCheckRequest check, boolean excludeSecrets) {
        return new PostCheckRequest(
                check.id(), check.name(), check.enabled(), check.templateId(), check.authProfileId(),
                check.authBinding(), check.dataProfileId(), check.scenarioInstanceId(),
                check.assertions().stream()
                        .map(rule -> transformPostCheckAssertion(rule, excludeSecrets)).toList());
    }

    private PostCheckAssertion transformPostCheckAssertion(PostCheckAssertion rule, boolean excludeSecrets) {
        return new PostCheckAssertion(
                rule.id(), rule.name(), rule.enabled(), rule.type(), rule.expression(),
                flowExportSanitizer.sanitizeConfiguredValue(
                        rule.expression(), rule.expectedValue(), excludeSecrets, false),
                rule.negate());
    }

    private AccessTestResult transformResult(AccessTestResult result, boolean excludeSecrets) {
        return new AccessTestResult(
                result.id(),
                result.testCaseId(),
                result.runStatus(),
                result.verdict(),
                redactionService.redactInlineSecrets(result.reason()),
                result.startedAtEpochMillis(),
                result.finishedAtEpochMillis(),
                excludeSecrets ? "" : redactionService.redactHttpMessage(result.renderedRequestSummary()),
                excludeSecrets ? "" : redactionService.redactHttpMessage(result.responseSummary()),
                transformWarnings(result.warnings()));
    }

    private ScenarioInstance transformScenario(ScenarioInstance scenario, boolean excludeSecrets) {
        LinkedHashMap<String, ScenarioVariable> variables = new LinkedHashMap<>();
        scenario.variables().forEach((key, variable) -> {
            String value = transformScenarioValue(key, variable.value(), excludeSecrets);
            ScenarioVariable transformed = new ScenarioVariable(
                    variable.key(),
                    value,
                    variable.createdAtEpochMillis(),
                    variable.expiresAtEpochMillis(),
                    variable.consumable(),
                    variable.consumed(),
                    variable.producedByFlowId(),
                    variable.producedByStepId(),
                    variable.ownerDataProfileId());
            variables.put(transformed.key(), transformed);
        });
        return new ScenarioInstance(
                scenario.id(),
                scenario.name(),
                scenario.ownerDataProfileId(),
                variables,
                scenario.policy(),
                scenario.createdAtEpochMillis(),
                scenario.consumed(),
                scenario.producedByFlowId(),
                scenario.producedByUntilStepId());
    }

    private String transformScenarioValue(String key, String value, boolean excludeSecrets) {
        if (!redactionService.isSensitiveKey(key)) {
            return value;
        }
        return excludeSecrets ? "" : redactionService.redactValue(key, value);
    }

    private List<String> transformWarnings(List<String> warnings) {
        if (warnings.isEmpty()) {
            return List.of();
        }
        List<String> redacted = new ArrayList<>();
        warnings.forEach(warning -> redacted.add(redactionService.redactInlineSecrets(warning)));
        return List.copyOf(redacted);
    }

    private List<AccessEvidence> evidenceForMode(List<AccessEvidence> evidence, ExportMode exportMode) {
        return switch (exportMode) {
            case FULL_EXPORT -> List.copyOf(evidence);
            case SANITIZED_EXPORT -> evidence.stream()
                    .map(this::sanitizeEvidence)
                    .toList();
            case EXCLUDE_SECRETS -> List.of();
            case ENCRYPTED_EXPORT -> throw new IllegalArgumentException("Encrypted access export is not implemented");
        };
    }

    private Map<String, SavedFindingDraft> reportDraftsForMode(
            Map<String, SavedFindingDraft> drafts,
            ExportMode exportMode) {
        return switch (exportMode) {
            case FULL_EXPORT -> Map.copyOf(drafts);
            case SANITIZED_EXPORT -> transformReportDrafts(drafts, false);
            case EXCLUDE_SECRETS -> transformReportDrafts(drafts, true);
            case ENCRYPTED_EXPORT -> throw new IllegalArgumentException("Encrypted access export is not implemented");
        };
    }

    private Map<String, SavedFindingDraft> transformReportDrafts(
            Map<String, SavedFindingDraft> drafts,
            boolean excludeSecrets) {
        if (drafts.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, SavedFindingDraft> transformed = new LinkedHashMap<>();
        drafts.forEach((testCaseId, draft) -> transformed.put(testCaseId, new SavedFindingDraft(
                draft.testCaseId(),
                draft.resultId(),
                excludeSecrets ? "" : redactionService.redactInlineSecrets(draft.markdown()),
                draft.updatedAtEpochMillis())));
        return Map.copyOf(transformed);
    }

    private AccessEvidence sanitizeEvidence(AccessEvidence evidence) {
        String request = preferredRedactedMessage(evidence.redactedRequestRaw(), evidence.renderedRequestRaw());
        String response = preferredRedactedMessage(evidence.redactedResponseRaw(), evidence.responseRaw());
        return new AccessEvidence(
                evidence.testCaseId(),
                evidence.resultId(),
                request,
                request,
                response,
                response,
                sanitizeFlowStepEvidence(evidence.flowStepEvidence()),
                sanitizePostCheckEvidence(evidence.postCheckEvidence()),
                sanitizeTeardownEvidence(evidence.teardownEvidence()),
                evidence.capturedAtEpochMillis());
    }

    private List<FlowStepEvidence> sanitizeFlowStepEvidence(List<FlowStepEvidence> evidence) {
        if (evidence.isEmpty()) {
            return List.of();
        }
        return evidence.stream()
                .map(entry -> new FlowStepEvidence(
                        entry.stepId(),
                        entry.stepName(),
                        entry.targetStep(),
                        preferredRedactedMessage(entry.redactedRequestRaw(), entry.redactedRequestRaw()),
                        preferredRedactedMessage(entry.redactedResponseRaw(), entry.redactedResponseRaw()),
                        entry.responseStatusCode(),
                        entry.success(),
                        redactionService.redactInlineSecrets(entry.reason()),
                        entry.capturedAtEpochMillis()))
                .toList();
    }

    private List<PostCheckEvidence> sanitizePostCheckEvidence(List<PostCheckEvidence> evidence) {
        if (evidence.isEmpty()) {
            return List.of();
        }
        return evidence.stream()
                .map(entry -> new PostCheckEvidence(
                        entry.postCheckId(),
                        entry.name(),
                        preferredRedactedMessage(entry.redactedRequestRaw(), entry.redactedRequestRaw()),
                        preferredRedactedMessage(entry.redactedResponseRaw(), entry.redactedResponseRaw()),
                        entry.verdict(),
                        redactionService.redactInlineSecrets(entry.reason()),
                        sanitizePostCheckAssertionResults(entry.assertionResults()),
                        entry.capturedAtEpochMillis()))
                .toList();
    }

    private List<TeardownEvidence> sanitizeTeardownEvidence(List<TeardownEvidence> evidence) {
        if (evidence.isEmpty()) {
            return List.of();
        }
        return evidence.stream()
                .map(entry -> new TeardownEvidence(
                        entry.flowId(),
                        entry.flowName(),
                        entry.success(),
                        redactionService.redactInlineSecrets(entry.reason()),
                        sanitizeTeardownStepEvidence(entry.steps()),
                        entry.capturedAtEpochMillis()))
                .toList();
    }

    private List<TeardownStepEvidence> sanitizeTeardownStepEvidence(List<TeardownStepEvidence> evidence) {
        if (evidence.isEmpty()) {
            return List.of();
        }
        return evidence.stream()
                .map(entry -> new TeardownStepEvidence(
                        entry.stepId(),
                        preferredRedactedMessage(entry.redactedRequestRaw(), entry.redactedRequestRaw()),
                        preferredRedactedMessage(entry.redactedResponseRaw(), entry.redactedResponseRaw()),
                        entry.responseStatusCode(),
                        entry.success(),
                        redactionService.redactInlineSecrets(entry.reason()),
                        entry.capturedAtEpochMillis()))
                .toList();
    }

    private List<PostCheckAssertionResult> sanitizePostCheckAssertionResults(List<PostCheckAssertionResult> results) {
        if (results.isEmpty()) {
            return List.of();
        }
        return results.stream()
                .map(result -> new PostCheckAssertionResult(
                        result.assertionId(),
                        result.name(),
                        result.passed(),
                        redactionService.redactInlineSecrets(result.reason())))
                .toList();
    }

    private String preferredRedactedMessage(String alreadyRedacted, String raw) {
        String source = alreadyRedacted == null || alreadyRedacted.isBlank() ? raw : alreadyRedacted;
        try {
            return redactionService.redactHttpMessage(source);
        } catch (RuntimeException ignored) {
            return redactionService.redactInlineSecrets(source);
        }
    }

    String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize access workspace", exception);
        }
    }
}
