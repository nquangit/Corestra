package dev.nquangit.corestra.flow;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Holds optional flow-wide rules that are not tied to a single step.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class FlowRuleSet {
    private final List<ExtractRule> sharedExtractRules;
    private final List<InjectRule> sharedInjectRules;

    /**
     * Creates a flow-wide rule set.
     *
     * @param sharedExtractRules shared extraction rules for the flow
     * @param sharedInjectRules shared injection rules for the flow
     */
    @JsonCreator
    public FlowRuleSet(
            @JsonProperty("sharedExtractRules") List<ExtractRule> sharedExtractRules,
            @JsonProperty("sharedInjectRules") List<InjectRule> sharedInjectRules) {
        this.sharedExtractRules = List.copyOf(sharedExtractRules == null ? List.of() : new ArrayList<>(sharedExtractRules));
        this.sharedInjectRules = List.copyOf(sharedInjectRules == null ? List.of() : new ArrayList<>(sharedInjectRules));
    }

    /**
     * Returns an empty rule set.
     *
     * @return an empty rule set
     */
    public static FlowRuleSet empty() {
        return new FlowRuleSet(List.of(), List.of());
    }

    /**
     * Returns the shared extraction rules.
     *
     * @return the shared extraction rules
     */
    public List<ExtractRule> sharedExtractRules() {
        return sharedExtractRules;
    }

    /**
     * Returns the shared injection rules.
     *
     * @return the shared injection rules
     */
    public List<InjectRule> sharedInjectRules() {
        return sharedInjectRules;
    }

    /**
     * Compares this rule set to another object.
     *
     * @param other the candidate object
     * @return {@code true} when the objects are equal, otherwise {@code false}
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FlowRuleSet that)) {
            return false;
        }
        return Objects.equals(sharedExtractRules, that.sharedExtractRules)
                && Objects.equals(sharedInjectRules, that.sharedInjectRules);
    }

    /**
     * Returns the hash code for this rule set.
     *
     * @return the hash code for this rule set
     */
    @Override
    public int hashCode() {
        return Objects.hash(sharedExtractRules, sharedInjectRules);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "FlowRuleSet{" +
                "sharedExtractRules=" + sharedExtractRules +
                ", sharedInjectRules=" + sharedInjectRules +
                '}';
    }
}
