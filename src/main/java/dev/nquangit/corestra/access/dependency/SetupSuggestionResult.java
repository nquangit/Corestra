package dev.nquangit.corestra.access.dependency;

import dev.nquangit.corestra.access.matrix.SetupConfig;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Contains the outcome of suggesting setup configuration for a target flow step.
 */
public final class SetupSuggestionResult {
    private final boolean success;
    private final SetupConfig suggestedSetup;
    private final Set<ScopedVariableRef> requiredScenarioVariables;
    private final Map<ScopedVariableRef, String> producerStepIds;
    private final List<String> warnings;
    private final String reason;

    /**
     * Creates a setup suggestion result.
     *
     * @param success whether the suggestion can be applied directly
     * @param suggestedSetup suggested setup configuration
     * @param requiredScenarioVariables required scenario variables for the target
     * @param producerStepIds selected producer step ids by variable
     * @param warnings warnings requiring user attention
     * @param reason human-readable reason
     */
    public SetupSuggestionResult(
            boolean success,
            SetupConfig suggestedSetup,
            Set<ScopedVariableRef> requiredScenarioVariables,
            Map<ScopedVariableRef, String> producerStepIds,
            List<String> warnings,
            String reason) {
        this.success = success;
        this.suggestedSetup = suggestedSetup == null ? SetupConfig.none() : suggestedSetup;
        this.requiredScenarioVariables = copyRefs(requiredScenarioVariables);
        this.producerStepIds = copyProducerStepIds(producerStepIds);
        this.warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
        this.reason = reason == null ? "" : reason;
    }

    /**
     * Returns whether the suggestion can be applied directly.
     *
     * @return true when successful
     */
    public boolean success() {
        return success;
    }

    /**
     * Returns the suggested setup configuration.
     *
     * @return suggested setup configuration
     */
    public SetupConfig suggestedSetup() {
        return suggestedSetup;
    }

    /**
     * Returns required scenario variables for the target step.
     *
     * @return immutable required scenario variables
     */
    public Set<ScopedVariableRef> requiredScenarioVariables() {
        return requiredScenarioVariables;
    }

    /**
     * Returns selected producer step ids by required scenario variable.
     *
     * @return immutable producer mapping
     */
    public Map<ScopedVariableRef, String> producerStepIds() {
        return producerStepIds;
    }

    /**
     * Returns non-fatal warnings.
     *
     * @return immutable warnings
     */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * Returns a human-readable reason.
     *
     * @return reason
     */
    public String reason() {
        return reason;
    }

    private static Set<ScopedVariableRef> copyRefs(Set<ScopedVariableRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<ScopedVariableRef> copied = new LinkedHashSet<>();
        refs.forEach(ref -> copied.add(Objects.requireNonNull(ref, "ref")));
        return Set.copyOf(copied);
    }

    private static Map<ScopedVariableRef, String> copyProducerStepIds(Map<ScopedVariableRef, String> producerStepIds) {
        if (producerStepIds == null || producerStepIds.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<ScopedVariableRef, String> copied = new LinkedHashMap<>();
        producerStepIds.forEach((ref, stepId) -> copied.put(
                Objects.requireNonNull(ref, "ref"),
                stepId == null ? "" : stepId.strip()));
        return Map.copyOf(copied);
    }
}
