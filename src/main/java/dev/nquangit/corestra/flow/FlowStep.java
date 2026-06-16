package dev.nquangit.corestra.flow;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.nquangit.corestra.flow.control.StepConditionActionRule;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Describes a single executable step inside a flow definition.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class FlowStep {
    private final String id;
    private final String name;
    private final boolean enabled;
    private final String notes;
    private final StoredRequestTemplate requestTemplate;
    private final List<ExtractRule> extractRules;
    private final List<InjectRule> injectRules;
    private final List<StepPrecondition> preconditions;
    private final StepErrorStrategy errorStrategy;
    // Base64-encoded raw HTTP response bytes — persisted so the response editor survives reload.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final String lastResponseRaw;
    private final StepTriggerRule triggerRule;
    private final boolean includeInAggregation;
    private final List<StepConditionActionRule> conditionActionRules;

    /** Convenience constructor used by most internal code (no stored response, no trigger rule). */
    public FlowStep(
            String id, String name, boolean enabled, String notes,
            StoredRequestTemplate requestTemplate, List<ExtractRule> extractRules,
            List<InjectRule> injectRules, List<StepPrecondition> preconditions,
            StepErrorStrategy errorStrategy) {
        this(id, name, enabled, notes, requestTemplate, extractRules,
             injectRules, preconditions, errorStrategy, null, null, false, List.of());
    }

    /** Convenience constructor with stored response but no trigger rule. */
    public FlowStep(
            String id, String name, boolean enabled, String notes,
            StoredRequestTemplate requestTemplate, List<ExtractRule> extractRules,
            List<InjectRule> injectRules, List<StepPrecondition> preconditions,
            StepErrorStrategy errorStrategy, String lastResponseRaw) {
        this(id, name, enabled, notes, requestTemplate, extractRules,
             injectRules, preconditions, errorStrategy, lastResponseRaw, null, false, List.of());
    }

    /** Backward-compatible 11-arg constructor — {@code includeInAggregation} defaults to {@code false}. */
    public FlowStep(
            String id, String name, boolean enabled, String notes,
            StoredRequestTemplate requestTemplate, List<ExtractRule> extractRules,
            List<InjectRule> injectRules, List<StepPrecondition> preconditions,
            StepErrorStrategy errorStrategy, String lastResponseRaw,
            StepTriggerRule triggerRule) {
        this(id, name, enabled, notes, requestTemplate, extractRules,
             injectRules, preconditions, errorStrategy, lastResponseRaw, triggerRule, false, List.of());
    }

    /** Backward-compatible 12-arg constructor — condition/action rules default to empty. */
    public FlowStep(
            String id, String name, boolean enabled, String notes,
            StoredRequestTemplate requestTemplate, List<ExtractRule> extractRules,
            List<InjectRule> injectRules, List<StepPrecondition> preconditions,
            StepErrorStrategy errorStrategy, String lastResponseRaw,
            StepTriggerRule triggerRule, boolean includeInAggregation) {
        this(id, name, enabled, notes, requestTemplate, extractRules,
             injectRules, preconditions, errorStrategy, lastResponseRaw, triggerRule,
             includeInAggregation, List.of());
    }

    @JsonCreator
    public FlowStep(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("notes") @JsonAlias("description") String notes,
            @JsonProperty("requestTemplate") StoredRequestTemplate requestTemplate,
            @JsonProperty("extractRules") List<ExtractRule> extractRules,
            @JsonProperty("injectRules") List<InjectRule> injectRules,
            @JsonProperty("preconditions") List<StepPrecondition> preconditions,
            @JsonProperty("errorStrategy") StepErrorStrategy errorStrategy,
            @JsonProperty("lastResponseRaw") String lastResponseRaw,
            @JsonProperty("triggerRule") StepTriggerRule triggerRule,
            @JsonProperty("includeInAggregation") boolean includeInAggregation,
            @JsonProperty("conditionActionRules") List<StepConditionActionRule> conditionActionRules) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.enabled = enabled;
        this.notes = normalizeOptionalText(notes);
        this.requestTemplate = Objects.requireNonNull(requestTemplate, "requestTemplate");
        this.extractRules = copyList(extractRules, "extractRules");
        this.injectRules = copyList(injectRules, "injectRules");
        this.preconditions = copyList(preconditions, "preconditions");
        this.errorStrategy = Objects.requireNonNullElse(errorStrategy, StepErrorStrategy.FAIL_FLOW);
        this.lastResponseRaw = lastResponseRaw;
        this.triggerRule = triggerRule != null ? triggerRule : StepTriggerRule.disabled();
        this.includeInAggregation = includeInAggregation;
        this.conditionActionRules = copyList(conditionActionRules, "conditionActionRules");
    }

    /** Returns a copy of this step with the given Base64-encoded raw response stored. */
    public FlowStep withLastResponseRaw(String raw) {
        return new FlowStep(id, name, enabled, notes, requestTemplate,
                            extractRules, injectRules, preconditions, errorStrategy, raw, triggerRule,
                            includeInAggregation, conditionActionRules);
    }

    /** Returns a copy of this step with the given trigger rule. */
    public FlowStep withTriggerRule(StepTriggerRule rule) {
        return new FlowStep(id, name, enabled, notes, requestTemplate,
                            extractRules, injectRules, preconditions, errorStrategy, lastResponseRaw,
                            rule != null ? rule : StepTriggerRule.disabled(), includeInAggregation,
                            conditionActionRules);
    }

    /** Returns a copy of this step with the display name changed. */
    public FlowStep withName(String name) {
        return new FlowStep(id, name, enabled, notes, requestTemplate,
                            extractRules, injectRules, preconditions, errorStrategy, lastResponseRaw,
                            triggerRule, includeInAggregation, conditionActionRules);
    }

    /** Returns a copy of this step with the enabled flag changed. */
    public FlowStep withEnabled(boolean enabled) {
        return new FlowStep(id, name, enabled, notes, requestTemplate,
                            extractRules, injectRules, preconditions, errorStrategy, lastResponseRaw,
                            triggerRule, includeInAggregation, conditionActionRules);
    }

    /** Returns a copy of this step with the notes text changed. */
    public FlowStep withNotes(String notes) {
        return new FlowStep(id, name, enabled, notes, requestTemplate,
                            extractRules, injectRules, preconditions, errorStrategy, lastResponseRaw,
                            triggerRule, includeInAggregation, conditionActionRules);
    }

    /** Returns a copy of this step with the request template changed. */
    public FlowStep withRequestTemplate(StoredRequestTemplate template) {
        return new FlowStep(id, name, enabled, notes, template,
                            extractRules, injectRules, preconditions, errorStrategy, lastResponseRaw,
                            triggerRule, includeInAggregation, conditionActionRules);
    }

    /** Returns a copy of this step with the extract rules replaced. */
    public FlowStep withExtractRules(List<ExtractRule> rules) {
        return new FlowStep(id, name, enabled, notes, requestTemplate,
                            rules, injectRules, preconditions, errorStrategy, lastResponseRaw,
                            triggerRule, includeInAggregation, conditionActionRules);
    }

    /** Returns a copy of this step with the inject rules replaced. */
    public FlowStep withInjectRules(List<InjectRule> rules) {
        return new FlowStep(id, name, enabled, notes, requestTemplate,
                            extractRules, rules, preconditions, errorStrategy, lastResponseRaw,
                            triggerRule, includeInAggregation, conditionActionRules);
    }

    /** Returns a copy of this step with the includeInAggregation flag changed. */
    public FlowStep withIncludeInAggregation(boolean include) {
        return new FlowStep(id, name, enabled, notes, requestTemplate,
                            extractRules, injectRules, preconditions, errorStrategy, lastResponseRaw,
                            triggerRule, include, conditionActionRules);
    }

    /** Returns a copy of this step with condition/action rules replaced. */
    public FlowStep withConditionActionRules(List<StepConditionActionRule> rules) {
        return new FlowStep(id, name, enabled, notes, requestTemplate,
                            extractRules, injectRules, preconditions, errorStrategy, lastResponseRaw,
                            triggerRule, includeInAggregation, rules);
    }

    /**
     * Returns the stable step identifier.
     *
     * @return the stable step identifier
     */
    public String id() {
        return id;
    }

    /**
     * Returns the display name.
     *
     * @return the display name
     */
    public String name() {
        return name;
    }

    /**
     * Returns whether the step is enabled.
     *
     * @return whether the step is enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the optional execution notes.
     *
     * @return the optional execution notes, or an empty string when none is configured
     */
    public String notes() {
        return notes;
    }

    /**
     * Returns the stored request template.
     *
     * @return the stored request template
     */
    public StoredRequestTemplate requestTemplate() {
        return requestTemplate;
    }

    /**
     * Returns the step-scoped extraction rules.
     *
     * @return the step-scoped extraction rules
     */
    public List<ExtractRule> extractRules() {
        return extractRules;
    }

    /**
     * Returns the step-scoped injection rules.
     *
     * @return the step-scoped injection rules
     */
    public List<InjectRule> injectRules() {
        return injectRules;
    }

    /**
     * Returns the preconditions that must be satisfied before execution.
     *
     * @return the execution preconditions
     */
    public List<StepPrecondition> preconditions() {
        return preconditions;
    }

    /**
     * Returns the strategy applied when the step fails.
     *
     * @return the strategy applied when the step fails
     */
    public StepErrorStrategy errorStrategy() {
        return errorStrategy;
    }

    /**
     * Returns the Base64-encoded raw HTTP response bytes from the last capture, or {@code null}
     * when no response has been stored for this step.
     *
     * @return the stored raw response, or {@code null}
     */
    public String lastResponseRaw() {
        return lastResponseRaw;
    }

    /**
     * Returns the auto-trigger rule for this step.
     * Never {@code null} — defaults to {@link StepTriggerRule#disabled()} when not configured.
     *
     * @return the auto-trigger rule
     */
    public StepTriggerRule triggerRule() {
        return triggerRule;
    }

    /**
     * Returns whether this step's response should be included in the flow's aggregation result
     * when the flow is triggered via auto-trigger.
     *
     * @return {@code true} when the step response is included in aggregation
     */
    public boolean includeInAggregation() {
        return includeInAggregation;
    }

    /**
     * Returns the post-step condition/action rules.
     *
     * @return the post-step condition/action rules
     */
    public List<StepConditionActionRule> conditionActionRules() {
        return conditionActionRules;
    }

    /**
     * Compares this step to another object.
     *
     * @param other the candidate object
     * @return {@code true} when the objects are equal, otherwise {@code false}
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FlowStep that)) {
            return false;
        }
        return enabled == that.enabled
                && includeInAggregation == that.includeInAggregation
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && Objects.equals(notes, that.notes)
                && Objects.equals(requestTemplate, that.requestTemplate)
                && Objects.equals(extractRules, that.extractRules)
                && Objects.equals(injectRules, that.injectRules)
                && Objects.equals(preconditions, that.preconditions)
                && errorStrategy == that.errorStrategy
                && Objects.equals(triggerRule, that.triggerRule)
                && Objects.equals(conditionActionRules, that.conditionActionRules);
    }

    /**
     * Returns the hash code for this step.
     *
     * @return the hash code for this step
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, enabled, includeInAggregation, notes, requestTemplate, extractRules, injectRules, preconditions, errorStrategy, triggerRule, conditionActionRules);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "FlowStep{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", enabled=" + enabled +
                ", notes='" + notes + '\'' +
                ", requestTemplate=" + requestTemplate +
                ", extractRules=" + extractRules +
                ", injectRules=" + injectRules +
                ", preconditions=" + preconditions +
                ", errorStrategy=" + errorStrategy +
                ", conditionActionRules=" + conditionActionRules +
                '}';
    }

    /**
     * Enumerates supported precondition types.
     */
    public enum PreconditionType {
        /**
         * Requires the named execution variable to be present.
         */
        VARIABLE_PRESENT,

        /**
         * Requires the named execution variable to be absent.
         */
        VARIABLE_ABSENT,

        /**
         * Requires the named metadata entry to be present.
         */
        METADATA_PRESENT,

        /**
         * Requires a previous step with the supplied identifier to have completed.
         */
        STEP_COMPLETED
    }

    /**
     * Enumerates the strategies applied when a step fails.
     */
    public enum StepErrorStrategy {
        /**
         * Fails the enclosing flow immediately.
         */
        FAIL_FLOW,

        /**
         * Records the failure and continues with the remaining flow steps.
         */
        CONTINUE_FLOW
    }

    /**
     * Describes a prerequisite that must be satisfied before a step can execute.
     */
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class StepPrecondition {
        private final PreconditionType preconditionType;
        private final String subjectKey;
        private final String description;

        /**
         * Creates a step precondition.
         *
         * @param preconditionType the precondition type
         * @param subjectKey the variable, metadata, or step identifier referenced by the precondition
         * @param description the optional human-readable description
         */
        @JsonCreator
        public StepPrecondition(
                @JsonProperty("preconditionType") PreconditionType preconditionType,
                @JsonProperty("subjectKey") String subjectKey,
                @JsonProperty("description") String description) {
            this.preconditionType = Objects.requireNonNull(preconditionType, "preconditionType");
            this.subjectKey = normalizeRequiredText(subjectKey, "subjectKey");
            this.description = normalizeOptionalText(description);
        }

        /**
         * Returns the precondition type.
         *
         * @return the precondition type
         */
        public PreconditionType preconditionType() {
            return preconditionType;
        }

        /**
         * Returns the referenced variable, metadata key, or step identifier.
         *
         * @return the referenced variable, metadata key, or step identifier
         */
        public String subjectKey() {
            return subjectKey;
        }

        /**
         * Returns the optional human-readable description.
         *
         * @return the optional human-readable description, or an empty string when none is configured
         */
        public String description() {
            return description;
        }

        /**
         * Compares this precondition to another object.
         *
         * @param other the candidate object
         * @return {@code true} when the objects are equal, otherwise {@code false}
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof StepPrecondition that)) {
                return false;
            }
            return preconditionType == that.preconditionType
                    && Objects.equals(subjectKey, that.subjectKey)
                    && Objects.equals(description, that.description);
        }

        /**
         * Returns the hash code for this precondition.
         *
         * @return the hash code for this precondition
         */
        @Override
        public int hashCode() {
            return Objects.hash(preconditionType, subjectKey, description);
        }

        /**
         * Returns a diagnostic string representation.
         *
         * @return a diagnostic string representation
         */
        @Override
        public String toString() {
            return "StepPrecondition{" +
                    "preconditionType=" + preconditionType +
                    ", subjectKey='" + subjectKey + '\'' +
                    ", description='" + description + '\'' +
                    '}';
        }
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

    private static <T> List<T> copyList(List<T> values, String fieldName) {
        if (values == null) {
            return List.of();
        }

        List<T> copied = new ArrayList<>();
        for (T value : values) {
            copied.add(Objects.requireNonNull(value, fieldName + " must not contain null entries"));
        }
        return List.copyOf(copied);
    }
}
