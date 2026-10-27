package dev.nquangit.corestra.integration;

import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.TriggerInputBindings;
import dev.nquangit.corestra.flow.control.StepActionType;
import dev.nquangit.corestra.flow.execution.FlowInputValidator;
import dev.nquangit.corestra.flow.rule.ExtractRule;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Resolves trigger-local profile inputs and analyzes ordered injection dependencies without sending HTTP. */
public final class TriggerInputService {
    private final Supplier<AccessWorkspace> workspace;
    private final Clock clock;

    /** Creates a resolver over current immutable profile snapshots. */
    public TriggerInputService(Supplier<AccessWorkspace> workspace, Clock clock) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Copies selected profile data once per run; never authenticates or writes shared state. */
    public Map<String, String> resolve(TriggerInputBindings bindings) {
        if (!bindings.hasProfiles()) return Map.of();
        AccessWorkspace snapshot = workspace.get();
        LinkedHashMap<String, String> variables = new LinkedHashMap<>();
        if (!bindings.authProfileId().isEmpty()) {
            var profile = snapshot.authProfiles().get(bindings.authProfileId());
            if (profile == null) throw new TriggerInputException("Auth Profile not found: " + bindings.authProfileId());
            if (!profile.enabled()) throw new TriggerInputException("Auth Profile is disabled: " + profile.name());
            add(variables, "", profile.credentials());
            add(variables, "credentials.", profile.credentials());
            var state = profile.state();
            // Credentials remain useful for login even when the old authentication has expired.
            if (state.expiresAtEpochMillis() == 0 || state.expiresAtEpochMillis() > clock.millis()) {
                add(variables, "", state.variables());
                add(variables, "auth.", state.variables());
                add(variables, "auth.header.", state.headers());
                add(variables, "auth.cookie.", state.cookies());
            }
        }
        if (!bindings.dataProfileId().isEmpty()) {
            var profile = snapshot.dataProfiles().get(bindings.dataProfileId());
            if (profile == null) throw new TriggerInputException("Data Profile not found: " + bindings.dataProfileId());
            if (!profile.enabled()) throw new TriggerInputException("Data Profile is disabled: " + profile.name());
            add(variables, "data.", profile.values());
        }
        return Map.copyOf(variables);
    }

    /** Removes explicit target-only injections from a run-local flow copy, never from the saved flow. */
    public static FlowDefinition applyTargetExclusions(FlowDefinition flow, int matchedIndex, TriggerInputBindings bindings) {
        if (bindings.excludedTargetRuleIds().isEmpty()) return flow;
        FlowStep target = flow.steps().get(matchedIndex);
        for (String id : bindings.excludedTargetRuleIds()) {
            if (target.injectRules().stream().noneMatch(rule -> rule.id().equals(id))) {
                throw new TriggerInputException("Excluded target inject rule no longer exists: " + id
                        + ". Review Target inject rules in Auto Trigger Flow.");
            }
        }
        List<FlowStep> steps = new ArrayList<>(flow.steps());
        steps.set(matchedIndex, target.withInjectRules(target.injectRules().stream()
                .filter(rule -> !bindings.excludedTargetRuleIds().contains(rule.id())).toList()));
        return new FlowDefinition(flow.id(), flow.name(), flow.description(), flow.enabled(), steps,
                flow.rules(), flow.aggregation(), flow.triggerConfig());
    }

    /** An input's expected source; runtime extraction still needs to succeed. */
    public record Input(String stepName, String key, String source, boolean missing) { }

    /**
     * Analyzes only enabled steps in the executed range, including request-side extraction
     * before injection on the live target. Child-flow outputs are deferred to runtime checks.
     */
    public static List<Input> analyze(FlowDefinition flow, int matchedIndex, int stopIndex,
                                      Map<String, String> variables) {
        LinkedHashMap<String, String> available = new LinkedHashMap<>();
        variables.forEach((key, value) -> {
            if (!value.isBlank()) available.put(key, "Selected profile");
        });
        List<Input> inputs = new ArrayList<>();
        for (int i = 0; i <= stopIndex; i++) {
            FlowStep step = flow.steps().get(i);
            if (!step.enabled()) continue;
            if (i == matchedIndex) addOutputs(available, step, true);
            for (var rule : step.injectRules()) {
                if (!rule.enabled()) continue;
                for (String key : rule.referencedVariableKeys()) {
                    String source = available.get(key);
                    inputs.add(new Input(step.name(), key, source == null ? "Missing / blank" : source, source == null));
                }
            }
            addOutputs(available, step, false);
            if (i != matchedIndex && step.conditionActionRules().stream().filter(rule -> rule.enabled())
                    .flatMap(rule -> rule.actions().stream())
                    .anyMatch(action -> action.enabled() && action.type() == StepActionType.TRIGGER_FLOW)) break;
        }
        return List.copyOf(inputs);
    }

    /** Rejects statically missing inputs before sending any prerequisite request. */
    public static void requireInputs(FlowDefinition flow, int matchedIndex, int stopIndex,
                                     Map<String, String> variables) {
        List<String> missing = analyze(flow, matchedIndex, stopIndex, variables).stream()
                .filter(Input::missing).map(input -> input.key() + " (" + input.stepName() + ")").distinct().toList();
        if (!missing.isEmpty()) throw new TriggerInputException("Missing or blank trigger inputs: "
                + String.join(", ", missing) + ". Select profiles containing these keys or provide earlier extractors."
                + " Expired Auth State is not used.");
    }

    private static void addOutputs(Map<String, String> available, FlowStep step, boolean requestOnly) {
        for (ExtractRule rule : step.extractRules()) {
            if (!rule.enabled() || (requestOnly && rule.sourceSide() != ExtractRule.SourceSide.REQUEST)) continue;
            String source = requestOnly ? "Live request extract (runtime)" : "Extract: " + step.name() + " (runtime)";
            FlowInputValidator.scopedOutputs(Map.of(rule.targetVariableName(), source), "auth")
                    .forEach(available::put);
        }
    }

    private static void add(Map<String, String> destination, String prefix, Map<String, String> values) {
        values.forEach((key, value) -> destination.put(prefix + key, value));
    }
}
