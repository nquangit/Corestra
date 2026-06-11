package dev.nquangit.corestra.access.auth.runtime;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.execution.ExecutionContext;
import dev.nquangit.corestra.flow.execution.ExecutionResult;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.flow.execution.StepExecutionRecord;
import dev.nquangit.corestra.persistence.FlowRepository;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Executes configured AuthProvider flows through the existing FlowEngine.
 */
public final class AuthFlowExecutor implements AuthFlowRunner {
    private final FlowRepository flowRepository;
    private final FlowEngine flowEngine;
    private final Clock clock;

    /**
     * Creates an auth flow executor.
     *
     * @param flowRepository repository used to look up flow definitions
     * @param flowEngine flow engine used to execute definitions
     * @param clock clock used for execution context timestamps
     */
    public AuthFlowExecutor(FlowRepository flowRepository, FlowEngine flowEngine, Clock clock) {
        this.flowRepository = Objects.requireNonNull(flowRepository, "flowRepository");
        this.flowEngine = Objects.requireNonNull(flowEngine, "flowEngine");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Runs an auth flow by id.
     *
     * @param flowId flow id to run
     * @param profile auth profile
     * @param provider auth provider
     * @param env environment variables
     * @param purpose flow purpose
     * @param renderMode render mode requested by the caller
     * @return auth flow run result
     */
    @Override
    public AuthFlowRunResult runAuthFlow(
            String flowId,
            AuthProfile profile,
            AuthProvider provider,
            Map<String, String> env,
            AuthFlowPurpose purpose,
            RenderMode renderMode) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(purpose, "purpose");
        if (flowId == null || flowId.isBlank()) {
            return AuthFlowRunResult.notExecuted("No " + purposeName(purpose) + " flow configured");
        }

        Optional<FlowDefinition> flow = flowRepository.loadAll().stream()
                .filter(candidate -> candidate.id().equals(flowId.strip()))
                .findFirst();
        if (flow.isEmpty()) {
            return AuthFlowRunResult.notExecuted("Auth flow not found: " + flowId.strip());
        }
        if (!flow.get().enabled()) {
            return AuthFlowRunResult.notExecuted("Auth flow is disabled: " + flowId.strip());
        }

        try {
            ExecutionContext context = new ExecutionContext(
                    "auth-" + purpose.name().toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID(),
                    flow.get().id(),
                    metadata(profile, provider, purpose),
                    initialVariables(profile, env),
                    "",
                    Map.of(),
                    clock.millis(),
                    0L);
            ExecutionResult result = flowEngine.execute(flow.get(), context);
            return toRunResult(result);
        } catch (RuntimeException exception) {
            return AuthFlowRunResult.executed(
                    false,
                    "Auth flow failed: " + exception.getMessage(),
                    0,
                    Map.of(),
                    "",
                    List.of());
        }
    }

    private AuthFlowRunResult toRunResult(ExecutionResult result) {
        LinkedHashMap<String, String> extracted = new LinkedHashMap<>();
        int statusCode = 0;
        String finalResponse = "";
        for (StepExecutionRecord record : result.stepRecords()) {
            extracted.putAll(record.extractedVariables());
            statusCode = record.responseStatusCode();
            finalResponse = record.receivedResponse();
        }
        String reason = result.success()
                ? "Auth flow executed successfully"
                : Objects.requireNonNullElse(result.failureMessage(), "Auth flow failed");
        return AuthFlowRunResult.executed(
                result.success(),
                reason,
                statusCode,
                extracted,
                finalResponse,
                warnings(result));
    }

    private List<String> warnings(ExecutionResult result) {
        return result.stepRecords().stream()
                .filter(record -> !record.success())
                .map(record -> "Step " + record.stepId() + " failed: " + record.errorMessage())
                .toList();
    }

    private Map<String, String> metadata(AuthProfile profile, AuthProvider provider, AuthFlowPurpose purpose) {
        return Map.of(
                "authProfileId", profile.id(),
                "authProviderId", provider.id(),
                "authFlowPurpose", purpose.name());
    }

    private Map<String, String> initialVariables(AuthProfile profile, Map<String, String> env) {
        LinkedHashMap<String, String> variables = new LinkedHashMap<>();
        addFlatAndPrefixed(variables, "", env);
        addFlatAndPrefixed(variables, "env.", env);
        addFlatAndPrefixed(variables, "", profile.credentials());
        addFlatAndPrefixed(variables, "credentials.", profile.credentials());
        addFlatAndPrefixed(variables, "", profile.state().variables());
        addFlatAndPrefixed(variables, "auth.", profile.state().variables());
        addFlatAndPrefixed(variables, "auth.header.", profile.state().headers());
        addFlatAndPrefixed(variables, "auth.cookie.", profile.state().cookies());
        variables.put("authProfileId", profile.id());
        variables.put("authProviderId", profile.providerId());
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

    private static String purposeName(AuthFlowPurpose purpose) {
        return purpose.name().toLowerCase(Locale.ROOT);
    }
}
