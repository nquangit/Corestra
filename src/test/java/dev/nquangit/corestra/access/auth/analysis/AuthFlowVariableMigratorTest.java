package dev.nquangit.corestra.access.auth.analysis;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthFlowVariableMigratorTest {
    private final AuthFlowVariableMigrator migrator = new AuthFlowVariableMigrator();

    @Test
    void renamesLegacyExtractorAndOnlyLaterInjectionReferences() {
        FlowDefinition flow = flow(List.of(
                step("login", List.of(extract("auth.accessToken")), List.of(variableInject("credentials.username"))),
                step("target", List.of(), List.of(variableInject("auth.accessToken")))));

        AuthFlowVariableMigrator.MigrationResult result = migrator.migrate(flow);

        assertTrue(result.changed());
        assertEquals(1, result.renamedExtractors());
        assertEquals(1, result.renamedReferences());
        assertEquals("accessToken", result.flow().steps().getFirst().extractRules().getFirst().targetVariableName());
        assertEquals("credentials.username", result.flow().steps().getFirst().injectRules().getFirst().valueSourceKey());
        assertEquals("accessToken", result.flow().steps().get(1).injectRules().getFirst().valueSourceKey());
    }

    @Test
    void skipsLegacyNameWhenCanonicalExtractorAlreadyExists() {
        FlowDefinition flow = flow(List.of(step(
                "login",
                List.of(extract("accessToken"), extract("auth.accessToken")),
                List.of())));

        AuthFlowVariableMigrator.MigrationResult result = migrator.migrate(flow);

        assertFalse(result.changed());
        assertSame(flow, result.flow());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("already extracts")));
    }

    @Test
    void disabledExtractorDoesNotRewriteLaterAuthStateInput() {
        ExtractRule disabled = new ExtractRule(
                "disabled", "Disabled", false, ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.BODY_TEXT, "", "auth.accessToken", List.of());
        FlowDefinition flow = flow(List.of(
                step("disabled", List.of(disabled), List.of()),
                step("target", List.of(), List.of(variableInject("auth.accessToken")))));

        AuthFlowVariableMigrator.MigrationResult result = migrator.migrate(flow);

        assertEquals("accessToken", result.flow().steps().getFirst().extractRules().getFirst().targetVariableName());
        assertEquals("auth.accessToken", result.flow().steps().get(1).injectRules().getFirst().valueSourceKey());
    }

    private static FlowDefinition flow(List<FlowStep> steps) {
        return new FlowDefinition(
                "flow", "Flow", "", true, steps, FlowRuleSet.empty(), AggregationPlan.disabled(),
                FlowTriggerConfig.none());
    }

    private static FlowStep step(String id, List<ExtractRule> extracts, List<InjectRule> injects) {
        return new FlowStep(
                id, id, true, "",
                new StoredRequestTemplate("GET", "https", "example.test", 443, "/", List.of(), ""),
                extracts, injects, List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
    }

    private static ExtractRule extract(String target) {
        return new ExtractRule(
                "extract-" + target, "Extract " + target, true, ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.BODY_TEXT, "", target, List.of());
    }

    private static InjectRule variableInject(String key) {
        return new InjectRule(
                "inject-" + key, "Inject " + key, true, InjectRule.Target.REQUEST_HEADER, "X-Value",
                InjectRule.ValueSourceType.VARIABLE, key, "", InjectRule.WriteStrategy.OVERWRITE);
    }
}
