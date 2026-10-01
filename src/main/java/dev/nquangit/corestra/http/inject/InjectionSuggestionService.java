package dev.nquangit.corestra.http.inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Suggests variable injection rules from extractor names produced by earlier flow steps. */
public final class InjectionSuggestionService {
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Finds request fields whose name equals a variable extracted by an earlier enabled step.
     * Existing equivalent rules are excluded.
     *
     * @param flow active flow
     * @param currentStep step whose request will receive the rules
     * @return ordered suggestions
     */
    public List<Suggestion> suggest(FlowDefinition flow, FlowStep currentStep) {
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(currentStep, "currentStep");
        Map<String, VariableSource> variables = earlierVariables(flow, currentStep);
        if (variables.isEmpty()) return List.of();

        List<Suggestion> suggestions = new ArrayList<>();
        StoredRequestTemplate request = currentStep.requestTemplate();
        String contentType = "";
        for (String header : request.headerLines()) {
            int colon = header.indexOf(':');
            if (colon <= 0) continue;
            String name = header.substring(0, colon).strip();
            String value = header.substring(colon + 1).strip();
            if (name.equalsIgnoreCase("Content-Type")) contentType = value.toLowerCase(Locale.ROOT);
            if (name.equalsIgnoreCase("Cookie")) {
                for (String pair : value.split(";")) {
                    int equals = pair.indexOf('=');
                    if (equals > 0) {
                        String cookieName = pair.substring(0, equals).strip();
                        addIfVariable(suggestions, variables, cookieName,
                                InjectRule.Target.REQUEST_COOKIE, cookieName);
                    }
                }
            } else {
                addIfVariable(suggestions, variables, name, InjectRule.Target.REQUEST_HEADER, name);
            }
        }

        int queryStart = request.pathAndQuery().indexOf('?');
        if (queryStart >= 0) {
            addPairs(suggestions, variables, request.pathAndQuery().substring(queryStart + 1),
                    InjectRule.Target.REQUEST_QUERY_PARAMETER);
        }
        if (contentType.contains("application/x-www-form-urlencoded")) {
            addPairs(suggestions, variables, request.body(), InjectRule.Target.REQUEST_FORM_FIELD);
        } else if (contentType.contains("json") || looksLikeJson(request.body())) {
            addJsonPaths(suggestions, variables, request.body());
        }

        Set<String> existing = new LinkedHashSet<>();
        for (InjectRule rule : currentStep.injectRules()) {
            existing.add(identity(rule.target(), rule.targetSelector(), rule.valueSourceKey()));
        }
        return suggestions.stream()
                .filter(suggestion -> !existing.contains(identity(
                        suggestion.rule().target(), suggestion.rule().targetSelector(),
                        suggestion.rule().valueSourceKey())))
                .distinct()
                .toList();
    }

    private static Map<String, VariableSource> earlierVariables(FlowDefinition flow, FlowStep currentStep) {
        Map<String, VariableSource> result = new LinkedHashMap<>();
        boolean found = false;
        for (FlowStep step : flow.steps()) {
            if (step.id().equals(currentStep.id())) {
                found = true;
                break;
            }
            if (!step.enabled()) continue;
            for (ExtractRule rule : step.extractRules()) {
                if (rule.enabled()) {
                    result.put(rule.targetVariableName().toLowerCase(Locale.ROOT),
                            new VariableSource(rule.targetVariableName(), step.name(), rule.name()));
                }
            }
        }
        return found ? result : Map.of();
    }

    private static void addPairs(List<Suggestion> suggestions, Map<String, VariableSource> variables,
                                 String encodedPairs, InjectRule.Target target) {
        if (encodedPairs == null || encodedPairs.isBlank()) return;
        for (String pair : encodedPairs.split("&")) {
            int equals = pair.indexOf('=');
            String rawName = equals < 0 ? pair : pair.substring(0, equals);
            String name;
            try {
                name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                name = rawName;
            }
            addIfVariable(suggestions, variables, name, target, name);
        }
    }

    private void addJsonPaths(List<Suggestion> suggestions, Map<String, VariableSource> variables, String body) {
        try {
            JsonNode root = mapper.readTree(body);
            if (root != null && root.isObject()) walkJsonObject(suggestions, variables, root, "", 0);
        } catch (Exception ignored) {
            // Invalid JSON cannot produce a safe structured suggestion.
        }
    }

    private void walkJsonObject(List<Suggestion> suggestions, Map<String, VariableSource> variables,
                                JsonNode node, String prefix, int depth) {
        if (depth > 16 || !node.isObject()) return;
        node.fields().forEachRemaining(entry -> {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            addIfVariable(suggestions, variables, entry.getKey(), InjectRule.Target.REQUEST_JSON_PATH, path);
            if (entry.getValue().isObject()) {
                walkJsonObject(suggestions, variables, entry.getValue(), path, depth + 1);
            }
        });
    }

    private static void addIfVariable(List<Suggestion> suggestions, Map<String, VariableSource> variables,
                                      String candidateName, InjectRule.Target target, String selector) {
        VariableSource source = variables.get(candidateName.toLowerCase(Locale.ROOT));
        if (source == null) return;
        InjectRule rule = new InjectRule(UUID.randomUUID().toString(),
                "Inject " + source.variableName() + " into " + selector, true, target, selector,
                InjectRule.ValueSourceType.VARIABLE, source.variableName(), "",
                InjectRule.WriteStrategy.OVERWRITE);
        suggestions.add(new Suggestion(rule, source.stepName(), source.ruleName()));
    }

    private static boolean looksLikeJson(String body) {
        return Objects.requireNonNullElse(body, "").stripLeading().startsWith("{");
    }

    private static String identity(InjectRule.Target target, String selector, String variable) {
        return target + "\u0000" + selector.toLowerCase(Locale.ROOT) + "\u0000" + variable;
    }

    /** Suggested rule and the extractor that made its variable available. */
    public record Suggestion(InjectRule rule, String sourceStepName, String sourceRuleName) {
        /** Human-readable review label. */
        public String displayLabel() {
            return rule.target() + "  " + rule.targetSelector() + "  ←  " + rule.valueSourceKey()
                    + "  (" + sourceStepName + " / " + sourceRuleName + ")";
        }
    }

    private record VariableSource(String variableName, String stepName, String ruleName) {
    }
}
