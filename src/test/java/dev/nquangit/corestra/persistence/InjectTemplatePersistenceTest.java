package dev.nquangit.corestra.persistence;

import dev.nquangit.corestra.access.security.RedactionService;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.http.inject.InjectionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class InjectTemplatePersistenceTest {
    private final FlowSerializer serializer = new FlowSerializer();

    @Test
    void templateAndBearerPrefixSpaceSurvivePersistenceAndStillInjectCorrectly() {
        List<InjectRule> rules = List.of(
                rule(InjectRule.ValueSourceType.TEMPLATE, "", "Bearer {{auth.refreshToken}}", List.of()),
                rule(InjectRule.ValueSourceType.VARIABLE, "auth.refreshToken", "", List.of("prefix:Bearer ")));
        FlowDefinition flow = flow(rules);
        assertEquals(flow, serializer.deserialize(serializer.serialize(List.of(flow))).getFirst());
        for (InjectRule restored : serializer.deserialize(serializer.serialize(List.of(flow)))
                .getFirst().steps().getFirst().injectRules()) {
            var result = new InjectionService().apply(List.of(restored), Map.of("auth.refreshToken", "abc"),
                    new InjectionService.MutableHttpRequest("POST /refresh HTTP/1.1", List.of(), ""));
            assertEquals(List.of("Authorization: Bearer abc"), result.headers());
        }
    }

    @Test
    void safeExportsKeepBearerReferencesButRemoveInlineSecretsInTemplates() {
        FlowExportSanitizer sanitizer = new FlowExportSanitizer(new RedactionService());
        for (boolean exclude : List.of(false, true)) {
            FlowDefinition sanitized = sanitizer.sanitizeFlow(flow(List.of(
                    rule(InjectRule.ValueSourceType.TEMPLATE, "", "Bearer {{auth.refreshToken}}", List.of()),
                    rule(InjectRule.ValueSourceType.TEMPLATE, "", "Bearer hardcoded-secret", List.of()))), exclude);
            assertEquals("Bearer {{auth.refreshToken}}", sanitized.steps().getFirst().injectRules().getFirst().staticValue());
            assertFalse(serializer.serialize(List.of(sanitized)).contains("hardcoded-secret"));
            assertEquals(sanitized, serializer.deserialize(serializer.serialize(List.of(sanitized))).getFirst());
        }
    }

    private static InjectRule rule(InjectRule.ValueSourceType source, String key, String value, List<String> transforms) {
        return new InjectRule("id-" + source + value.hashCode(), "Rule", true, InjectRule.Target.REQUEST_HEADER,
                "Authorization", source, key, value, InjectRule.WriteStrategy.OVERWRITE, transforms);
    }

    private static FlowDefinition flow(List<InjectRule> rules) {
        return new FlowDefinition("flow", "Flow", "", true, List.of(new FlowStep("step", "Step", true, "",
                new StoredRequestTemplate("POST", "https", "example.test", 443, "/refresh", List.of(), ""),
                List.of(), rules, List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW)), null, null, null);
    }
}
