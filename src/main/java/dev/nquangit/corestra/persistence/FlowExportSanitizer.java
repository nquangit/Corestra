package dev.nquangit.corestra.persistence;

import dev.nquangit.corestra.access.security.RedactionService;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.InjectRule;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Removes secrets from persisted Flowise snapshots used by safe project exports.
 */
public final class FlowExportSanitizer {
    private static final Pattern SAFE_TEMPLATE_VALUE = Pattern.compile(
            "(?i)^\\s*(?:(?:Bearer|Basic|Token)\\s+)?\\{\\{[^{}]+}}\\s*$");

    private final RedactionService redactionService;

    /**
     * Creates a flow export sanitizer.
     *
     * @param redactionService configured redaction service
     */
    public FlowExportSanitizer(RedactionService redactionService) {
        this.redactionService = Objects.requireNonNull(redactionService, "redactionService");
    }

    /**
     * Sanitizes a complete flow while preserving its executable structure.
     *
     * @param flow flow to sanitize
     * @param excludeSecrets whether secret values should be removed instead of replaced
     * @return sanitized flow
     */
    public FlowDefinition sanitizeFlow(FlowDefinition flow, boolean excludeSecrets) {
        FlowRuleSet rules = new FlowRuleSet(
                flow.rules().sharedExtractRules(),
                flow.rules().sharedInjectRules().stream()
                        .map(rule -> sanitizeInjectRule(rule, excludeSecrets))
                        .toList());
        return new FlowDefinition(
                flow.id(),
                flow.name(),
                redactionService.redactInlineSecrets(flow.description()),
                flow.enabled(),
                flow.steps().stream().map(step -> sanitizeStep(step, excludeSecrets)).toList(),
                rules,
                flow.aggregation(),
                flow.triggerConfig());
    }

    /**
     * Sanitizes a stored HTTP request.
     *
     * @param request request template
     * @param excludeSecrets whether detected secrets should be removed
     * @return sanitized request template
     */
    public StoredRequestTemplate sanitizeRequest(StoredRequestTemplate request, boolean excludeSecrets) {
        String path = redactionService.redactInlineSecrets(request.pathAndQuery());
        String body = redactionService.redactInlineSecrets(request.body());
        if (excludeSecrets) {
            path = removeReplacement(path);
            body = removeReplacement(body);
        }
        return new StoredRequestTemplate(
                request.method(),
                request.scheme(),
                request.host(),
                request.port(),
                path,
                excludeSecrets
                        ? redactionService.redactHeaders(request.headerLines()).stream()
                                .map(this::removeReplacement).toList()
                        : redactionService.redactHeaders(request.headerLines()),
                body);
    }

    /**
     * Sanitizes a configured value whose owning field may carry a secret.
     * Scoped placeholder-only templates remain intact because they contain no resolved value.
     *
     * @param key semantic key or target name
     * @param value configured value
     * @param excludeSecrets whether secret values should be removed
     * @param forceSensitive whether an opaque literal must be treated as secret
     * @return safe configured value
     */
    public String sanitizeConfiguredValue(
            String key, String value, boolean excludeSecrets, boolean forceSensitive) {
        String source = Objects.requireNonNullElse(value, "");
        if (source.isEmpty()) {
            return "";
        }
        if (SAFE_TEMPLATE_VALUE.matcher(source).matches()) {
            return source;
        }
        String inlineRedacted = redactionService.redactInlineSecrets(source);
        boolean sensitive = forceSensitive || redactionService.isSensitiveKey(key);
        if (sensitive && inlineRedacted.equals(source)) {
            return excludeSecrets ? "" : "<redacted>";
        }
        return excludeSecrets ? removeReplacement(inlineRedacted) : inlineRedacted;
    }

    private FlowStep sanitizeStep(FlowStep step, boolean excludeSecrets) {
        return new FlowStep(
                step.id(),
                step.name(),
                step.enabled(),
                redactionService.redactInlineSecrets(step.notes()),
                sanitizeRequest(step.requestTemplate(), excludeSecrets),
                step.extractRules(),
                step.injectRules().stream().map(rule -> sanitizeInjectRule(rule, excludeSecrets)).toList(),
                step.preconditions(),
                step.errorStrategy(),
                null,
                step.triggerRule(),
                step.includeInAggregation(),
                step.conditionActionRules(), step.sendModeOverride(), step.manualInjectRulesEnabled());
    }

    private InjectRule sanitizeInjectRule(InjectRule rule, boolean excludeSecrets) {
        String staticValue = rule.staticValue();
        if (rule.valueSourceType() != InjectRule.ValueSourceType.VARIABLE) {
            staticValue = sanitizeConfiguredValue(
                    rule.targetSelector(), staticValue, excludeSecrets, true);
        }
        return new InjectRule(
                rule.id(), rule.name(), rule.enabled(), rule.target(), rule.targetSelector(),
                rule.valueSourceType(), rule.valueSourceKey(), staticValue, rule.writeStrategy(),
                rule.transformChain(), rule.ruleType(), rule.interactiveInput());
    }

    private String removeReplacement(String value) {
        return Objects.requireNonNullElse(value, "").replace("<redacted>", "");
    }
}
