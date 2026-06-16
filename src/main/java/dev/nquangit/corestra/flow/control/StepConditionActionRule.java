package dev.nquangit.corestra.flow.control;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Binds post-step conditions to ordered actions.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class StepConditionActionRule {
    private final String id;
    private final String name;
    private final boolean enabled;
    private final ConditionMatchPolicy matchPolicy;
    private final List<StepCondition> conditions;
    private final List<StepAction> actions;

    /**
     * Creates a condition/action rule.
     *
     * @param id stable rule identifier
     * @param name display name
     * @param enabled whether the rule is enabled
     * @param matchPolicy condition match policy
     * @param conditions ordered conditions
     * @param actions ordered actions
     */
    @JsonCreator
    public StepConditionActionRule(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("matchPolicy") ConditionMatchPolicy matchPolicy,
            @JsonProperty("conditions") List<StepCondition> conditions,
            @JsonProperty("actions") List<StepAction> actions) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.enabled = enabled;
        this.matchPolicy = Objects.requireNonNullElse(matchPolicy, ConditionMatchPolicy.ALL);
        this.conditions = copyList(conditions, "conditions");
        this.actions = copyList(actions, "actions");
    }

    /**
     * Returns the stable rule identifier.
     *
     * @return the stable rule identifier
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
     * Returns whether this rule is enabled.
     *
     * @return whether this rule is enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the match policy.
     *
     * @return the match policy
     */
    public ConditionMatchPolicy matchPolicy() {
        return matchPolicy;
    }

    /**
     * Returns the immutable condition list.
     *
     * @return the immutable condition list
     */
    public List<StepCondition> conditions() {
        return conditions;
    }

    /**
     * Returns the immutable action list.
     *
     * @return the immutable action list
     */
    public List<StepAction> actions() {
        return actions;
    }

    /**
     * Compares this rule to another object.
     *
     * @param other the candidate object
     * @return {@code true} when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StepConditionActionRule that)) {
            return false;
        }
        return enabled == that.enabled
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && matchPolicy == that.matchPolicy
                && Objects.equals(conditions, that.conditions)
                && Objects.equals(actions, that.actions);
    }

    /**
     * Returns the hash code for this rule.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, enabled, matchPolicy, conditions, actions);
    }

    /**
     * Returns a diagnostic string.
     *
     * @return a diagnostic string
     */
    @Override
    public String toString() {
        return "StepConditionActionRule{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", enabled=" + enabled +
                ", matchPolicy=" + matchPolicy +
                ", conditions=" + conditions +
                ", actions=" + actions +
                '}';
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

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
