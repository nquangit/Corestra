package dev.nquangit.corestra.access.auth.analysis;

import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthInjectionLocation;
import dev.nquangit.corestra.access.auth.AuthInjectionRule;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.auth.runtime.AuthFlowPurpose;
import dev.nquangit.corestra.access.variable.DefaultRuntimeValueProvider;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.ScopedVariableStore;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.access.variable.VariableErrorType;
import dev.nquangit.corestra.access.variable.VariableResolver;
import dev.nquangit.corestra.access.variable.VariableScope;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Derives and validates the variable contract between Flowise auth flows and an AuthProvider.
 */
public final class AuthProviderFlowAnalyzer {
    private static final Set<String> EXPIRY_KEYS = Set.of("expiresAt", "expiresIn", "expires_in");
    private final TemplateRenderer templateRenderer = new TemplateRenderer(
            new VariableResolver(new DefaultRuntimeValueProvider()));

    /**
     * Analyzes selected flows and provider injection rules without executing network requests.
     *
     * @param provider provider being edited or validated
     * @param flows available Flowise definitions
     * @return derived flow contract and diagnostics
     */
    public AuthProviderFlowAnalysis analyze(AuthProvider provider, List<FlowDefinition> flows) {
        Objects.requireNonNull(provider, "provider");
        Map<String, FlowDefinition> byId = new LinkedHashMap<>();
        if (flows != null) {
            for (FlowDefinition flow : flows) {
                if (flow != null) {
                    byId.put(flow.id(), flow);
                }
            }
        }

        ArrayList<AuthFlowOutput> outputs = new ArrayList<>();
        LinkedHashSet<String> requiredInputs = new LinkedHashSet<>();
        ArrayList<AuthProviderFlowIssue> issues = new ArrayList<>();
        EnumMap<AuthFlowPurpose, FlowDefinition> selected = new EnumMap<>(AuthFlowPurpose.class);

        resolveFlow(provider.loginFlowId(), AuthFlowPurpose.LOGIN, requiresLogin(provider), byId, selected, issues);
        resolveFlow(provider.refreshFlowId(), AuthFlowPurpose.REFRESH, requiresRefresh(provider), byId, selected, issues);
        resolveFlow(provider.validateFlowId(), AuthFlowPurpose.VALIDATE, requiresValidate(provider), byId, selected, issues);
        resolveFlow(provider.keepAlive().flowId(), AuthFlowPurpose.KEEP_ALIVE, false, byId, selected, issues);
        if (provider.keepAlive().enabled()) {
            if (provider.keepAlive().successRules().stream().noneMatch(rule -> rule.enabled())) {
                issues.add(new AuthProviderFlowIssue(Severity.ERROR, "Keep-alive success rule is required",
                        "Add a rule that confirms the existing session is alive. Login success rules are separate."));
            }
            if (provider.keepAlive().expiredRules().stream().noneMatch(rule -> rule.enabled())) {
                issues.add(new AuthProviderFlowIssue(Severity.WARNING, "Keep-alive will not re-authenticate on failure",
                        "No session-expired rule is enabled. Unconfirmed responses only retry with backoff."));
            }
            provider.keepAlive().successRules().stream().filter(rule -> rule.enabled()).forEach(rule ->
                    validateKeepAliveRule(rule.type(), rule.expression(), rule.expectedValue(), issues));
            provider.keepAlive().expiredRules().stream().filter(rule -> rule.enabled()).forEach(rule ->
                    validateKeepAliveRule(rule.type(), rule.expression(), rule.expectedValue(), issues));
        }

        for (Map.Entry<AuthFlowPurpose, FlowDefinition> entry : selected.entrySet()) {
            analyzeFlow(entry.getKey(), entry.getValue(), outputs, requiredInputs, issues);
        }

        LinkedHashSet<String> producedKeys = new LinkedHashSet<>();
        outputs.forEach(output -> producedKeys.add(output.key()));
        validateInjectionRules(provider.injectionRules(), producedKeys, issues);
        validateRefreshInputs(selected, outputs, issues);
        if (provider.stateTtlSeconds() == 0L
                && producedKeys.stream().noneMatch(EXPIRY_KEYS::contains)
                && (requiresLogin(provider) || requiresRefresh(provider))) {
            issues.add(new AuthProviderFlowIssue(
                    Severity.WARNING,
                    "Auth state has no reuse window",
                    "TTL is 0 and the selected auth flows do not extract expiresAt, expiresIn, or expires_in. "
                            + "Authentication will run again whenever state is required."));
        }

        List<AuthInjectionSuggestion> suggestions = suggestions(outputs);
        return new AuthProviderFlowAnalysis(outputs, List.copyOf(requiredInputs), suggestions, issues);
    }

    private void validateKeepAliveRule(dev.nquangit.corestra.access.auth.AuthRuleType type,
                                      String expression, String expectedValue,
                                      List<AuthProviderFlowIssue> issues) {
        String text = expectedValue.isBlank() ? expression : expectedValue;
        boolean valid = !text.isBlank();
        switch (type) {
            case STATUS_CODE -> {
                try {
                    int code = Integer.parseInt(text.strip());
                    valid = code >= 100 && code <= 599;
                } catch (NumberFormatException ignored) { valid = false; }
            }
            case HEADER_EXISTS -> valid = !expression.isBlank();
            case JSON_PATH, CUSTOM -> valid = false;
            case BODY_CONTAINS, BODY_NOT_CONTAINS -> { }
        }
        if (!valid) {
            issues.add(new AuthProviderFlowIssue(Severity.ERROR, "Invalid keep-alive outcome rule",
                    "Use a supported rule type with a non-empty expression; status codes must be 100–599."));
        }
    }

    private void resolveFlow(
            String flowId,
            AuthFlowPurpose purpose,
            boolean required,
            Map<String, FlowDefinition> byId,
            Map<AuthFlowPurpose, FlowDefinition> selected,
            List<AuthProviderFlowIssue> issues) {
        String normalized = flowId == null ? "" : flowId.strip();
        if (normalized.isBlank()) {
            if (required) {
                issues.add(new AuthProviderFlowIssue(
                        Severity.ERROR,
                        label(purpose) + " Flow is required",
                        "The selected fallback or retry policy requires a " + label(purpose).toLowerCase(Locale.ROOT)
                                + " flow."));
            }
            return;
        }
        FlowDefinition flow = byId.get(normalized);
        if (flow == null) {
            issues.add(new AuthProviderFlowIssue(
                    Severity.ERROR,
                    label(purpose) + " Flow is missing",
                    "Flow '" + normalized + "' does not exist in Flowise."));
            return;
        }
        selected.put(purpose, flow);
        if (!flow.enabled()) {
            issues.add(new AuthProviderFlowIssue(
                    Severity.ERROR,
                    label(purpose) + " Flow is disabled",
                    "Flow '" + flow.name() + "' must be enabled before this provider can use it."));
        }
    }

    private void analyzeFlow(
            AuthFlowPurpose purpose,
            FlowDefinition flow,
            List<AuthFlowOutput> outputs,
            Set<String> requiredInputs,
            List<AuthProviderFlowIssue> issues) {
        LinkedHashSet<String> availableInsideFlow = new LinkedHashSet<>();
        LinkedHashMap<String, AuthFlowOutput> producedInFlow = new LinkedHashMap<>();
        for (FlowStep step : flow.steps()) {
            if (!step.enabled()) {
                continue;
            }
            collectStepInputs(step, availableInsideFlow, requiredInputs, issues, flow);
            for (ExtractRule rule : step.extractRules()) {
                if (!rule.enabled()) {
                    continue;
                }
                String originalKey = rule.targetVariableName().strip();
                if (originalKey.equals("auth.")) {
                    issues.add(new AuthProviderFlowIssue(
                            Severity.ERROR,
                            "Invalid Auth State extractor key",
                            "Flow '" + flow.name() + "', step '" + step.name()
                                    + "' uses 'auth.' without a variable name."));
                    continue;
                }
                String key = canonicalOutputKey(originalKey);
                if (originalKey.startsWith("auth.")) {
                    issues.add(new AuthProviderFlowIssue(
                            Severity.WARNING,
                            "Legacy auth prefix in extractor",
                            "Flow '" + flow.name() + "', step '" + step.name() + "' extracts '" + originalKey
                                    + "'. Prefer '" + key + "'; consume it later as {{auth." + key + "}}."));
                } else if (hasNonAuthScope(originalKey)) {
                    issues.add(new AuthProviderFlowIssue(
                            Severity.WARNING,
                            "Scoped extractor is not Auth State",
                            "Flow '" + flow.name() + "', step '" + step.name() + "' extracts '" + originalKey
                                    + "'. Auth State outputs should use an unscoped key such as 'accessToken'."));
                    availableInsideFlow.add(originalKey);
                    continue;
                }
                AuthFlowOutput output = new AuthFlowOutput(
                        purpose,
                        flow.id(),
                        flow.name(),
                        step.id(),
                        step.name(),
                        rule.id(),
                        rule.name(),
                        key,
                        originalKey,
                        rule.extractorType(),
                        rule.sourceSelector());
                AuthFlowOutput previous = producedInFlow.put(key, output);
                if (previous != null) {
                    issues.add(new AuthProviderFlowIssue(
                            Severity.WARNING,
                            "Duplicate auth output",
                            "Flow '" + flow.name() + "' extracts '" + key + "' more than once. The later value from step '"
                                    + step.name() + "' overwrites the earlier value from step '" + previous.stepName() + "'."));
                    outputs.remove(previous);
                }
                outputs.add(output);
                availableInsideFlow.add(originalKey);
                availableInsideFlow.add(key);
                availableInsideFlow.add("auth." + key);
            }
        }
        if ((purpose == AuthFlowPurpose.LOGIN || purpose == AuthFlowPurpose.REFRESH) && producedInFlow.isEmpty()) {
            issues.add(new AuthProviderFlowIssue(
                    Severity.WARNING,
                    label(purpose) + " Flow produces no Auth State",
                    "Flow '" + flow.name() + "' has no enabled unscoped/auth-prefixed extractors."));
        }
    }

    private void collectStepInputs(
            FlowStep step,
            Set<String> availableInsideFlow,
            Set<String> requiredInputs,
            List<AuthProviderFlowIssue> issues,
            FlowDefinition flow) {
        for (InjectRule rule : step.injectRules()) {
            if (!rule.enabled() || rule.valueSourceType() != InjectRule.ValueSourceType.VARIABLE) {
                continue;
            }
            String key = rule.valueSourceKey();
            if (!availableInsideFlow.contains(key)) {
                requiredInputs.add(key);
            }
        }
        StoredRequestTemplate request = step.requestTemplate();
        ArrayList<String> templates = new ArrayList<>();
        templates.add(request.pathAndQuery());
        templates.addAll(request.headerLines());
        templates.add(request.body());
        for (String template : templates) {
            templateRenderer.findVariableRefs(template, RenderMode.STRICT).stream()
                    .map(ScopedVariableRef::scopedKey)
                    .filter(key -> !availableInsideFlow.contains(key))
                    .forEach(requiredInputs::add);
            templateRenderer.render(template, ScopedVariableStore.empty(), RenderMode.STRICT)
                    .unresolvedVariables().stream()
                    .filter(unresolved -> unresolved.errorType() != VariableErrorType.MISSING_VARIABLE)
                    .forEach(unresolved -> issues.add(new AuthProviderFlowIssue(
                            Severity.ERROR,
                            "Malformed flow placeholder",
                            "Flow '" + flow.name() + "', step '" + step.name() + "': " + unresolved.message() + ".")));
        }
    }

    private void validateInjectionRules(
            List<AuthInjectionRule> rules,
            Set<String> producedKeys,
            List<AuthProviderFlowIssue> issues) {
        for (AuthInjectionRule rule : rules) {
            if (!rule.enabled()) {
                continue;
            }
            if (rule.target().isBlank()) {
                issues.add(new AuthProviderFlowIssue(Severity.ERROR, "Injection target is blank",
                        "Rule '" + rule.name() + "' needs a header, cookie, or query target."));
            }
            if (rule.valueTemplate().isBlank()) {
                issues.add(new AuthProviderFlowIssue(Severity.ERROR, "Injection value is blank",
                        "Rule '" + rule.name() + "' needs a value template."));
                continue;
            }
            templateRenderer.render(rule.valueTemplate(), ScopedVariableStore.empty(), RenderMode.STRICT)
                    .unresolvedVariables().stream()
                    .filter(unresolved -> unresolved.errorType() != VariableErrorType.MISSING_VARIABLE)
                    .forEach(unresolved -> issues.add(new AuthProviderFlowIssue(
                            Severity.ERROR,
                            "Invalid injection template",
                            "Rule '" + rule.name() + "': " + unresolved.message() + ".")));
            for (ScopedVariableRef ref : templateRenderer.findVariableRefs(rule.valueTemplate(), RenderMode.STRICT)) {
                if (ref.scope() == VariableScope.AUTH && !producedKeys.contains(ref.key())) {
                    issues.add(new AuthProviderFlowIssue(
                            Severity.WARNING,
                            "Auth variable is not produced by selected flows",
                            "Rule '" + rule.name() + "' uses {{" + ref.scopedKey() + "}}, but no selected flow extracts '"
                                    + ref.key() + "'. It must be supplied by existing/manual Auth State."));
                }
            }
        }
    }

    private void validateRefreshInputs(
            Map<AuthFlowPurpose, FlowDefinition> selected,
            List<AuthFlowOutput> outputs,
            List<AuthProviderFlowIssue> issues) {
        validateStateInputs(AuthFlowPurpose.REFRESH, selected, outputs, issues);
        validateStateInputs(AuthFlowPurpose.KEEP_ALIVE, selected, outputs, issues);
    }

    private void validateStateInputs(AuthFlowPurpose purpose, Map<AuthFlowPurpose, FlowDefinition> selected,
                                    List<AuthFlowOutput> outputs, List<AuthProviderFlowIssue> issues) {
        FlowDefinition refresh = selected.get(purpose);
        if (refresh == null) {
            return;
        }
        Set<String> loginOutputs = outputs.stream()
                .filter(output -> output.purpose() == AuthFlowPurpose.LOGIN)
                .map(AuthFlowOutput::key)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        LinkedHashSet<String> refreshInputs = new LinkedHashSet<>();
        LinkedHashSet<String> producedInsideRefresh = new LinkedHashSet<>();
        for (FlowStep step : refresh.steps()) {
            if (!step.enabled()) continue;
            step.injectRules().stream()
                    .filter(InjectRule::enabled)
                    .filter(rule -> rule.valueSourceType() == InjectRule.ValueSourceType.VARIABLE)
                    .map(InjectRule::valueSourceKey)
                    .filter(key -> key.startsWith("auth."))
                    .map(key -> key.substring("auth.".length()))
                    .filter(key -> !producedInsideRefresh.contains(key))
                    .forEach(refreshInputs::add);
            step.extractRules().stream()
                    .filter(ExtractRule::enabled)
                    .map(ExtractRule::targetVariableName)
                    .filter(key -> !hasNonAuthScope(key))
                    .map(AuthProviderFlowAnalyzer::canonicalOutputKey)
                    .forEach(producedInsideRefresh::add);
        }
        for (String key : refreshInputs) {
            if (!loginOutputs.contains(key)) {
                issues.add(new AuthProviderFlowIssue(
                        Severity.WARNING,
                        label(purpose) + " input is not produced by Login Flow",
                        label(purpose) + " Flow requires auth." + key + ", but Login Flow does not extract '" + key
                                + "'. Existing/manual Auth State must provide it."));
            }
        }
    }

    private List<AuthInjectionSuggestion> suggestions(List<AuthFlowOutput> outputs) {
        LinkedHashMap<String, AuthInjectionSuggestion> suggestions = new LinkedHashMap<>();
        for (AuthFlowOutput output : outputs) {
            String lower = output.key().toLowerCase(Locale.ROOT);
            AuthInjectionSuggestion suggestion = null;
            if (output.extractorType() == ExtractRule.ExtractorType.COOKIE && !output.sourceSelector().isBlank()) {
                suggestion = new AuthInjectionSuggestion(
                        AuthInjectionLocation.COOKIE,
                        output.sourceSelector(),
                        "{{auth." + output.key() + "}}",
                        "Cookie extracted by " + output.flowName() + " / " + output.stepName());
            } else if (lower.contains("csrf") || lower.contains("xsrf")) {
                suggestion = new AuthInjectionSuggestion(
                        AuthInjectionLocation.HEADER,
                        "X-CSRF-Token",
                        "{{auth." + output.key() + "}}",
                        "CSRF-like output '" + output.key() + "'");
            } else if (lower.equals("accesstoken") || lower.equals("token") || lower.contains("jwt")) {
                suggestion = new AuthInjectionSuggestion(
                        AuthInjectionLocation.HEADER,
                        "Authorization",
                        "Bearer {{auth." + output.key() + "}}",
                        "Bearer-token-like output '" + output.key() + "'");
            }
            if (suggestion != null) {
                suggestions.put(suggestion.location() + "\n" + suggestion.target(), suggestion);
            }
        }
        return List.copyOf(suggestions.values());
    }

    private static String canonicalOutputKey(String key) {
        return AuthState.normalizeVariables(Map.of(key, "")).keySet().iterator().next();
    }

    private static boolean hasNonAuthScope(String key) {
        int separator = key.indexOf('.');
        if (separator <= 0) {
            return false;
        }
        String prefix = key.substring(0, separator);
        return VariableScope.fromPrefix(prefix).filter(scope -> scope != VariableScope.AUTH).isPresent();
    }

    private static boolean requiresLogin(AuthProvider provider) {
        return provider.fallbackPolicy() == AuthFallbackPolicy.LOGIN_ONLY
                || provider.fallbackPolicy() == AuthFallbackPolicy.REFRESH_THEN_LOGIN
                || provider.fallbackPolicy() == AuthFallbackPolicy.VALIDATE_THEN_REFRESH_THEN_LOGIN
                || provider.targetRetryPolicy() == AuthRetryPolicy.LOGIN_AND_RETRY_ONCE
                || provider.targetRetryPolicy() == AuthRetryPolicy.REFRESH_THEN_LOGIN_AND_RETRY_ONCE;
    }

    private static boolean requiresRefresh(AuthProvider provider) {
        return provider.fallbackPolicy() == AuthFallbackPolicy.REFRESH_THEN_LOGIN
                || provider.fallbackPolicy() == AuthFallbackPolicy.VALIDATE_THEN_REFRESH_THEN_LOGIN
                || provider.targetRetryPolicy() == AuthRetryPolicy.REFRESH_AND_RETRY_ONCE
                || provider.targetRetryPolicy() == AuthRetryPolicy.REFRESH_THEN_LOGIN_AND_RETRY_ONCE;
    }

    private static boolean requiresValidate(AuthProvider provider) {
        return provider.fallbackPolicy() == AuthFallbackPolicy.VALIDATE_THEN_REFRESH_THEN_LOGIN;
    }

    private static String label(AuthFlowPurpose purpose) {
        if (purpose == AuthFlowPurpose.KEEP_ALIVE) return "Keep-alive";
        String lower = purpose.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** Severity of an AuthProvider flow-contract diagnostic. */
    public enum Severity { ERROR, WARNING }

    /** One variable produced by an enabled extractor in a selected auth flow. */
    public record AuthFlowOutput(
            AuthFlowPurpose purpose,
            String flowId,
            String flowName,
            String stepId,
            String stepName,
            String ruleId,
            String ruleName,
            String key,
            String originalKey,
            ExtractRule.ExtractorType extractorType,
            String sourceSelector) {
        /** Creates an immutable output description. */
        public AuthFlowOutput {
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(flowId, "flowId");
            Objects.requireNonNull(flowName, "flowName");
            Objects.requireNonNull(stepId, "stepId");
            Objects.requireNonNull(stepName, "stepName");
            Objects.requireNonNull(ruleId, "ruleId");
            Objects.requireNonNull(ruleName, "ruleName");
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(originalKey, "originalKey");
            Objects.requireNonNull(extractorType, "extractorType");
            sourceSelector = Objects.requireNonNullElse(sourceSelector, "");
        }
    }

    /** Suggested target injection derived from extractor metadata and conventional variable names. */
    public record AuthInjectionSuggestion(
            AuthInjectionLocation location,
            String target,
            String valueTemplate,
            String reason) {
        /** Creates an immutable suggestion. */
        public AuthInjectionSuggestion {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(valueTemplate, "valueTemplate");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** One actionable contract diagnostic. */
    public record AuthProviderFlowIssue(Severity severity, String title, String detail) {
        /** Creates an immutable diagnostic. */
        public AuthProviderFlowIssue {
            Objects.requireNonNull(severity, "severity");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** Complete read-only analysis for one AuthProvider configuration. */
    public record AuthProviderFlowAnalysis(
            List<AuthFlowOutput> outputs,
            List<String> requiredInputs,
            List<AuthInjectionSuggestion> suggestions,
            List<AuthProviderFlowIssue> issues) {
        /** Creates an immutable analysis. */
        public AuthProviderFlowAnalysis {
            outputs = List.copyOf(outputs);
            requiredInputs = List.copyOf(requiredInputs);
            suggestions = List.copyOf(suggestions);
            issues = List.copyOf(issues);
        }

        /** Returns whether at least one blocking error exists. */
        public boolean hasErrors() {
            return issues.stream().anyMatch(issue -> issue.severity() == Severity.ERROR);
        }

        /** Returns unique canonical Auth State keys in flow order. */
        public List<String> outputKeys() {
            return outputs.stream().map(AuthFlowOutput::key).distinct().toList();
        }
    }
}
