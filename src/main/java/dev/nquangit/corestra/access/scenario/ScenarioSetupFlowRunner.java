package dev.nquangit.corestra.access.scenario;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.execution.ExecutionContext;
import dev.nquangit.corestra.flow.execution.ExecutionResult;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.flow.execution.StepExecutionRecord;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Runs setup flows or simple linear prefixes to produce scenario variables.
 */
public class ScenarioSetupFlowRunner {
    private static final String SCENARIO_PREFIX = "scenario.";

    private final SetupFlowExecutor setupFlowExecutor;
    private final Clock clock;

    /**
     * Creates a scenario setup flow runner.
     *
     * @param flowEngine flow engine used to execute temporary setup flows
     * @param clock clock used for execution context timestamps
     */
    public ScenarioSetupFlowRunner(FlowEngine flowEngine, Clock clock) {
        this(Objects.requireNonNull(flowEngine, "flowEngine")::execute, clock);
    }

    ScenarioSetupFlowRunner(SetupFlowExecutor setupFlowExecutor, Clock clock) {
        this.setupFlowExecutor = Objects.requireNonNull(setupFlowExecutor, "setupFlowExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Runs a setup flow or setup flow prefix without mutating the source flow.
     *
     * @param flow setup flow definition
     * @param untilBeforeStepId optional step id before which execution should stop
     * @param setupAuthProfile auth profile used for setup execution variables
     * @param dataProfile data profile used for setup execution variables
     * @param env environment variables
     * @param legacy legacy variables
     * @param renderMode render mode requested by the caller
     * @return setup run result
     */
    public ScenarioSetupRunResult runSetup(
            FlowDefinition flow,
            String untilBeforeStepId,
            AuthProfile setupAuthProfile,
            DataProfile dataProfile,
            Map<String, String> env,
            Map<String, String> legacy,
            RenderMode renderMode) {
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(setupAuthProfile, "setupAuthProfile");
        Objects.requireNonNull(renderMode, "renderMode");

        try {
            FlowDefinition setupFlow = copySetupFlow(flow, untilBeforeStepId);
            if (setupFlow.steps().isEmpty()) {
                return ScenarioSetupRunResult.failed("Setup flow has no executable steps", Map.of(), List.of());
            }

            ExecutionContext context = new ExecutionContext(
                    "scenario-" + UUID.randomUUID(),
                    setupFlow.id(),
                    metadata(setupAuthProfile, dataProfile, renderMode),
                    initialVariables(setupAuthProfile, dataProfile, env, legacy),
                    "",
                    Map.of(),
                    clock.millis(),
                    0L);
            ExecutionResult result = setupFlowExecutor.execute(setupFlow, context);
            LinkedHashMap<String, String> rawExtracted = rawExtractedVariables(result);
            ScenarioVariables scenarioVariables = scenarioVariables(rawExtracted);
            if (!result.success()) {
                return ScenarioSetupRunResult.failed(
                        Objects.requireNonNullElse(result.failureMessage(), "Setup flow failed"),
                        rawExtracted,
                        scenarioVariables.warnings());
            }

            List<String> warnings = new ArrayList<>(scenarioVariables.warnings());
            warnings.addAll(stepWarnings(result));
            if (scenarioVariables.variables().isEmpty()) {
                warnings.add("Setup flow completed but produced no scenario variables");
            }
            return ScenarioSetupRunResult.success(
                    "Setup flow executed successfully",
                    scenarioVariables.variables(),
                    rawExtracted,
                    warnings);
        } catch (RuntimeException exception) {
            return ScenarioSetupRunResult.failed("Setup flow failed: " + exception.getMessage(), Map.of(), List.of());
        }
    }

    private FlowDefinition copySetupFlow(FlowDefinition flow, String untilBeforeStepId) {
        List<FlowStep> selectedSteps = selectedSteps(flow, untilBeforeStepId);
        return new FlowDefinition(
                flow.id(),
                flow.name(),
                flow.description(),
                flow.enabled(),
                selectedSteps,
                FlowRuleSet.empty(),
                AggregationPlan.disabled(),
                FlowTriggerConfig.none());
    }

    private List<FlowStep> selectedSteps(FlowDefinition flow, String untilBeforeStepId) {
        String stopStepId = untilBeforeStepId == null ? "" : untilBeforeStepId.strip();
        int exclusiveEnd = flow.steps().size();
        if (!stopStepId.isBlank()) {
            exclusiveEnd = -1;
            for (int index = 0; index < flow.steps().size(); index++) {
                if (flow.steps().get(index).id().equals(stopStepId)) {
                    exclusiveEnd = index;
                    break;
                }
            }
            if (exclusiveEnd < 0) {
                throw new IllegalArgumentException("Setup prefix stop step not found: " + stopStepId);
            }
        }

        List<FlowStep> selected = new ArrayList<>();
        for (int index = 0; index < exclusiveEnd; index++) {
            FlowStep step = flow.steps().get(index);
            if (step.enabled()) {
                selected.add(step);
            }
        }
        return List.copyOf(selected);
    }

    private Map<String, String> metadata(AuthProfile setupAuthProfile, DataProfile dataProfile, RenderMode renderMode) {
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        metadata.put("setupAuthProfileId", setupAuthProfile.id());
        metadata.put("renderMode", renderMode.name());
        if (dataProfile != null) {
            metadata.put("dataProfileId", dataProfile.id());
        }
        return Map.copyOf(metadata);
    }

    private Map<String, String> initialVariables(
            AuthProfile setupAuthProfile,
            DataProfile dataProfile,
            Map<String, String> env,
            Map<String, String> legacy) {
        LinkedHashMap<String, String> variables = new LinkedHashMap<>();
        addFlatAndPrefixed(variables, "", env);
        addFlatAndPrefixed(variables, "env.", env);
        if (dataProfile != null) {
            addFlatAndPrefixed(variables, "", dataProfile.values());
            addFlatAndPrefixed(variables, "data.", dataProfile.values());
            variables.put("dataProfileId", dataProfile.id());
            variables.put("data.ownerAuthProfileId", dataProfile.ownerAuthProfileId());
        }
        addFlatAndPrefixed(variables, "", setupAuthProfile.credentials());
        addFlatAndPrefixed(variables, "credentials.", setupAuthProfile.credentials());
        addFlatAndPrefixed(variables, "", setupAuthProfile.state().variables());
        addFlatAndPrefixed(variables, "auth.", setupAuthProfile.state().variables());
        addFlatAndPrefixed(variables, "auth.header.", setupAuthProfile.state().headers());
        addFlatAndPrefixed(variables, "auth.cookie.", setupAuthProfile.state().cookies());
        addFlatAndPrefixed(variables, "", legacy);
        addFlatAndPrefixed(variables, "legacy.", legacy);
        variables.put("authProfileId", setupAuthProfile.id());
        variables.put("authProviderId", setupAuthProfile.providerId());
        return Map.copyOf(variables);
    }

    private void addFlatAndPrefixed(Map<String, String> target, String prefix, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        values.forEach((key, value) -> {
            if (key != null && !key.isBlank()) {
                target.put(prefix + key.strip(), Objects.requireNonNullElse(value, ""));
            }
        });
    }

    private LinkedHashMap<String, String> rawExtractedVariables(ExecutionResult result) {
        LinkedHashMap<String, String> extracted = new LinkedHashMap<>();
        for (StepExecutionRecord record : result.stepRecords()) {
            extracted.putAll(record.extractedVariables());
        }
        return extracted;
    }

    private ScenarioVariables scenarioVariables(Map<String, String> rawExtractedVariables) {
        LinkedHashMap<String, String> variables = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        rawExtractedVariables.forEach((key, value) -> {
            String normalizedKey = key == null ? "" : key.strip();
            if (normalizedKey.isBlank()) {
                return;
            }
            if (normalizedKey.startsWith(SCENARIO_PREFIX)) {
                String scenarioKey = normalizedKey.substring(SCENARIO_PREFIX.length());
                if (ScenarioVariable.isValidKey(scenarioKey)) {
                    variables.put(scenarioKey, Objects.requireNonNullElse(value, ""));
                } else {
                    warnings.add("Setup extracted invalid scenario variable key: " + normalizedKey);
                }
                return;
            }
            if (knownScopedPrefix(normalizedKey)) {
                warnings.add("Setup extracted non-scenario scoped variable and skipped it: " + normalizedKey);
                return;
            }
            if (ScenarioVariable.isValidKey(normalizedKey)) {
                variables.put(normalizedKey, Objects.requireNonNullElse(value, ""));
                warnings.add("Setup extracted plain variable as scenario." + normalizedKey);
            } else {
                warnings.add("Setup extracted invalid scenario variable key: " + normalizedKey);
            }
        });
        return new ScenarioVariables(Map.copyOf(variables), List.copyOf(warnings));
    }

    private boolean knownScopedPrefix(String key) {
        return key.startsWith("env.")
                || key.startsWith("auth.")
                || key.startsWith("data.")
                || key.startsWith("legacy.")
                || key.startsWith("credentials.")
                || key.startsWith("runtime.");
    }

    private List<String> stepWarnings(ExecutionResult result) {
        return result.stepRecords().stream()
                .filter(record -> !record.success())
                .map(record -> "Setup step " + record.stepId() + " failed: " + record.errorMessage())
                .toList();
    }

    private record ScenarioVariables(Map<String, String> variables, List<String> warnings) {
        private ScenarioVariables {
            variables = Map.copyOf(Objects.requireNonNull(variables, "variables"));
            warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        }
    }

    @FunctionalInterface
    interface SetupFlowExecutor {
        ExecutionResult execute(FlowDefinition flow, ExecutionContext context);
    }
}
