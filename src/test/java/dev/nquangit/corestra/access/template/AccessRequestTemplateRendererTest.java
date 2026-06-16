package dev.nquangit.corestra.access.template;

import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.ScopedVariableStore;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.access.variable.UnresolvedVariable;
import dev.nquangit.corestra.access.variable.VariableErrorType;
import dev.nquangit.corestra.access.variable.VariableResolver;
import dev.nquangit.corestra.access.variable.VariableScope;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessRequestTemplateRendererTest {
    @Test
    void rendersMultipleRequestPartsInOneCall() {
        AccessRequestTemplateRenderer renderer = renderer(key -> java.util.Optional.empty());
        StoredRequestTemplate original = new StoredRequestTemplate(
                "POST",
                "{{env.scheme}}",
                "{{env.host}}",
                443,
                "/accounts/{{data.accountId}}",
                List.of("Authorization: Bearer {{auth.accessToken}}"),
                "{\"confirm\":\"{{scenario.confirmToken}}\"}");
        AccessRequestTemplate template = template(original);
        ScopedVariableStore store = ScopedVariableStore.empty()
                .withEnv("scheme", "https")
                .withEnv("host", "api.example.test")
                .withData("accountId", "acct-123")
                .withAuth("accessToken", "token-abc")
                .withScenario("confirmToken", "confirm-xyz");

        RenderedAccessRequest result = renderer.render(template, store, RenderMode.STRICT);
        StoredRequestTemplate rendered = result.renderedRequest();

        assertTrue(result.success());
        assertEquals("https", rendered.scheme());
        assertEquals("api.example.test", rendered.host());
        assertEquals("/accounts/acct-123", rendered.pathAndQuery());
        assertEquals(List.of("Authorization: Bearer token-abc"), rendered.headerLines());
        assertEquals("{\"confirm\":\"confirm-xyz\"}", rendered.body());
        assertEquals(List.of(
                new ScopedVariableRef(VariableScope.ENV, "scheme"),
                new ScopedVariableRef(VariableScope.ENV, "host"),
                new ScopedVariableRef(VariableScope.DATA, "accountId"),
                new ScopedVariableRef(VariableScope.AUTH, "accessToken"),
                new ScopedVariableRef(VariableScope.SCENARIO, "confirmToken")), result.resolvedVariables());
    }

    @Test
    void doesNotMutateOriginalTemplateAndPreservesHeaderFidelity() {
        AccessRequestTemplateRenderer renderer = renderer(key -> java.util.Optional.empty());
        StoredRequestTemplate original = new StoredRequestTemplate(
                "GET",
                "https",
                "example.test",
                443,
                "/items/{{data.itemId}}",
                List.of(
                        "X-Duplicate: first",
                        "Authorization: Bearer {{auth.accessToken}}",
                        "X-Duplicate: second"),
                "static {{data.itemId}} body");
        AccessRequestTemplate template = template(original);
        ScopedVariableStore store = ScopedVariableStore.empty()
                .withData("itemId", "item-1")
                .withAuth("accessToken", "token-abc");

        RenderedAccessRequest result = renderer.render(template, store, RenderMode.STRICT);

        assertSame(original, result.originalTemplate());
        assertNotSame(original, result.renderedRequest());
        assertEquals("/items/{{data.itemId}}", original.pathAndQuery());
        assertEquals(List.of(
                "X-Duplicate: first",
                "Authorization: Bearer {{auth.accessToken}}",
                "X-Duplicate: second"), original.headerLines());
        assertEquals("static {{data.itemId}} body", original.body());
        assertEquals(List.of(
                "X-Duplicate: first",
                "Authorization: Bearer token-abc",
                "X-Duplicate: second"), result.renderedRequest().headerLines());
        assertEquals("static item-1 body", result.renderedRequest().body());
    }

    @Test
    void preservesUnresolvedPlaceholdersAndReportsMissingVariables() {
        AccessRequestTemplateRenderer renderer = renderer(key -> java.util.Optional.empty());
        StoredRequestTemplate original = new StoredRequestTemplate(
                "GET",
                "https",
                "example.test",
                443,
                "/accounts/{{data.accountId}}",
                List.of(),
                "");
        AccessRequestTemplate template = template(original);

        RenderedAccessRequest result = renderer.render(template, ScopedVariableStore.empty(), RenderMode.STRICT);

        assertFalse(result.success());
        assertEquals("/accounts/{{data.accountId}}", result.renderedRequest().pathAndQuery());
        UnresolvedVariable unresolved = result.unresolvedVariables().getFirst();
        assertEquals(VariableErrorType.MISSING_VARIABLE, unresolved.errorType());
        assertEquals(new ScopedVariableRef(VariableScope.DATA, "accountId"), unresolved.ref().orElseThrow());
        assertEquals("path", result.issues().getFirst().location());
        assertSame(unresolved, result.issues().getFirst().unresolvedVariable());
    }

    @Test
    void compatibilityModeResolvesLegacyPlaceholdersAndRecordsWarning() {
        AccessRequestTemplateRenderer renderer = renderer(key -> java.util.Optional.empty());
        StoredRequestTemplate original = new StoredRequestTemplate(
                "GET",
                "https",
                "example.test",
                443,
                "/session/{{token}}",
                List.of(),
                "");
        AccessRequestTemplate template = template(original);
        ScopedVariableStore store = ScopedVariableStore.empty().withLegacy("token", "legacy-token");

        RenderedAccessRequest result = renderer.render(template, store, RenderMode.COMPATIBILITY);

        assertTrue(result.success());
        assertEquals("/session/legacy-token", result.renderedRequest().pathAndQuery());
        assertEquals(List.of(new ScopedVariableRef(VariableScope.LEGACY, "token")), result.resolvedVariables());
        assertTrue(result.warnings().getFirst().contains("legacy.token"));
    }

    @Test
    void runtimeValuesAreStableAcrossHeaderAndBodyInOneRenderedRequest() {
        AtomicInteger counter = new AtomicInteger();
        AccessRequestTemplateRenderer renderer = renderer(key -> java.util.Optional.of(key + "-" + counter.incrementAndGet()));
        StoredRequestTemplate original = new StoredRequestTemplate(
                "POST",
                "https",
                "example.test",
                443,
                "/events",
                List.of("X-Request-Id: {{runtime.uuid}}"),
                "{\"requestId\":\"{{runtime.uuid}}\"}");
        AccessRequestTemplate template = template(original);

        RenderedAccessRequest first = renderer.render(template, ScopedVariableStore.empty(), RenderMode.STRICT);
        RenderedAccessRequest second = renderer.render(template, ScopedVariableStore.empty(), RenderMode.STRICT);

        assertEquals("X-Request-Id: uuid-1", first.renderedRequest().headerLines().getFirst());
        assertEquals("{\"requestId\":\"uuid-1\"}", first.renderedRequest().body());
        assertEquals("X-Request-Id: uuid-2", second.renderedRequest().headerLines().getFirst());
        assertEquals("{\"requestId\":\"uuid-2\"}", second.renderedRequest().body());
    }

    private static AccessRequestTemplateRenderer renderer(dev.nquangit.corestra.access.variable.RuntimeValueProvider provider) {
        return new AccessRequestTemplateRenderer(new TemplateRenderer(new VariableResolver(provider)));
    }

    private static AccessRequestTemplate template(StoredRequestTemplate request) {
        return new AccessRequestTemplate(
                "template-1",
                "Template",
                request,
                Set.of(),
                OperationMetadata.unknown(),
                "flow-1",
                "step-1",
                "hash-1",
                true);
    }
}
