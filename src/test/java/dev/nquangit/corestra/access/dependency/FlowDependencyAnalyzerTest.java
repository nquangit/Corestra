package dev.nquangit.corestra.access.dependency;

import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.OperationMetadata;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.VariableScope;
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowStep.StepErrorStrategy;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.ExtractRule.ExtractorType;
import dev.nquangit.corestra.flow.rule.ExtractRule.SourceSide;
import dev.nquangit.corestra.flow.rule.InjectRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowDependencyAnalyzerTest {
    private final FlowDependencyAnalyzer analyzer = new FlowDependencyAnalyzer();

    @Test
    void stepRequiresScenarioVariableFromStoredRequestPlaceholder() {
        FlowDefinition flow = flow(List.of(
                step("target", "/confirm/{{scenario.confirmToken}}", List.of(), List.of())));

        FlowDependencyGraph graph = analyzer.analyze(flow, RenderMode.STRICT);

        assertEquals(Set.of(new ScopedVariableRef(VariableScope.SCENARIO, "confirmToken")),
                graph.metadataForStep("target").orElseThrow().requires());
    }

    @Test
    void stepRequiresScenarioVariableFromAccessTemplateMetadata() {
        FlowDefinition flow = flow(List.of(step("target", "/confirm", List.of(), List.of())));
        AccessRequestTemplate template = new AccessRequestTemplate(
                "template-1",
                "Confirm",
                request("/confirm"),
                Set.of(new ScopedVariableRef(VariableScope.SCENARIO, "confirmToken")),
                OperationMetadata.unknown(),
                flow.id(),
                "target",
                "hash-1",
                true);

        FlowDependencyGraph graph = analyzer.analyze(flow, List.of(template), RenderMode.STRICT);

        assertEquals(Set.of(new ScopedVariableRef(VariableScope.SCENARIO, "confirmToken")),
                graph.metadataForStep("target").orElseThrow().requires());
    }

    @Test
    void previousStepProducesScenarioVariableFromExtractRule() {
        FlowDefinition flow = flow(List.of(
                step("setup", "/setup", List.of(extract("scenario.confirmToken")), List.of()),
                step("target", "/confirm/{{scenario.confirmToken}}", List.of(), List.of())));

        FlowDependencyGraph graph = analyzer.analyze(flow, RenderMode.STRICT);

        assertEquals(List.of(graph.metadataForStep("setup").orElseThrow()),
                graph.producersFor(new ScopedVariableRef(VariableScope.SCENARIO, "confirmToken")));
    }

    @Test
    void enabledInjectRuleAddsRequiredScopedVariable() {
        InjectRule injectRule = new InjectRule(
                "inject-1",
                "Inject scenario token",
                true,
                InjectRule.Target.REQUEST_HEADER,
                "X-Confirm",
                InjectRule.ValueSourceType.VARIABLE,
                "scenario.confirmToken",
                "",
                InjectRule.WriteStrategy.OVERWRITE);
        FlowDefinition flow = flow(List.of(step("target", "/confirm", List.of(), List.of(injectRule))));

        FlowDependencyGraph graph = analyzer.analyze(flow, RenderMode.STRICT);

        assertEquals(Set.of(new ScopedVariableRef(VariableScope.SCENARIO, "confirmToken")),
                graph.metadataForStep("target").orElseThrow().requires());
    }

    @Test
    void malformedPlaceholderDoesNotCrashAndIsWarned() {
        FlowDefinition flow = flow(List.of(
                step("target", "/confirm/{{{scenario.confirmToken}}}", List.of(), List.of())));

        FlowDependencyGraph graph = analyzer.analyze(flow, RenderMode.STRICT);

        assertTrue(graph.metadataForStep("target").orElseThrow().requires().isEmpty());
        assertTrue(graph.warnings().stream().anyMatch(warning -> warning.contains("Malformed placeholder")));
    }

    @Test
    void compatibilityModeTreatsPlainExtractAsScenarioProducer() {
        FlowDefinition flow = flow(List.of(
                step("setup", "/setup", List.of(extract("confirmToken")), List.of())));

        FlowDependencyGraph graph = analyzer.analyze(flow, RenderMode.COMPATIBILITY);

        assertEquals(Set.of(new ScopedVariableRef(VariableScope.SCENARIO, "confirmToken")),
                graph.metadataForStep("setup").orElseThrow().produces());
        assertTrue(graph.warnings().stream().anyMatch(warning -> warning.contains("scenario.confirmToken")));
    }

    @Test
    void strictModeDoesNotTreatPlainExtractAsScenarioProducer() {
        FlowDefinition flow = flow(List.of(
                step("setup", "/setup", List.of(extract("confirmToken")), List.of())));

        FlowDependencyGraph graph = analyzer.analyze(flow, RenderMode.STRICT);

        assertTrue(graph.metadataForStep("setup").orElseThrow().produces().isEmpty());
    }

    @Test
    void requiredScenarioVariablesForReturnsOnlyScenarioScope() {
        FlowDefinition flow = flow(List.of(
                step("target", "/confirm/{{scenario.confirmToken}}?auth={{auth.accessToken}}", List.of(), List.of())));

        FlowDependencyGraph graph = analyzer.analyze(flow, RenderMode.STRICT);

        assertEquals(Set.of(new ScopedVariableRef(VariableScope.SCENARIO, "confirmToken")),
                graph.requiredScenarioVariablesFor("target"));
    }

    private static FlowDefinition flow(List<FlowStep> steps) {
        return new FlowDefinition(
                "flow-1",
                "Flow",
                "",
                true,
                steps,
                FlowRuleSet.empty(),
                AggregationPlan.disabled(),
                FlowTriggerConfig.none());
    }

    private static FlowStep step(
            String id,
            String path,
            List<ExtractRule> extractRules,
            List<InjectRule> injectRules) {
        return new FlowStep(
                id,
                id,
                true,
                "",
                request(path),
                extractRules,
                injectRules,
                List.of(),
                StepErrorStrategy.FAIL_FLOW);
    }

    private static StoredRequestTemplate request(String path) {
        return new StoredRequestTemplate("GET", "https", "example.test", 443, path, List.of(), "");
    }

    private static ExtractRule extract(String targetVariableName) {
        return new ExtractRule(
                "extract-" + targetVariableName.replace('.', '-'),
                "Extract",
                true,
                SourceSide.RESPONSE,
                ExtractorType.BODY_TEXT,
                "",
                targetVariableName,
                List.of());
    }
}
