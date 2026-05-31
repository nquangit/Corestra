package dev.nquangit.corestra.flow;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Describes a complete reusable multi-step request flow.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class FlowDefinition {
    private final String id;
    private final String name;
    private final String description;
    private final boolean enabled;
    private final List<FlowStep> steps;
    private final FlowRuleSet rules;
    private final AggregationPlan aggregation;
    private final FlowTriggerConfig triggerConfig;

    /**
     * Creates a flow definition.
     *
     * @param id the stable flow identifier
     * @param name the display name
     * @param description the optional description
     * @param enabled whether the flow is enabled
     * @param steps the ordered steps that make up the flow
     * @param rules the optional flow-wide rules
     * @param aggregation the optional aggregation plan
     * @param triggerConfig the optional trigger configuration
     */
    @JsonCreator
    public FlowDefinition(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("steps") List<FlowStep> steps,
            @JsonProperty("rules") FlowRuleSet rules,
            @JsonProperty("aggregation") @JsonAlias("aggregationPlan") AggregationPlan aggregation,
            @JsonProperty("triggerConfig") FlowTriggerConfig triggerConfig) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.description = normalizeOptionalText(description);
        this.enabled = enabled;
        this.steps = copySteps(steps);
        this.rules = Objects.requireNonNullElse(rules, FlowRuleSet.empty());
        this.aggregation = Objects.requireNonNullElse(aggregation, AggregationPlan.disabled());
        this.triggerConfig = Objects.requireNonNullElse(triggerConfig, FlowTriggerConfig.none());

        Set<String> stepIds = validateUniqueStepIds(this.steps);
        validateInternalStepReferences(stepIds, this.aggregation, this.triggerConfig, this.steps);
    }

    /**
     * Returns the stable flow identifier.
     *
     * @return the stable flow identifier
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
     * Returns the optional description.
     *
     * @return the optional description, or an empty string when none is configured
     */
    public String description() {
        return description;
    }

    /**
     * Returns whether the flow is enabled.
     *
     * @return whether the flow is enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the ordered flow steps.
     *
     * @return the ordered flow steps
     */
    public List<FlowStep> steps() {
        return steps;
    }

    /**
     * Returns the optional flow-wide rules.
     *
     * @return the optional flow-wide rules
     */
    public FlowRuleSet rules() {
        return rules;
    }

    /**
     * Returns the aggregation plan.
     *
     * @return the aggregation plan
     */
    public AggregationPlan aggregation() {
        return aggregation;
    }

    /**
     * Returns the trigger and follow-up configuration.
     *
     * @return the trigger and follow-up configuration
     */
    public FlowTriggerConfig triggerConfig() {
        return triggerConfig;
    }

    /**
     * Indicates whether the flow contains a step with the supplied identifier.
     *
     * @param stepId the step identifier to check
     * @return {@code true} when the flow contains the step, otherwise {@code false}
     */
    public boolean hasStep(String stepId) {
        String normalizedStepId = normalizeRequiredText(stepId, "stepId");
        return steps.stream().anyMatch(step -> step.id().equals(normalizedStepId));
    }

    /**
     * Compares this definition to another object.
     *
     * @param other the candidate object
     * @return {@code true} when the objects are equal, otherwise {@code false}
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FlowDefinition that)) {
            return false;
        }
        return enabled == that.enabled
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && Objects.equals(description, that.description)
                && Objects.equals(steps, that.steps)
                && Objects.equals(rules, that.rules)
                && Objects.equals(aggregation, that.aggregation)
                && Objects.equals(triggerConfig, that.triggerConfig);
    }

    /**
     * Returns the hash code for this definition.
     *
     * @return the hash code for this definition
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, description, enabled, steps, rules, aggregation, triggerConfig);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "FlowDefinition{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", enabled=" + enabled +
                ", steps=" + steps +
                ", rules=" + rules +
                ", aggregation=" + aggregation +
                ", triggerConfig=" + triggerConfig +
                '}';
    }

    private static List<FlowStep> copySteps(List<FlowStep> steps) {
        return List.copyOf(steps == null ? List.of() : new ArrayList<>(steps));
    }

    private static Set<String> validateUniqueStepIds(List<FlowStep> steps) {
        Set<String> seenIds = new HashSet<>();
        for (FlowStep step : steps) {
            Objects.requireNonNull(step, "steps must not contain null entries");
            if (!seenIds.add(step.id())) {
                throw new IllegalArgumentException("steps must not contain duplicate step ids: " + step.id());
            }
        }
        return Set.copyOf(seenIds);
    }

    private static void validateInternalStepReferences(
            Set<String> stepIds,
            AggregationPlan aggregation,
            FlowTriggerConfig triggerConfig,
            List<FlowStep> steps) {
        if (aggregation.enabled() && !aggregation.useTriggeredStep()
                && !stepIds.contains(aggregation.finalStepId())) {
            throw new IllegalArgumentException(
                    "aggregation.finalStepId must reference an existing step id: " + aggregation.finalStepId());
        }

        for (String prerequisiteStepId : triggerConfig.prerequisiteStepIds()) {
            if (!stepIds.contains(prerequisiteStepId)) {
                throw new IllegalArgumentException(
                        "triggerConfig.prerequisiteStepIds must reference existing step ids: " + prerequisiteStepId);
            }
        }

        for (FlowStep step : steps) {
            for (FlowStep.StepPrecondition precondition : step.preconditions()) {
                if (precondition.preconditionType() == FlowStep.PreconditionType.STEP_COMPLETED
                        && !stepIds.contains(precondition.subjectKey())) {
                    throw new IllegalArgumentException(
                            "STEP_COMPLETED preconditions must reference existing step ids: "
                                    + precondition.subjectKey() + " (step=" + step.id() + ")");
                }
            }
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
}
