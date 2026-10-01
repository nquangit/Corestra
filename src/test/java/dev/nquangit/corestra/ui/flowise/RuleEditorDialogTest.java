package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuleEditorDialogTest {
    private static final StoredRequestTemplate TEMPLATE = new StoredRequestTemplate(
            "GET", "https", "example.test", 443, "/", List.of(), "");

    @Test
    void injectionChoicesContainOnlyEnabledExtractorsFromEarlierEnabledSteps() {
        FlowStep first = step("first", "Login", true, List.of(
                extract("token-old", "Old token", true, "token"),
                extract("disabled", "Disabled", false, "ignored")));
        FlowStep skipped = step("skipped", "Disabled step", false, List.of(
                extract("skipped-token", "Skipped token", true, "ignoredStep")));
        FlowStep second = step("second", "Refresh", true, List.of(
                extract("token-new", "Fresh token", true, "token"),
                extract("csrf", "CSRF", true, "csrf")));
        FlowStep current = step("current", "Target", true, List.of(
                extract("current-value", "Current", true, "tooLate")));
        FlowStep later = step("later", "Later", true, List.of(
                extract("later-value", "Later", true, "future")));
        FlowDefinition flow = new FlowDefinition(
                "flow", "Flow", "", true, List.of(first, skipped, second, current, later),
                FlowRuleSet.empty(), AggregationPlan.disabled(), FlowTriggerConfig.none());

        List<RuleEditorDialog.ExtractedVariableOption> options =
                RuleEditorDialog.availableExtractedVariables(flow, current);

        assertEquals(List.of("token", "csrf"), options.stream().map(
                RuleEditorDialog.ExtractedVariableOption::key).toList());
        assertEquals("Refresh", options.get(0).stepName());
        assertEquals("Fresh token", options.get(0).ruleName());
        assertEquals(List.of(), RuleEditorDialog.availableExtractedVariables(
                flow, step("not-in-flow", "Stale", true, List.of())));
    }

    @Test
    void selectedVariableStoresTheKeyInsteadOfItsSourceDescription() {
        RuleEditorDialog.ExtractedVariableOption option =
                new RuleEditorDialog.ExtractedVariableOption("token", "Login", "Extract token");
        JComboBox<RuleEditorDialog.ExtractedVariableOption> combo = new JComboBox<>(
                new RuleEditorDialog.ExtractedVariableOption[]{option});
        combo.setEditable(true);
        combo.setSelectedItem(option);

        assertEquals("token", RuleEditorDialog.selectedVariableKey(combo));
        assertEquals("token  —  Login / Extract token", option.displayLabel());
    }

    private static FlowStep step(
            String id,
            String name,
            boolean enabled,
            List<ExtractRule> extractRules) {
        return new FlowStep(
                id,
                name,
                enabled,
                "",
                TEMPLATE,
                extractRules,
                List.of(),
                List.of(),
                null);
    }

    private static ExtractRule extract(
            String id,
            String name,
            boolean enabled,
            String variable) {
        return new ExtractRule(
                id,
                name,
                enabled,
                ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.HEADER,
                "X-Value",
                variable,
                List.of());
    }
}
