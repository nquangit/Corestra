package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.access.flow.AccessTargetType;
import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.matrix.RunStatus;
import dev.nquangit.corestra.access.matrix.ScenarioPolicy;
import dev.nquangit.corestra.access.matrix.SetupAuthBinding;
import dev.nquangit.corestra.access.matrix.SetupConfig;
import dev.nquangit.corestra.access.matrix.SetupMode;
import dev.nquangit.corestra.access.matrix.TargetBinding;
import dev.nquangit.corestra.access.postcheck.PostCheckRequest;
import dev.nquangit.corestra.access.teardown.TeardownAuthBinding;
import dev.nquangit.corestra.access.teardown.TeardownConfig;
import dev.nquangit.corestra.access.teardown.TeardownMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class MatrixRowFormModel {
    private final String name;
    private final AccessTargetType targetType;
    private final String templateId;
    private final String flowTargetId;
    private final String targetAuthProfileId;
    private final String dataProfileId;
    private final String environmentId;
    private final SetupMode setupMode;
    private final String setupFlowId;
    private final String untilBeforeStepId;
    private final SetupAuthBinding setupAuthBinding;
    private final String setupAuthProfileId;
    private final ScenarioPolicy scenarioPolicy;
    private final String scenarioInstanceId;
    private final ExpectedResult expected;
    private final String evaluationProfileId;
    private final List<PostCheckRequest> postChecks;
    private final TeardownConfig teardown;
    private final boolean enabled;

    MatrixRowFormModel(
            String name,
            String templateId,
            String targetAuthProfileId,
            String dataProfileId,
            String environmentId,
            ExpectedResult expected,
            boolean enabled) {
        this(name, templateId, targetAuthProfileId, dataProfileId, environmentId,
                SetupMode.NONE, "", "", SetupAuthBinding.DATA_OWNER, "", ScenarioPolicy.REUSE_EXISTING, "",
                expected, "", List.of(), TeardownConfig.none(), enabled);
    }

    MatrixRowFormModel(
            String name,
            String templateId,
            String targetAuthProfileId,
            String dataProfileId,
            String environmentId,
            SetupMode setupMode,
            String setupFlowId,
            String untilBeforeStepId,
            SetupAuthBinding setupAuthBinding,
            String setupAuthProfileId,
            ScenarioPolicy scenarioPolicy,
            String scenarioInstanceId,
            ExpectedResult expected,
            String evaluationProfileId,
            List<PostCheckRequest> postChecks,
            TeardownConfig teardown,
            boolean enabled) {
        this(AccessTargetType.REQUEST_TEMPLATE, "", name, templateId, targetAuthProfileId, dataProfileId,
                environmentId, setupMode, setupFlowId, untilBeforeStepId, setupAuthBinding, setupAuthProfileId,
                scenarioPolicy, scenarioInstanceId, expected, evaluationProfileId, postChecks, teardown, enabled);
    }

    MatrixRowFormModel(
            AccessTargetType targetType,
            String flowTargetId,
            String name,
            String templateId,
            String targetAuthProfileId,
            String dataProfileId,
            String environmentId,
            SetupMode setupMode,
            String setupFlowId,
            String untilBeforeStepId,
            SetupAuthBinding setupAuthBinding,
            String setupAuthProfileId,
            ScenarioPolicy scenarioPolicy,
            String scenarioInstanceId,
            ExpectedResult expected,
            String evaluationProfileId,
            List<PostCheckRequest> postChecks,
            TeardownConfig teardown,
            boolean enabled) {
        this.name = normalizeRequiredText(name, "name");
        this.targetType = Objects.requireNonNullElse(targetType, AccessTargetType.REQUEST_TEMPLATE);
        this.templateId = normalizeOptionalText(templateId);
        this.flowTargetId = normalizeOptionalText(flowTargetId);
        this.targetAuthProfileId = normalizeOptionalText(targetAuthProfileId);
        this.dataProfileId = normalizeOptionalText(dataProfileId);
        this.environmentId = normalizeOptionalText(environmentId);
        this.setupMode = Objects.requireNonNullElse(setupMode, SetupMode.NONE);
        this.setupFlowId = normalizeOptionalText(setupFlowId);
        this.untilBeforeStepId = normalizeOptionalText(untilBeforeStepId);
        this.setupAuthBinding = Objects.requireNonNullElse(setupAuthBinding, SetupAuthBinding.DATA_OWNER);
        this.setupAuthProfileId = normalizeOptionalText(setupAuthProfileId);
        this.scenarioPolicy = Objects.requireNonNullElse(scenarioPolicy, ScenarioPolicy.REUSE_EXISTING);
        this.scenarioInstanceId = normalizeOptionalText(scenarioInstanceId);
        this.expected = Objects.requireNonNullElse(expected, ExpectedResult.REVIEW);
        this.evaluationProfileId = normalizeOptionalText(evaluationProfileId);
        this.postChecks = copyPostChecks(postChecks);
        this.teardown = teardown == null ? TeardownConfig.none() : teardown;
        this.enabled = enabled;
    }

    MatrixRowFormModel(
            String name,
            String templateId,
            String targetAuthProfileId,
            String dataProfileId,
            String environmentId,
            SetupMode setupMode,
            String setupFlowId,
            String untilBeforeStepId,
            SetupAuthBinding setupAuthBinding,
            String setupAuthProfileId,
            ScenarioPolicy scenarioPolicy,
            String scenarioInstanceId,
            ExpectedResult expected,
            String evaluationProfileId,
            List<PostCheckRequest> postChecks,
            boolean enabled) {
        this(name, templateId, targetAuthProfileId, dataProfileId, environmentId, setupMode, setupFlowId,
                untilBeforeStepId, setupAuthBinding, setupAuthProfileId, scenarioPolicy, scenarioInstanceId,
                expected, evaluationProfileId, postChecks, TeardownConfig.none(), enabled);
    }

    static MatrixRowFormModel from(AccessTestCase testCase) {
        Objects.requireNonNull(testCase, "testCase");
        return new MatrixRowFormModel(
                testCase.targetType(),
                testCase.flowTargetId(),
                testCase.name(),
                testCase.templateId(),
                testCase.target().targetAuthProfileId(),
                testCase.target().dataProfileId(),
                testCase.target().environmentId(),
                testCase.setup().mode(),
                testCase.setup().setupFlowId(),
                testCase.setup().untilBeforeStepId(),
                testCase.setup().setupAuthBinding(),
                testCase.setup().setupAuthProfileId(),
                testCase.setup().scenarioPolicy(),
                testCase.target().scenarioInstanceId(),
                testCase.expected(),
                testCase.evaluationProfileId(),
                testCase.postChecks(),
                testCase.teardown(),
                testCase.enabled());
    }

    AccessTestCase toTestCase(String id, String flowId, String targetStepId, RunStatus status, long lastRunAtEpochMillis) {
        return new AccessTestCase(
                id,
                name,
                targetType,
                templateId,
                flowTargetId,
                flowId,
                targetStepId,
                new SetupConfig(setupMode, setupFlowId, untilBeforeStepId,
                        setupAuthProfileId, setupAuthBinding, scenarioPolicy),
                new TargetBinding(targetAuthProfileId, dataProfileId, environmentId, scenarioInstanceId),
                expected,
                evaluationProfileId,
                List.of(),
                postChecks,
                teardown,
                enabled,
                Objects.requireNonNullElse(status, RunStatus.PENDING),
                lastRunAtEpochMillis);
    }

    String name() {
        return name;
    }

    AccessTargetType targetType() {
        return targetType;
    }

    String templateId() {
        return templateId;
    }

    String flowTargetId() {
        return flowTargetId;
    }

    String targetAuthProfileId() {
        return targetAuthProfileId;
    }

    String dataProfileId() {
        return dataProfileId;
    }

    String environmentId() {
        return environmentId;
    }

    SetupMode setupMode() {
        return setupMode;
    }

    String setupFlowId() {
        return setupFlowId;
    }

    String untilBeforeStepId() {
        return untilBeforeStepId;
    }

    SetupAuthBinding setupAuthBinding() {
        return setupAuthBinding;
    }

    String setupAuthProfileId() {
        return setupAuthProfileId;
    }

    ScenarioPolicy scenarioPolicy() {
        return scenarioPolicy;
    }

    String scenarioInstanceId() {
        return scenarioInstanceId;
    }

    ExpectedResult expected() {
        return expected;
    }

    String evaluationProfileId() {
        return evaluationProfileId;
    }

    List<PostCheckRequest> postChecks() {
        return postChecks;
    }

    TeardownConfig teardown() {
        return teardown;
    }

    TeardownMode teardownMode() {
        return teardown.mode();
    }

    String teardownFlowId() {
        return teardown.teardownFlowId();
    }

    TeardownAuthBinding teardownAuthBinding() {
        return teardown.authBinding();
    }

    String teardownAuthProfileId() {
        return teardown.teardownAuthProfileId();
    }

    boolean teardownRunOnSuccess() {
        return teardown.runOnSuccess();
    }

    boolean teardownRunOnFailure() {
        return teardown.runOnFailure();
    }

    boolean teardownRunOnSkipped() {
        return teardown.runOnSkipped();
    }

    boolean enabled() {
        return enabled;
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }

    private static String normalizeOptionalText(String value) {
        return value == null ? "" : value.strip();
    }

    private static List<PostCheckRequest> copyPostChecks(List<PostCheckRequest> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        ArrayList<PostCheckRequest> copied = new ArrayList<>();
        values.forEach(value -> copied.add(Objects.requireNonNull(value, "post-check")));
        return List.copyOf(copied);
    }
}
