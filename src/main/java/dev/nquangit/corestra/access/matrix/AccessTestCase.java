package dev.nquangit.corestra.access.matrix;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.nquangit.corestra.access.flow.AccessTargetType;
import dev.nquangit.corestra.access.postcheck.PostCheckRequest;
import dev.nquangit.corestra.access.teardown.TeardownConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents one row in the access-testing matrix.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class AccessTestCase {
    private final String id;
    private final String name;
    private final AccessTargetType targetType;
    private final String templateId;
    private final String flowTargetId;
    private final String flowId;
    private final String targetStepId;
    private final SetupConfig setup;
    private final TargetBinding target;
    private final ExpectedResult expected;
    private final String evaluationProfileId;
    private final List<ResponseAssertion> assertions;
    private final List<PostCheckRequest> postChecks;
    private final TeardownConfig teardown;
    private final boolean enabled;
    private final RunStatus status;
    private final long lastRunAtEpochMillis;

    /**
     * Creates an access test case.
     *
     * @param id stable test case identifier
     * @param name display name
     * @param targetType target type
     * @param templateId access request template identifier
     * @param flowTargetId access flow target identifier
     * @param flowId source flow identifier
     * @param targetStepId target step identifier
     * @param setup setup configuration
     * @param target target binding
     * @param expected expected access outcome
     * @param evaluationProfileId advanced evaluation profile id
     * @param assertions response assertions
     * @param postChecks optional side-effect post-check requests
     * @param teardown optional teardown flow configuration
     * @param enabled whether this test case is enabled
     * @param status last known run status
     * @param lastRunAtEpochMillis last run timestamp in epoch milliseconds, or 0 when never run
     */
    @JsonCreator
    public AccessTestCase(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("targetType") AccessTargetType targetType,
            @JsonProperty("templateId") String templateId,
            @JsonProperty("flowTargetId") String flowTargetId,
            @JsonProperty("flowId") String flowId,
            @JsonProperty("targetStepId") String targetStepId,
            @JsonProperty("setup") SetupConfig setup,
            @JsonProperty("target") TargetBinding target,
            @JsonProperty("expected") ExpectedResult expected,
            @JsonProperty("evaluationProfileId") String evaluationProfileId,
            @JsonProperty("assertions") List<ResponseAssertion> assertions,
            @JsonProperty("postChecks") List<PostCheckRequest> postChecks,
            @JsonProperty("teardown") TeardownConfig teardown,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("status") RunStatus status,
            @JsonProperty("lastRunAtEpochMillis") long lastRunAtEpochMillis) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.flowTargetId = normalizeOptionalText(flowTargetId);
        this.targetType = normalizeTargetType(targetType, this.flowTargetId);
        this.templateId = normalizeOptionalText(templateId);
        this.flowId = normalizeOptionalText(flowId);
        this.targetStepId = normalizeOptionalText(targetStepId);
        this.setup = setup == null ? SetupConfig.none() : setup;
        this.target = target == null ? TargetBinding.empty() : target;
        this.expected = Objects.requireNonNullElse(expected, ExpectedResult.REVIEW);
        this.evaluationProfileId = normalizeOptionalText(evaluationProfileId);
        this.assertions = copyAssertions(assertions);
        this.postChecks = copyPostChecks(postChecks);
        this.teardown = teardown == null ? TeardownConfig.none() : teardown;
        this.enabled = enabled;
        this.status = Objects.requireNonNullElse(status, RunStatus.PENDING);
        this.lastRunAtEpochMillis = lastRunAtEpochMillis;
    }

    /**
     * Creates a request-template access test case.
     *
     * @param id stable test case identifier
     * @param name display name
     * @param templateId access request template identifier
     * @param flowId source flow identifier
     * @param targetStepId target step identifier
     * @param setup setup configuration
     * @param target target binding
     * @param expected expected access outcome
     * @param evaluationProfileId advanced evaluation profile id
     * @param assertions response assertions
     * @param postChecks optional side-effect post-check requests
     * @param teardown optional teardown flow configuration
     * @param enabled whether this test case is enabled
     * @param status last known run status
     * @param lastRunAtEpochMillis last run timestamp in epoch milliseconds, or 0 when never run
     */
    public AccessTestCase(
            String id,
            String name,
            String templateId,
            String flowId,
            String targetStepId,
            SetupConfig setup,
            TargetBinding target,
            ExpectedResult expected,
            String evaluationProfileId,
            List<ResponseAssertion> assertions,
            List<PostCheckRequest> postChecks,
            TeardownConfig teardown,
            boolean enabled,
            RunStatus status,
            long lastRunAtEpochMillis) {
        this(id, name, AccessTargetType.REQUEST_TEMPLATE, templateId, "", flowId, targetStepId, setup, target,
                expected, evaluationProfileId, assertions, postChecks, teardown, enabled, status, lastRunAtEpochMillis);
    }

    /**
     * Creates an access test case without teardown configuration.
     *
     * @param id stable test case identifier
     * @param name display name
     * @param templateId access request template identifier
     * @param flowId source flow identifier
     * @param targetStepId target step identifier
     * @param setup setup configuration
     * @param target target binding
     * @param expected expected access outcome
     * @param evaluationProfileId advanced evaluation profile id
     * @param assertions response assertions
     * @param postChecks optional side-effect post-check requests
     * @param enabled whether this test case is enabled
     * @param status last known run status
     * @param lastRunAtEpochMillis last run timestamp in epoch milliseconds, or 0 when never run
     */
    public AccessTestCase(
            String id,
            String name,
            String templateId,
            String flowId,
            String targetStepId,
            SetupConfig setup,
            TargetBinding target,
            ExpectedResult expected,
            String evaluationProfileId,
            List<ResponseAssertion> assertions,
            List<PostCheckRequest> postChecks,
            boolean enabled,
            RunStatus status,
            long lastRunAtEpochMillis) {
        this(id, name, templateId, flowId, targetStepId, setup, target, expected, evaluationProfileId,
                assertions, postChecks, TeardownConfig.none(), enabled, status, lastRunAtEpochMillis);
    }

    /**
     * Creates an access test case without post-check requests.
     *
     * @param id stable test case identifier
     * @param name display name
     * @param templateId access request template identifier
     * @param flowId source flow identifier
     * @param targetStepId target step identifier
     * @param setup setup configuration
     * @param target target binding
     * @param expected expected access outcome
     * @param evaluationProfileId advanced evaluation profile id
     * @param assertions response assertions
     * @param enabled whether this test case is enabled
     * @param status last known run status
     * @param lastRunAtEpochMillis last run timestamp in epoch milliseconds, or 0 when never run
     */
    public AccessTestCase(
            String id,
            String name,
            String templateId,
            String flowId,
            String targetStepId,
            SetupConfig setup,
            TargetBinding target,
            ExpectedResult expected,
            String evaluationProfileId,
            List<ResponseAssertion> assertions,
            boolean enabled,
            RunStatus status,
            long lastRunAtEpochMillis) {
        this(id, name, templateId, flowId, targetStepId, setup, target, expected, evaluationProfileId,
                assertions, List.of(), TeardownConfig.none(), enabled, status, lastRunAtEpochMillis);
    }

    /**
     * Creates an access test case without an advanced evaluation profile.
     *
     * @param id stable test case identifier
     * @param name display name
     * @param templateId access request template identifier
     * @param flowId source flow identifier
     * @param targetStepId target step identifier
     * @param setup setup configuration
     * @param target target binding
     * @param expected expected access outcome
     * @param assertions response assertions
     * @param enabled whether this test case is enabled
     * @param status last known run status
     * @param lastRunAtEpochMillis last run timestamp in epoch milliseconds, or 0 when never run
     */
    public AccessTestCase(
            String id,
            String name,
            String templateId,
            String flowId,
            String targetStepId,
            SetupConfig setup,
            TargetBinding target,
            ExpectedResult expected,
            List<ResponseAssertion> assertions,
            boolean enabled,
            RunStatus status,
            long lastRunAtEpochMillis) {
        this(id, name, templateId, flowId, targetStepId, setup, target, expected, "",
                assertions, List.of(), TeardownConfig.none(), enabled, status, lastRunAtEpochMillis);
    }

    /**
     * Returns the stable test case identifier.
     *
     * @return stable test case identifier
     */
    public String id() {
        return id;
    }

    /**
     * Returns the display name.
     *
     * @return display name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the Matrix row target type.
     *
     * @return target type
     */
    public AccessTargetType targetType() {
        return targetType;
    }

    /**
     * Returns the access request template identifier.
     *
     * @return template identifier, or an empty string
     */
    public String templateId() {
        return templateId;
    }

    /**
     * Returns the access flow target identifier.
     *
     * @return flow target identifier, or an empty string
     */
    public String flowTargetId() {
        return flowTargetId;
    }

    /**
     * Returns the source flow identifier.
     *
     * @return flow identifier, or an empty string
     */
    public String flowId() {
        return flowId;
    }

    /**
     * Returns the target step identifier.
     *
     * @return target step identifier, or an empty string
     */
    public String targetStepId() {
        return targetStepId;
    }

    /**
     * Returns setup configuration.
     *
     * @return setup configuration
     */
    public SetupConfig setup() {
        return setup;
    }

    /**
     * Returns target binding.
     *
     * @return target binding
     */
    public TargetBinding target() {
        return target;
    }

    /**
     * Returns the expected access outcome.
     *
     * @return expected access outcome
     */
    public ExpectedResult expected() {
        return expected;
    }

    /**
     * Returns the selected advanced evaluation profile id.
     *
     * @return evaluation profile id, or an empty string
     */
    public String evaluationProfileId() {
        return evaluationProfileId;
    }

    /**
     * Returns configured response assertions.
     *
     * @return immutable response assertions
     */
    public List<ResponseAssertion> assertions() {
        return assertions;
    }

    /**
     * Returns configured post-check requests.
     *
     * @return immutable post-check requests
     */
    public List<PostCheckRequest> postChecks() {
        return postChecks;
    }

    /**
     * Returns teardown configuration.
     *
     * @return teardown configuration
     */
    public TeardownConfig teardown() {
        return teardown;
    }

    /**
     * Returns whether this test case is enabled.
     *
     * @return whether this test case is enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the last known run status.
     *
     * @return last known run status
     */
    public RunStatus status() {
        return status;
    }

    /**
     * Returns the last run timestamp in epoch milliseconds.
     *
     * @return last run timestamp, or 0 when never run
     */
    public long lastRunAtEpochMillis() {
        return lastRunAtEpochMillis;
    }

    /**
     * Compares this test case to another object.
     *
     * @param other the candidate object
     * @return true when the objects are equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccessTestCase that)) {
            return false;
        }
        return enabled == that.enabled
                && lastRunAtEpochMillis == that.lastRunAtEpochMillis
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && targetType == that.targetType
                && Objects.equals(templateId, that.templateId)
                && Objects.equals(flowTargetId, that.flowTargetId)
                && Objects.equals(flowId, that.flowId)
                && Objects.equals(targetStepId, that.targetStepId)
                && Objects.equals(setup, that.setup)
                && Objects.equals(target, that.target)
                && expected == that.expected
                && Objects.equals(evaluationProfileId, that.evaluationProfileId)
                && Objects.equals(assertions, that.assertions)
                && Objects.equals(postChecks, that.postChecks)
                && Objects.equals(teardown, that.teardown)
                && status == that.status;
    }

    /**
     * Returns the hash code for this test case.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, targetType, templateId, flowTargetId, flowId, targetStepId, setup, target,
                expected, evaluationProfileId, assertions, postChecks, teardown, enabled, status, lastRunAtEpochMillis);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "AccessTestCase{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", targetType=" + targetType +
                ", templateId='" + templateId + '\'' +
                ", flowTargetId='" + flowTargetId + '\'' +
                ", flowId='" + flowId + '\'' +
                ", targetStepId='" + targetStepId + '\'' +
                ", setup=" + setup +
                ", target=" + target +
                ", expected=" + expected +
                ", evaluationProfileId='" + evaluationProfileId + '\'' +
                ", assertions=" + assertions +
                ", postChecks=" + postChecks +
                ", teardown=" + teardown +
                ", enabled=" + enabled +
                ", status=" + status +
                ", lastRunAtEpochMillis=" + lastRunAtEpochMillis +
                '}';
    }

    private static List<ResponseAssertion> copyAssertions(List<ResponseAssertion> assertions) {
        if (assertions == null || assertions.isEmpty()) {
            return List.of();
        }
        List<ResponseAssertion> copied = new ArrayList<>();
        assertions.forEach(assertion -> copied.add(Objects.requireNonNull(assertion, "assertion")));
        return List.copyOf(copied);
    }

    private static List<PostCheckRequest> copyPostChecks(List<PostCheckRequest> postChecks) {
        if (postChecks == null || postChecks.isEmpty()) {
            return List.of();
        }
        List<PostCheckRequest> copied = new ArrayList<>();
        postChecks.forEach(postCheck -> copied.add(Objects.requireNonNull(postCheck, "post-check")));
        return List.copyOf(copied);
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

    private static AccessTargetType normalizeTargetType(AccessTargetType targetType, String flowTargetId) {
        if (targetType != null) {
            return targetType;
        }
        return flowTargetId == null || flowTargetId.isBlank()
                ? AccessTargetType.REQUEST_TEMPLATE
                : AccessTargetType.FLOW_TARGET;
    }
}
