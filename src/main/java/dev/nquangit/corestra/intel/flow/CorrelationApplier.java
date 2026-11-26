package dev.nquangit.corestra.intel.flow;

import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Applies reviewed correlation suggestions to a flow, returning a new immutable flow.
 *
 * <p>New extractors are appended to their producer steps (once per variable) and inject rules
 * to the target step. Existing rules and every other flow and step field are preserved.</p>
 */
public final class CorrelationApplier {

    /**
     * Applies suggestions.
     *
     * @param flow current flow
     * @param targetStepId step receiving the inject rules
     * @param selected reviewed suggestions
     * @return updated flow
     * @throws IllegalArgumentException when a referenced step is missing or a new variable name is already used
     */
    public FlowDefinition apply(FlowDefinition flow, String targetStepId, List<CorrelationSuggestion> selected) {
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(targetStepId, "targetStepId");
        if (selected == null || selected.isEmpty()) {
            return flow;
        }
        Set<String> stepIds = new HashSet<>();
        flow.steps().forEach(step -> stepIds.add(step.id()));
        if (!stepIds.contains(targetStepId)) {
            throw new IllegalArgumentException("Target step not found: " + targetStepId);
        }

        Set<String> usedVariables = new HashSet<>();
        flow.steps().forEach(step -> step.extractRules()
                .forEach(rule -> usedVariables.add(rule.targetVariableName().toLowerCase(Locale.ROOT))));
        Map<String, List<ExtractRule>> newExtractors = new LinkedHashMap<>();
        Set<String> addedVariables = new HashSet<>();
        List<InjectRule> newInjects = new ArrayList<>();
        for (CorrelationSuggestion suggestion : selected) {
            if (!stepIds.contains(suggestion.producerStepId())) {
                throw new IllegalArgumentException("Producer step not found: " + suggestion.producerStepId());
            }
            ExtractRule extractor = suggestion.newExtractRule();
            if (extractor != null) {
                String key = extractor.targetVariableName().toLowerCase(Locale.ROOT);
                if (addedVariables.add(key)) {
                    if (usedVariables.contains(key)) {
                        throw new IllegalArgumentException(
                                "Variable already produced by another extractor: " + extractor.targetVariableName());
                    }
                    newExtractors.computeIfAbsent(suggestion.producerStepId(), ignored -> new ArrayList<>()).add(extractor);
                }
            }
            newInjects.add(suggestion.injectRule());
        }

        List<FlowStep> steps = new ArrayList<>();
        for (FlowStep step : flow.steps()) {
            FlowStep updated = step;
            List<ExtractRule> extractors = newExtractors.get(step.id());
            if (extractors != null) {
                List<ExtractRule> merged = new ArrayList<>(step.extractRules());
                merged.addAll(extractors);
                updated = updated.withExtractRules(merged);
            }
            if (step.id().equals(targetStepId)) {
                List<InjectRule> merged = new ArrayList<>(step.injectRules());
                merged.addAll(newInjects);
                updated = updated.withInjectRules(merged);
            }
            steps.add(updated);
        }
        return new FlowDefinition(
                flow.id(),
                flow.name(),
                flow.description(),
                flow.enabled(),
                steps,
                flow.rules(),
                flow.aggregation(),
                flow.triggerConfig());
    }
}
