package dev.nquangit.corestra.access.template;

import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.access.variable.VariableResolver;
import dev.nquangit.corestra.access.variable.VariableScope;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessRequestTemplateAnalyzerTest {
    @Test
    void discoversScopedPlaceholdersAcrossRequestParts() {
        AccessRequestTemplateAnalyzer analyzer = analyzer();
        AccessRequestTemplate template = new AccessRequestTemplate(
                "template-1",
                "Template",
                new StoredRequestTemplate(
                        "GET",
                        "{{env.scheme}}",
                        "{{env.host}}",
                        443,
                        "/accounts/{{data.accountId}}",
                        java.util.List.of(
                                "Authorization: Bearer {{auth.accessToken}}",
                                "X-Legacy: {{legacy.token}}",
                                "X-Bad: {{auth.{{bad}}}}"),
                        "{\"confirm\":\"{{scenario.confirmToken}}\",\"id\":\"{{runtime.uuid}}\"}"),
                Set.of(),
                OperationMetadata.unknown(),
                "",
                "",
                "",
                true);

        Set<ScopedVariableRef> refs = analyzer.discoverPlaceholders(template, RenderMode.STRICT);

        assertEquals(Set.of(
                new ScopedVariableRef(VariableScope.ENV, "scheme"),
                new ScopedVariableRef(VariableScope.ENV, "host"),
                new ScopedVariableRef(VariableScope.DATA, "accountId"),
                new ScopedVariableRef(VariableScope.AUTH, "accessToken"),
                new ScopedVariableRef(VariableScope.LEGACY, "token"),
                new ScopedVariableRef(VariableScope.SCENARIO, "confirmToken"),
                new ScopedVariableRef(VariableScope.RUNTIME, "uuid")), refs);
        assertFalse(refs.contains(new ScopedVariableRef(VariableScope.AUTH, "bad")));
    }

    @Test
    void compatibilityModeDiscoversAmbiguousPlaceholdersAsLegacyRefs() {
        AccessRequestTemplateAnalyzer analyzer = analyzer();
        AccessRequestTemplate template = new AccessRequestTemplate(
                "template-1",
                "Template",
                new StoredRequestTemplate(
                        "GET",
                        "https",
                        "example.test",
                        443,
                        "/session/{{token}}",
                        java.util.List.of("X-Bad: {{{auth.token}}}"),
                        ""),
                Set.of(),
                OperationMetadata.unknown(),
                "",
                "",
                "",
                true);

        assertTrue(analyzer.discoverPlaceholders(template, RenderMode.STRICT).isEmpty());
        assertEquals(Set.of(new ScopedVariableRef(VariableScope.LEGACY, "token")),
                analyzer.discoverPlaceholders(template, RenderMode.COMPATIBILITY));
    }

    private static AccessRequestTemplateAnalyzer analyzer() {
        return new AccessRequestTemplateAnalyzer(new TemplateRenderer(new VariableResolver(key -> Optional.empty())));
    }
}
