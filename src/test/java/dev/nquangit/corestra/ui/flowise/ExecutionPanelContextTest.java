package dev.nquangit.corestra.ui.flowise;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionPanelContextTest {
    @Test
    void templateInputsAreValidatedAgainstProfileStateAndEarlierExtractors() {
        InjectRule template = new InjectRule("template", "Template", true, InjectRule.Target.REQUEST_HEADER,
                "Authorization", InjectRule.ValueSourceType.TEMPLATE, "",
                "Bearer {{auth.refreshToken}}/{{credentials.username}}", InjectRule.WriteStrategy.OVERWRITE);
        FlowStep login = step("login", List.of(extract("auth.refreshToken")), List.of());
        FlowStep target = step("target", List.of(), List.of(template));
        FlowDefinition flow = flow(login, target);
        assertEquals(List.of("credentials.username"), ExecutionPanel.missingScopedVariables(flow, null, Map.of()));
        assertEquals(List.of("auth.refreshToken", "credentials.username"),
                ExecutionPanel.missingScopedVariables(flow, target, Map.of()));
        assertEquals(List.of(), ExecutionPanel.missingScopedVariables(flow, null, Map.of("credentials.username", "alice")));
    }

    @Test
    void wholeFlowAcceptsScopedValueExtractedBeforeInjection() {
        FlowStep login = step("login", List.of(extract("auth.accessToken")), List.of());
        FlowStep target = step("target", List.of(), List.of(inject("auth.accessToken")));
        FlowDefinition flow = flow(login, target);

        assertEquals(List.of(), ExecutionPanel.missingScopedVariables(flow, null, Map.of()));
        assertEquals(List.of("auth.accessToken"),
                ExecutionPanel.missingScopedVariables(flow, target, Map.of()));
    }

    @Test
    void reportsEachMissingProfileVariableOnceAndHonorsInitialContext() {
        FlowStep target = step("target", List.of(), List.of(
                inject("credentials.username"), inject("credentials.username"), inject("data.accountId")));
        FlowDefinition flow = flow(target);

        assertEquals(List.of("credentials.username", "data.accountId"),
                ExecutionPanel.missingScopedVariables(flow, null, Map.of()));
        assertEquals(List.of("data.accountId"), ExecutionPanel.missingScopedVariables(
                flow, null, Map.of("credentials.username", "alice")));
    }

    private static FlowDefinition flow(FlowStep... steps) {
        return new FlowDefinition("flow", "Flow", "", true, List.of(steps), FlowRuleSet.empty(),
                AggregationPlan.disabled(), FlowTriggerConfig.none());
    }

    private static FlowStep step(
            String id,
            List<ExtractRule> extracts,
            List<InjectRule> injects) {
        return new FlowStep(
                id, id, true, "",
                new StoredRequestTemplate("GET", "https", "example.test", 443, "/", List.of(), ""),
                extracts, injects, List.of(), null);
    }

    private static ExtractRule extract(String key) {
        return new ExtractRule(
                "extract-" + key, "Extract", true, ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.HEADER, "X-Value", key, List.of());
    }

    private static InjectRule inject(String key) {
        return new InjectRule(
                "inject-" + key, "Inject", true,
                InjectRule.Target.REQUEST_HEADER, "X-Value", InjectRule.ValueSourceType.VARIABLE,
                key, "", InjectRule.WriteStrategy.OVERWRITE);
    }
}
