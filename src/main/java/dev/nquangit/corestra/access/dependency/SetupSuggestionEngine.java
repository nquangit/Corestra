package dev.nquangit.corestra.access.dependency;

import dev.nquangit.corestra.access.matrix.ScenarioPolicy;
import dev.nquangit.corestra.access.matrix.SetupAuthBinding;
import dev.nquangit.corestra.access.matrix.SetupConfig;
import dev.nquangit.corestra.access.matrix.SetupMode;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.flow.FlowDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Suggests setup configuration for target steps that require scenario variables.
 */
public final class SetupSuggestionEngine {
    private final FlowDependencyAnalyzer analyzer;

    /**
     * Creates a setup suggestion engine with a default dependency analyzer.
     */
    public SetupSuggestionEngine() {
        this(new FlowDependencyAnalyzer());
    }

    /**
     * Creates a setup suggestion engine.
     *
     * @param analyzer dependency analyzer
     */
    public SetupSuggestionEngine(FlowDependencyAnalyzer analyzer) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
    }

    /**
     * Suggests setup for a target step using flow-embedded metadata.
     *
     * @param flow flow definition
     * @param targetStepId target step identifier
     * @param mode render mode
     * @return setup suggestion
     */
    public SetupSuggestionResult suggestForTargetStep(
            FlowDefinition flow,
            String targetStepId,
            RenderMode mode) {
        return suggestForTargetStep(flow, targetStepId, mode, List.of());
    }

    /**
     * Suggests setup for a target step using optional access template metadata.
     *
     * @param flow flow definition
     * @param targetStepId target step identifier
     * @param mode render mode
     * @param accessTemplates access templates that may reference the target step
     * @return setup suggestion
     */
    public SetupSuggestionResult suggestForTargetStep(
            FlowDefinition flow,
            String targetStepId,
            RenderMode mode,
            List<AccessRequestTemplate> accessTemplates) {
        Objects.requireNonNull(flow, "flow");
        RenderMode renderMode = Objects.requireNonNullElse(mode, RenderMode.STRICT);
        FlowDependencyGraph graph = analyzer.analyze(flow, accessTemplates, renderMode);
        List<String> warnings = new ArrayList<>(graph.warnings());
        int targetIndex = graph.indexOfStep(targetStepId);
        if (targetIndex < 0) {
            warnings.add("Target step not found: " + Objects.requireNonNullElse(targetStepId, ""));
            return new SetupSuggestionResult(
                    false,
                    SetupConfig.none(),
                    Set.of(),
                    Map.of(),
                    warnings,
                    "Target step not found");
        }

        Set<ScopedVariableRef> requiredScenarioVariables =
                new LinkedHashSet<>(graph.requiredScenarioVariablesFor(targetStepId));
        if (requiredScenarioVariables.isEmpty()) {
            return new SetupSuggestionResult(
                    true,
                    SetupConfig.none(),
                    Set.of(),
                    Map.of(),
                    warnings,
                    "Target step does not require scenario variables");
        }

        LinkedHashMap<ScopedVariableRef, String> selectedProducers = new LinkedHashMap<>();
        boolean allSatisfiedByPrefix = true;
        for (ScopedVariableRef ref : requiredScenarioVariables) {
            List<FlowStepVariableMetadata> producers = graph.producersFor(ref);
            List<FlowStepVariableMetadata> beforeTarget = producers.stream()
                    .filter(producer -> graph.indexOfStep(producer.stepId()) < targetIndex)
                    .toList();
            List<FlowStepVariableMetadata> afterTarget = producers.stream()
                    .filter(producer -> graph.indexOfStep(producer.stepId()) > targetIndex)
                    .toList();

            if (producers.size() > 1) {
                warnings.add("Multiple producers found for " + ref.scopedKey()
                        + "; choosing nearest producer before target when available.");
            }
            if (!afterTarget.isEmpty()) {
                warnings.add("Producer exists after target and cannot satisfy RUN_PREFIX: "
                        + ref.scopedKey() + " -> " + stepIds(afterTarget));
            }
            if (beforeTarget.isEmpty()) {
                allSatisfiedByPrefix = false;
                warnings.add(producers.isEmpty()
                        ? "Missing producer for required scenario variable: " + ref.scopedKey()
                        : "No producer before target for required scenario variable: " + ref.scopedKey());
                continue;
            }

            FlowStepVariableMetadata nearest = beforeTarget.stream()
                    .max(Comparator.comparingInt(producer -> graph.indexOfStep(producer.stepId())))
                    .orElseThrow();
            selectedProducers.put(ref, nearest.stepId());
        }

        if (!allSatisfiedByPrefix) {
            return new SetupSuggestionResult(
                    false,
                    SetupConfig.none(),
                    requiredScenarioVariables,
                    selectedProducers,
                    warnings,
                    "Manual setup is required");
        }

        SetupConfig setup = new SetupConfig(
                SetupMode.RUN_PREFIX,
                flow.id(),
                targetStepId,
                "",
                SetupAuthBinding.DATA_OWNER,
                ScenarioPolicy.FRESH_PER_TEST);
        return new SetupSuggestionResult(
                true,
                setup,
                requiredScenarioVariables,
                selectedProducers,
                warnings,
                "RUN_PREFIX setup can satisfy target scenario dependencies");
    }

    private String stepIds(List<FlowStepVariableMetadata> metadata) {
        return metadata.stream().map(FlowStepVariableMetadata::stepId).toList().toString();
    }
}
