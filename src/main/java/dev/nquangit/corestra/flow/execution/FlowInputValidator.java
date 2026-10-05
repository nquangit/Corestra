package dev.nquangit.corestra.flow.execution;

import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.control.StepActionType;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks required injection inputs before protected flows and their individual steps can send requests. */
public final class FlowInputValidator {
    /** Execution metadata enabling mandatory injection inputs. */
    public static final String REQUIRE_INPUTS = "requireResolvedInjectionVariables";
    /** Execution metadata declaring aliases for unscoped extraction outputs. */
    public static final String OUTPUT_SCOPE = "extractedVariableScope";
    /** Terminal input errors cannot be bypassed by a step's continue or action policy. */
    public static final String FAILURE_PREFIX = "Required injection failed: ";
    private static final Set<String> OTHER_SCOPES = Set.of("env", "credentials", "data", "scenario", "runtime", "legacy");

    private FlowInputValidator() { }

    /**
     * Finds missing or blank external inputs, honoring step order and enabled extractors.
     * Declared outputs may satisfy later steps; actual values are checked again during execution.
     * Static analysis stops at a trigger action, whose nested flow may supply further inputs.
     *
     * @param flow flow to validate
     * @param variables caller's actor-specific inputs
     * @param outputScope optional namespace for unscoped extraction outputs
     * @return distinct missing input keys in declaration order
     */
    public static List<String> missingExternalVariables(FlowDefinition flow, Map<String, String> variables, String outputScope) {
        LinkedHashSet<String> available = new LinkedHashSet<>();
        variables.forEach((key, value) -> { if (value != null && !value.isBlank()) available.add(key); });
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        for (FlowStep step : flow.steps()) {
            if (!step.enabled()) continue;
            for (String key : referencedKeys(step)) {
                if (!available.contains(key)) missing.add(key);
            }
            for (ExtractRule rule : step.extractRules()) {
                if (rule.enabled()) {
                    available.addAll(scopedOutputs(Map.of(rule.targetVariableName(), "produced"), outputScope).keySet());
                }
            }
            if (step.conditionActionRules().stream().filter(rule -> rule.enabled())
                    .flatMap(rule -> rule.actions().stream())
                    .anyMatch(action -> action.enabled() && action.type() == StepActionType.TRIGGER_FLOW)) {
                break;
            }
        }
        return List.copyOf(missing);
    }

    /** Returns missing or blank keys required by enabled injection rules of the current step. */
    public static List<String> missingStepVariables(FlowStep step, Map<String, String> variables) {
        return referencedKeys(step).stream()
                .filter(key -> variables.get(key) == null || variables.get(key).isBlank()).toList();
    }

    /**
     * Adds flat and scoped aliases for outputs, leaving other explicitly scoped values intact.
     *
     * @param outputs actual extraction values
     * @param scope optional output namespace
     * @return output values with aliases, so later steps can read either token or auth.token
     */
    public static Map<String, String> scopedOutputs(Map<String, String> outputs, String scope) {
        if (scope == null || scope.isBlank()) return outputs;
        LinkedHashMap<String, String> aliased = new LinkedHashMap<>();
        outputs.forEach((key, value) -> {
            aliased.put(key, value);
            if (key.startsWith(scope + ".")) {
                aliased.put(key.substring(scope.length() + 1), value);
            } else if (OTHER_SCOPES.stream().noneMatch(other -> key.startsWith(other + "."))) {
                aliased.put(scope + "." + key, value);
            }
        });
        return aliased;
    }

    private static Set<String> referencedKeys(FlowStep step) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (InjectRule rule : step.injectRules()) {
            if (rule.enabled()) keys.addAll(rule.referencedVariableKeys());
        }
        return keys;
    }
}
