package dev.nquangit.corestra.access.dependency;

import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.VariableScope;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Derives variable dependency metadata from flow steps, request templates, and extraction rules.
 */
public final class FlowDependencyAnalyzer {
    private static final Pattern KEY_SEGMENT = Pattern.compile("[A-Za-z0-9_-]+");

    /**
     * Analyzes a flow using only data embedded in the flow steps.
     *
     * @param flow flow definition
     * @param mode render mode for compatibility handling
     * @return dependency graph
     */
    public FlowDependencyGraph analyze(FlowDefinition flow, RenderMode mode) {
        return analyze(flow, List.of(), mode);
    }

    /**
     * Analyzes a flow with optional access request template metadata.
     *
     * @param flow flow definition
     * @param accessTemplates templates whose source flow/step may add placeholder metadata
     * @param mode render mode for compatibility handling
     * @return dependency graph
     */
    public FlowDependencyGraph analyze(
            FlowDefinition flow,
            List<AccessRequestTemplate> accessTemplates,
            RenderMode mode) {
        Objects.requireNonNull(flow, "flow");
        RenderMode renderMode = Objects.requireNonNullElse(mode, RenderMode.STRICT);
        List<String> warnings = new ArrayList<>();
        List<FlowStepVariableMetadata> metadata = new ArrayList<>();
        for (FlowStep step : flow.steps()) {
            LinkedHashSet<ScopedVariableRef> requires = new LinkedHashSet<>();
            LinkedHashSet<ScopedVariableRef> produces = new LinkedHashSet<>();
            requires.addAll(templatePlaceholders(flow.id(), step.id(), accessTemplates));
            requires.addAll(requestTemplateRefs(flow.id(), step, renderMode));
            requires.addAll(injectRuleRefs(step, renderMode, warnings));
            produces.addAll(extractRuleRefs(step, renderMode, warnings));
            metadata.add(new FlowStepVariableMetadata(flow.id(), step.id(), requires, produces));
        }
        warnings.addAll(requestTemplateWarnings(flow));
        return new FlowDependencyGraph(flow.id(), metadata, warnings);
    }

    private Set<ScopedVariableRef> templatePlaceholders(
            String flowId,
            String stepId,
            List<AccessRequestTemplate> accessTemplates) {
        if (accessTemplates == null || accessTemplates.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<ScopedVariableRef> refs = new LinkedHashSet<>();
        accessTemplates.stream()
                .filter(AccessRequestTemplate::enabled)
                .filter(template -> flowId.equals(template.sourceFlowId()))
                .filter(template -> stepId.equals(template.sourceStepId()))
                .forEach(template -> refs.addAll(template.placeholders()));
        return Set.copyOf(refs);
    }

    private Set<ScopedVariableRef> requestTemplateRefs(String flowId, FlowStep step, RenderMode mode) {
        StoredRequestTemplate request = step.requestTemplate();
        LinkedHashSet<ScopedVariableRef> refs = new LinkedHashSet<>();
        refs.addAll(findRefs(request.pathAndQuery(), mode, flowId, step.id(), "path", new ArrayList<>()));
        for (String header : request.headerLines()) {
            refs.addAll(findRefs(header, mode, flowId, step.id(), "header", new ArrayList<>()));
        }
        refs.addAll(findRefs(request.body(), mode, flowId, step.id(), "body", new ArrayList<>()));
        return Set.copyOf(refs);
    }

    private List<String> requestTemplateWarnings(FlowDefinition flow) {
        List<String> warnings = new ArrayList<>();
        for (FlowStep step : flow.steps()) {
            StoredRequestTemplate request = step.requestTemplate();
            findRefs(request.pathAndQuery(), RenderMode.STRICT, flow.id(), step.id(), "path", warnings);
            for (String header : request.headerLines()) {
                findRefs(header, RenderMode.STRICT, flow.id(), step.id(), "header", warnings);
            }
            findRefs(request.body(), RenderMode.STRICT, flow.id(), step.id(), "body", warnings);
        }
        return List.copyOf(warnings);
    }

    private Set<ScopedVariableRef> injectRuleRefs(FlowStep step, RenderMode mode, List<String> warnings) {
        LinkedHashSet<ScopedVariableRef> refs = new LinkedHashSet<>();
        for (InjectRule rule : step.injectRules()) {
            if (!rule.enabled() || rule.valueSourceType() != InjectRule.ValueSourceType.VARIABLE) {
                continue;
            }
            parseExpression(rule.valueSourceKey(), mode)
                    .ifPresentOrElse(
                            refs::add,
                            () -> warnings.add("Inject rule variable is not a supported scoped ref: step="
                                    + step.id() + ", rule=" + rule.id() + ", key=" + rule.valueSourceKey()));
        }
        return Set.copyOf(refs);
    }

    private Set<ScopedVariableRef> extractRuleRefs(FlowStep step, RenderMode mode, List<String> warnings) {
        LinkedHashSet<ScopedVariableRef> refs = new LinkedHashSet<>();
        for (ExtractRule rule : step.extractRules()) {
            if (!rule.enabled()) {
                continue;
            }
            String target = rule.targetVariableName();
            if (target.contains(".")) {
                ScopedVariableRef.tryParse(target).ifPresentOrElse(
                        refs::add,
                        () -> warnings.add("Extract rule target is not a supported scoped ref: step="
                                + step.id() + ", rule=" + rule.id() + ", key=" + target));
                continue;
            }
            if (mode == RenderMode.COMPATIBILITY && isValidKey(target)) {
                refs.add(new ScopedVariableRef(VariableScope.SCENARIO, target));
                warnings.add("Treating plain extracted variable as scenario." + target
                        + " in compatibility mode: step=" + step.id() + ", rule=" + rule.id());
            }
        }
        return Set.copyOf(refs);
    }

    private Set<ScopedVariableRef> findRefs(
            String template,
            RenderMode mode,
            String flowId,
            String stepId,
            String location,
            List<String> warnings) {
        String text = Objects.requireNonNullElse(template, "");
        LinkedHashSet<ScopedVariableRef> refs = new LinkedHashSet<>();
        int cursor = 0;
        while (cursor < text.length()) {
            int nextOpen = text.indexOf("{{", cursor);
            int nextClose = text.indexOf("}}", cursor);
            if (nextClose >= 0 && (nextOpen < 0 || nextClose < nextOpen)) {
                warnings.add("Malformed placeholder ignored: flow=" + flowId
                        + ", step=" + stepId + ", location=" + location + ", reason=stray closing braces");
                cursor = nextClose + 2;
                continue;
            }
            if (nextOpen < 0) {
                break;
            }
            PlaceholderParse parse = parsePlaceholder(text, nextOpen);
            if (parse.warning().isEmpty()) {
                parseExpression(parse.expression(), mode).ifPresent(refs::add);
            } else {
                warnings.add("Malformed placeholder ignored: flow=" + flowId
                        + ", step=" + stepId + ", location=" + location + ", reason=" + parse.warning());
            }
            cursor = parse.endOffset();
        }
        return Set.copyOf(refs);
    }

    private java.util.Optional<ScopedVariableRef> parseExpression(String expression, RenderMode mode) {
        if (expression == null || expression.isBlank()) {
            return java.util.Optional.empty();
        }
        String trimmed = expression.strip();
        if (!trimmed.contains(".")) {
            if (mode == RenderMode.COMPATIBILITY && isValidKey(trimmed)) {
                return java.util.Optional.of(new ScopedVariableRef(VariableScope.LEGACY, trimmed));
            }
            return java.util.Optional.empty();
        }
        return ScopedVariableRef.tryParse(trimmed);
    }

    private PlaceholderParse parsePlaceholder(String template, int startOffset) {
        if (startOffset + 2 < template.length() && template.charAt(startOffset + 2) == '{') {
            int tripleEnd = template.indexOf("}}}", startOffset + 3);
            int endOffset = tripleEnd >= 0 ? tripleEnd + 3 : malformedEnd(template, startOffset);
            return new PlaceholderParse("", endOffset, "triple braces are not supported");
        }
        int closeOffset = template.indexOf("}}", startOffset + 2);
        if (closeOffset < 0) {
            return new PlaceholderParse("", template.length(), "unmatched opening braces");
        }
        String inner = template.substring(startOffset + 2, closeOffset);
        if (inner.contains("{{") || inner.contains("}}")) {
            return new PlaceholderParse("", closeOffset + 2, "nested placeholders are not supported");
        }
        String expression = inner.strip();
        if (expression.isEmpty()) {
            return new PlaceholderParse("", closeOffset + 2, "blank placeholder expression");
        }
        return new PlaceholderParse(expression, closeOffset + 2, "");
    }

    private int malformedEnd(String template, int startOffset) {
        int closeOffset = template.indexOf("}}", startOffset + 2);
        return closeOffset >= 0 ? closeOffset + 2 : template.length();
    }

    private boolean isValidKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return Arrays.stream(key.split("\\.", -1)).allMatch(KEY_SEGMENT.asMatchPredicate());
    }

    private record PlaceholderParse(String expression, int endOffset, String warning) {
    }
}
