package dev.nquangit.corestra.access.variable;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateRendererTest {
    @Test
    void rendersScopedPlaceholdersAndSupportsSpacesInsideBraces() {
        TemplateRenderer renderer = renderer(key -> Optional.empty());
        ScopedVariableStore store = ScopedVariableStore.empty()
                .withAuth("accessToken", "abc")
                .withData("account.id", "42");

        RenderResult result = renderer.render(
                "Bearer {{ auth.accessToken }} for {{data.account.id}}",
                store,
                RenderMode.STRICT);

        assertEquals("Bearer abc for 42", result.rendered());
        assertTrue(result.isSuccess());
        assertEquals(List.of(
                new ScopedVariableRef(VariableScope.AUTH, "accessToken"),
                new ScopedVariableRef(VariableScope.DATA, "account.id")), result.resolvedVariables());
    }

    @Test
    void preservesUnresolvedPlaceholdersAndReturnsPartialRenderedOutput() {
        TemplateRenderer renderer = renderer(key -> Optional.empty());
        ScopedVariableStore store = ScopedVariableStore.empty().withAuth("accessToken", "abc");

        RenderResult result = renderer.render(
                "token={{auth.accessToken}} missing={{data.account.id}}",
                store,
                RenderMode.STRICT);

        assertEquals("token=abc missing={{data.account.id}}", result.rendered());
        assertFalse(result.isSuccess());
        UnresolvedVariable unresolved = result.unresolvedVariables().getFirst();
        assertEquals(VariableErrorType.MISSING_VARIABLE, unresolved.errorType());
        assertEquals(Optional.of(new ScopedVariableRef(VariableScope.DATA, "account.id")), unresolved.ref());
    }

    @Test
    void offsetsAreInclusiveStartAndExclusiveEndForPlaceholdersAndUnresolvedVariables() {
        TemplateRenderer renderer = renderer(key -> Optional.empty());
        String template = "abc {{ auth.accessToken }} def {{data.missing}}";

        List<TemplatePlaceholder> placeholders = renderer.findPlaceholders(template);
        RenderResult result = renderer.render(template, ScopedVariableStore.empty(), RenderMode.STRICT);

        for (TemplatePlaceholder placeholder : placeholders) {
            assertEquals(placeholder.placeholder(), template.substring(placeholder.startOffset(), placeholder.endOffset()));
        }
        for (UnresolvedVariable unresolved : result.unresolvedVariables()) {
            assertEquals(unresolved.placeholder(), template.substring(unresolved.startOffset(), unresolved.endOffset()));
        }
    }

    @Test
    void findVariableRefsHonorsStrictAndCompatibilityModes() {
        TemplateRenderer renderer = renderer(key -> Optional.empty());
        String template = "{{token}} {{auth.accessToken}} {{auth.{{nested}}}} {{AUTH.bad}}";

        List<ScopedVariableRef> strictRefs = renderer.findVariableRefs(template, RenderMode.STRICT);
        List<ScopedVariableRef> compatibilityRefs = renderer.findVariableRefs(template, RenderMode.COMPATIBILITY);

        assertEquals(List.of(new ScopedVariableRef(VariableScope.AUTH, "accessToken")), strictRefs);
        assertEquals(List.of(
                new ScopedVariableRef(VariableScope.LEGACY, "token"),
                new ScopedVariableRef(VariableScope.AUTH, "accessToken")), compatibilityRefs);
    }

    @Test
    void strictModeReportsAmbiguousPlaceholdersWithoutRef() {
        TemplateRenderer renderer = renderer(key -> Optional.empty());

        RenderResult result = renderer.render("{{token}}", ScopedVariableStore.empty(), RenderMode.STRICT);

        UnresolvedVariable unresolved = result.unresolvedVariables().getFirst();
        assertEquals("{{token}}", result.rendered());
        assertEquals(VariableErrorType.AMBIGUOUS_VARIABLE, unresolved.errorType());
        assertTrue(unresolved.ref().isEmpty());
    }

    @Test
    void compatibilityModeResolvesAmbiguousPlaceholdersAsLegacyValues() {
        TemplateRenderer renderer = renderer(key -> Optional.empty());
        ScopedVariableStore store = ScopedVariableStore.empty().withLegacy("token", "legacy-token");

        RenderResult result = renderer.render("{{token}}", store, RenderMode.COMPATIBILITY);

        assertEquals("legacy-token", result.rendered());
        assertTrue(result.isSuccess());
        assertEquals(List.of(new ScopedVariableRef(VariableScope.LEGACY, "token")), result.resolvedVariables());
        assertTrue(result.warnings().getFirst().contains("legacy.token"));
    }

    @Test
    void runtimeValuesAreStablePerKeyWithinOneRenderCall() {
        AtomicInteger counter = new AtomicInteger();
        TemplateRenderer renderer = renderer(key -> Optional.of(key + "-" + counter.incrementAndGet()));

        RenderResult first = renderer.render(
                "{{runtime.uuid}} {{runtime.uuid}} {{runtime.nonce}}",
                ScopedVariableStore.empty(),
                RenderMode.STRICT);
        RenderResult second = renderer.render("{{runtime.uuid}}", ScopedVariableStore.empty(), RenderMode.STRICT);

        assertEquals("uuid-1 uuid-1 nonce-2", first.rendered());
        assertEquals("uuid-3", second.rendered());
    }

    @Test
    void reportsStrayClosingBraces() {
        assertMalformed("abc }} def", VariableErrorType.MALFORMED_PLACEHOLDER, "}}");
    }

    @Test
    void reportsUnmatchedOpeningBraces() {
        assertMalformed("abc {{auth.token", VariableErrorType.MALFORMED_PLACEHOLDER, "{{auth.token");
    }

    @Test
    void extraClosingBracesResolveValidPlaceholderAndReportStrayClosing() {
        TemplateRenderer renderer = renderer(key -> Optional.empty());
        ScopedVariableStore store = ScopedVariableStore.empty().withAuth("token", "abc");
        String template = "abc {{auth.token}} }} def";

        RenderResult result = renderer.render(template, store, RenderMode.STRICT);

        assertEquals("abc abc }} def", result.rendered());
        assertEquals(List.of(new ScopedVariableRef(VariableScope.AUTH, "token")),
                renderer.findVariableRefs(template, RenderMode.STRICT));
        UnresolvedVariable unresolved = result.unresolvedVariables().getFirst();
        assertEquals("}}", unresolved.placeholder());
        assertEquals(unresolved.placeholder(), template.substring(unresolved.startOffset(), unresolved.endOffset()));
    }

    @Test
    void reportsTripleBracesWithoutRefs() {
        assertMalformed("{{{auth.token}}}", VariableErrorType.MALFORMED_PLACEHOLDER, "{{{auth.token}}}");
    }

    @Test
    void reportsNestedPlaceholdersWithoutRefs() {
        assertMalformed("{{auth.{{token}}}}", VariableErrorType.NESTED_PLACEHOLDER, "{{auth.{{token}}}}");
    }

    @Test
    void reportsEmptyPlaceholderWithoutRefs() {
        assertMalformed("{{}}", VariableErrorType.MALFORMED_PLACEHOLDER, "{{}}");
    }

    @Test
    void reportsWhitespaceOnlyPlaceholderWithoutRefs() {
        assertMalformed("{{   }}", VariableErrorType.MALFORMED_PLACEHOLDER, "{{   }}");
    }

    private static void assertMalformed(String template, VariableErrorType errorType, String placeholder) {
        TemplateRenderer renderer = renderer(key -> Optional.empty());

        RenderResult result = renderer.render(template, ScopedVariableStore.empty(), RenderMode.STRICT);

        assertEquals(template, result.rendered());
        assertTrue(renderer.findVariableRefs(template, RenderMode.STRICT).isEmpty());
        UnresolvedVariable unresolved = result.unresolvedVariables().getFirst();
        assertEquals(errorType, unresolved.errorType());
        assertEquals(placeholder, unresolved.placeholder());
        assertEquals(placeholder, template.substring(unresolved.startOffset(), unresolved.endOffset()));
    }

    private static TemplateRenderer renderer(RuntimeValueProvider provider) {
        return new TemplateRenderer(new VariableResolver(provider));
    }
}
