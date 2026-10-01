package dev.nquangit.corestra.http.inject;

import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InjectionSuggestionServiceTest {
    @Test
    void suggestsNamedTargetsUsingOnlyEarlierExtractors() {
        FlowStep login = step("login", request("/login"), List.of(extract("token"), extract("csrf")), List.of());
        FlowStep target = step("target", new StoredRequestTemplate(
                "POST", "https", "example.test", 443, "/pay?csrf=old",
                List.of("Authorization: old", "Cookie: token=old", "Content-Type: application/json"),
                "{\"data\":{\"token\":\"old\"},\"amount\":10}"), List.of(), List.of());

        List<InjectionSuggestionService.Suggestion> result =
                new InjectionSuggestionService().suggest(flow(login, target), target);

        assertEquals(List.of(InjectRule.Target.REQUEST_COOKIE, InjectRule.Target.REQUEST_QUERY_PARAMETER,
                        InjectRule.Target.REQUEST_JSON_PATH),
                result.stream().map(suggestion -> suggestion.rule().target()).toList());
        assertEquals(List.of("token", "csrf", "token"),
                result.stream().map(suggestion -> suggestion.rule().valueSourceKey()).toList());
        assertEquals("data.token", result.get(2).rule().targetSelector());
    }

    @Test
    void excludesEquivalentExistingRuleAndLaterExtractor() {
        FlowStep first = step("first", request("/first"), List.of(extract("token")), List.of());
        InjectRule existing = new InjectRule("rule", "existing", true,
                InjectRule.Target.REQUEST_QUERY_PARAMETER, "token", InjectRule.ValueSourceType.VARIABLE,
                "token", "", InjectRule.WriteStrategy.OVERWRITE);
        FlowStep current = step("current", request("/target?token=old&future=old"), List.of(), List.of(existing));
        FlowStep later = step("later", request("/later"), List.of(extract("future")), List.of());
        assertEquals(List.of(), new InjectionSuggestionService().suggest(flow(first, current, later), current));
    }

    private static ExtractRule extract(String variable) {
        return new ExtractRule(variable, "Extract " + variable, true, ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.HEADER, "X-" + variable, variable, List.of());
    }

    private static FlowStep step(String id, StoredRequestTemplate request,
                                 List<ExtractRule> extract, List<InjectRule> inject) {
        return new FlowStep(id, id, true, "", request, extract, inject, List.of(), null);
    }

    private static StoredRequestTemplate request(String path) {
        return new StoredRequestTemplate("GET", "https", "example.test", 443, path, List.of(), "");
    }

    private static FlowDefinition flow(FlowStep... steps) {
        return new FlowDefinition("flow", "Flow", "", true, List.of(steps), FlowRuleSet.empty(),
                AggregationPlan.disabled(), FlowTriggerConfig.none());
    }
}
