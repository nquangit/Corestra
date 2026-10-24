package dev.nquangit.corestra.manual;

import dev.nquangit.corestra.flow.*;
import dev.nquangit.corestra.flow.control.*;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.http.inject.RequestMutationResult;
import java.util.*;

/** Copies a flow and its explicit child-flow dependencies into independent, editable manual drafts. */
public final class ManualFlowImporter {
    private final InjectionService injections = new InjectionService();
    /** A completely prepared import; callers persist its drafts together after successful conversion. */
    public record Result(List<FlowDefinition> flows, List<String> notices) {
        /** Freezes a prepared batch. */
        public Result { flows = List.copyOf(flows); notices = List.copyOf(notices); }
    }

    /** Clones dependencies, remaps identity/references, and converts safe injections into inline placeholders. */
    public Result copy(FlowDefinition root, List<FlowDefinition> catalog) {
        Map<String, FlowDefinition> source = new LinkedHashMap<>(); catalog.forEach(f -> source.put(f.id(), f)); source.put(root.id(), root);
        Map<String, String> ids = new LinkedHashMap<>(); collect(root, source, ids);
        List<String> notices = new ArrayList<>(); List<FlowDefinition> drafts = new ArrayList<>();
        for (String oldId : ids.keySet()) {
            FlowDefinition flow = source.get(oldId);
            Map<String, String> stepIds = new HashMap<>(); flow.steps().forEach(s -> stepIds.put(s.id(), UUID.randomUUID().toString()));
            List<FlowStep> steps = new ArrayList<>();
            for (FlowStep step : flow.steps()) {
                var converted = convert(step, notices);
                var actions = step.conditionActionRules().stream().map(rule -> new StepConditionActionRule(rule.id(), rule.name(), rule.enabled(),
                        rule.matchPolicy(), rule.conditions(), rule.actions().stream().map(a -> {
                            Map<String, String> params = new LinkedHashMap<>(a.parameters());
                            if (a.type() == StepActionType.TRIGGER_FLOW && ids.containsKey(params.get("flowId"))) params.put("flowId", ids.get(params.get("flowId")));
                            return new StepAction(a.id(), a.name(), a.enabled(), a.type(), params);
                        }).toList())).toList();
                var preconditions = step.preconditions().stream().map(p -> new FlowStep.StepPrecondition(p.preconditionType(),
                        p.preconditionType() == FlowStep.PreconditionType.STEP_COMPLETED ? stepIds.get(p.subjectKey()) : p.subjectKey(), p.description())).toList();
                steps.add(new FlowStep(stepIds.get(step.id()), step.name(), step.enabled(), step.notes(), converted.requestTemplate(),
                        step.extractRules(), converted.injectRules(), preconditions, step.errorStrategy(), null, StepTriggerRule.disabled(),
                        step.includeInAggregation(), actions, step.sendModeOverride()));
            }
            drafts.add(new FlowDefinition(ids.get(oldId), flow.name() + " (manual)", flow.description(), true, steps,
                    flow.rules(), AggregationPlan.disabled(), FlowTriggerConfig.none()));
        }
        return new Result(drafts, notices);
    }

    private void collect(FlowDefinition flow, Map<String, FlowDefinition> source, Map<String, String> ids) {
        if (ids.containsKey(flow.id())) return;
        if (ids.size() >= 64) throw new IllegalArgumentException("Manual copy exceeds 64 dependent flows");
        ids.put(flow.id(), UUID.randomUUID().toString());
        flow.steps().stream().flatMap(s -> s.conditionActionRules().stream()).filter(StepConditionActionRule::enabled)
                .flatMap(r -> r.actions().stream()).filter(a -> a.enabled() && a.type() == StepActionType.TRIGGER_FLOW).forEach(a -> {
                    String target = a.parameters().getOrDefault("flowId", "");
                    FlowDefinition dependency = source.get(target);
                    if (dependency == null) throw new IllegalArgumentException("Cannot copy missing child flow: " + target);
                    collect(dependency, source, ids);
                });
    }

    private FlowStep convert(FlowStep step, List<String> notices) {
        StoredRequestTemplate t = step.requestTemplate();
        var mutable = new InjectionService.MutableHttpRequest(t.method() + " " + t.pathAndQuery() + " HTTP/1.1", t.headerLines(), t.body());
        Map<String, String> markers = new LinkedHashMap<>(); List<InjectRule> remaining = new ArrayList<>(); boolean keepRest = false;
        for (InjectRule rule : step.injectRules()) {
            if (!rule.enabled()) { remaining.add(rule); continue; }
            try {
                if (keepRest || rule.valueSourceType() == InjectRule.ValueSourceType.INTERACTIVE
                        || rule.target() == InjectRule.Target.REQUEST_REGEX_REPLACEMENT) throw new IllegalArgumentException();
                String expression;
                List<String> encoding = switch (rule.target()) {
                    case REQUEST_QUERY_PARAMETER, REQUEST_FORM_FIELD -> List.of("urlEncode");
                    case REQUEST_JSON_PATH -> List.of("jsonEscape");
                    default -> List.of();
                };
                if (rule.valueSourceType() == InjectRule.ValueSourceType.VARIABLE) {
                    if (rule.valueSourceKey().chars().anyMatch(c -> Character.isWhitespace(c) || c == '|' || c == '{' || c == '}')) throw new IllegalArgumentException();
                    var chain = new ArrayList<>(rule.transformChain()); chain.addAll(encoding);
                    if (chain.stream().anyMatch(s -> s.contains("|") || s.contains("{{") || s.contains("}}"))) throw new IllegalArgumentException();
                    expression = "{{" + rule.valueSourceKey() + (chain.isEmpty() ? "" : "|" + String.join("|", chain)) + "}}";
                } else if (rule.valueSourceType() == InjectRule.ValueSourceType.TEMPLATE && rule.transformChain().isEmpty() && encoding.isEmpty()) {
                    if (dev.nquangit.corestra.flow.rule.InjectValueTemplate.variableKeys(rule.staticValue()).stream().anyMatch(k -> k.contains("|"))) throw new IllegalArgumentException();
                    expression = rule.staticValue();
                } else if (rule.valueSourceType() == InjectRule.ValueSourceType.STATIC) {
                    var result = injections.applyRequired(List.of(rule), Map.of(), mutable);
                    mutable = new InjectionService.MutableHttpRequest(result.requestLine(), result.headers(), result.body()); continue;
                } else throw new IllegalArgumentException();
                String marker = "CORESTRAMANUAL" + UUID.randomUUID().toString().replace("-", "");
                InjectRule placeholder = new InjectRule(rule.id(), rule.name(), true, rule.target(), rule.targetSelector(),
                        InjectRule.ValueSourceType.STATIC, "", marker, rule.writeStrategy(), List.of());
                RequestMutationResult result = injections.applyRequired(List.of(placeholder), Map.of(), mutable);
                markers.put(marker, expression);
                mutable = new InjectionService.MutableHttpRequest(result.requestLine(), result.headers(), result.body());
            } catch (IllegalArgumentException e) {
                keepRest = true; remaining.add(rule); notices.add(step.name() + ": retained injection '" + rule.name() + "' in Rules (applied after inline templates).");
            }
        }
        String line = replaceMarkers(mutable.requestLine(), markers);
        String path = line.substring(line.indexOf(' ') + 1, line.lastIndexOf(' '));
        var request = new StoredRequestTemplate(t.method(), t.scheme(), t.host(), t.port(), path,
                mutable.headers().stream().filter(h -> !h.regionMatches(true, 0, "Content-Length:", 0, 15)).map(h -> replaceMarkers(h, markers)).toList(),
                replaceMarkers(mutable.body(), markers));
        return step.withRequestTemplate(request).withInjectRules(remaining);
    }

    private String replaceMarkers(String value, Map<String, String> markers) {
        String text = ManualRequestTemplate.literal(value);
        for (var entry : markers.entrySet()) text = text.replace(entry.getKey(), entry.getValue());
        return text;
    }
}
