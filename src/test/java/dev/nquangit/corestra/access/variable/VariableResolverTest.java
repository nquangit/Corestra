package dev.nquangit.corestra.access.variable;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariableResolverTest {
    @Test
    void resolvesScopedStoreValues() {
        VariableResolver resolver = new VariableResolver(key -> Optional.empty());
        ScopedVariableStore store = ScopedVariableStore.empty().withAuth("accessToken", "abc");

        ResolutionResult result = resolver.resolveExpression("auth.accessToken", store, RenderMode.STRICT);

        assertTrue(result.resolved());
        assertEquals(Optional.of("abc"), result.value());
        assertEquals(Optional.of(new ScopedVariableRef(VariableScope.AUTH, "accessToken")), result.ref());
    }

    @Test
    void reportsMissingScopedValuesWithoutThrowing() {
        VariableResolver resolver = new VariableResolver(key -> Optional.empty());

        ResolutionResult result = resolver.resolveExpression("auth.accessToken", ScopedVariableStore.empty(), RenderMode.STRICT);

        assertTrue(result.value().isEmpty());
        UnresolvedVariable unresolved = result.unresolved().orElseThrow();
        assertEquals(VariableErrorType.MISSING_VARIABLE, unresolved.errorType());
        assertEquals(Optional.of(new ScopedVariableRef(VariableScope.AUTH, "accessToken")), unresolved.ref());
    }

    @Test
    void strictModeRejectsAmbiguousPlaceholdersWithoutRef() {
        VariableResolver resolver = new VariableResolver(key -> Optional.empty());

        ResolutionResult result = resolver.resolveExpression("token", ScopedVariableStore.empty(), RenderMode.STRICT);

        assertTrue(result.value().isEmpty());
        UnresolvedVariable unresolved = result.unresolved().orElseThrow();
        assertEquals(VariableErrorType.AMBIGUOUS_VARIABLE, unresolved.errorType());
        assertTrue(unresolved.ref().isEmpty());
    }

    @Test
    void compatibilityModeResolvesAmbiguousPlaceholdersAsLegacyValues() {
        VariableResolver resolver = new VariableResolver(key -> Optional.empty());
        ScopedVariableStore store = ScopedVariableStore.empty().withLegacy("token", "legacy-token");

        ResolutionResult result = resolver.resolveExpression("token", store, RenderMode.COMPATIBILITY);

        assertTrue(result.resolved());
        assertEquals(Optional.of("legacy-token"), result.value());
        assertEquals(Optional.of(new ScopedVariableRef(VariableScope.LEGACY, "token")), result.ref());
        assertTrue(result.warning().orElseThrow().contains("legacy.token"));
    }

    @Test
    void compatibilityModeReportsMissingLegacyValueWithRef() {
        VariableResolver resolver = new VariableResolver(key -> Optional.empty());

        ResolutionResult result = resolver.resolveExpression("token", ScopedVariableStore.empty(), RenderMode.COMPATIBILITY);

        UnresolvedVariable unresolved = result.unresolved().orElseThrow();
        assertEquals(VariableErrorType.MISSING_VARIABLE, unresolved.errorType());
        assertEquals(Optional.of(new ScopedVariableRef(VariableScope.LEGACY, "token")), unresolved.ref());
    }

    @Test
    void reportsInvalidScopeAndInvalidKey() {
        VariableResolver resolver = new VariableResolver(key -> Optional.empty());

        ResolutionResult invalidScope = resolver.resolveExpression("AUTH.token", ScopedVariableStore.empty(), RenderMode.STRICT);
        ResolutionResult invalidKey = resolver.resolveExpression("auth..token", ScopedVariableStore.empty(), RenderMode.STRICT);

        assertEquals(VariableErrorType.INVALID_SCOPE, invalidScope.unresolved().orElseThrow().errorType());
        assertEquals(VariableErrorType.INVALID_KEY, invalidKey.unresolved().orElseThrow().errorType());
    }

    @Test
    void resolvesRuntimeValuesThroughProvider() {
        VariableResolver resolver = new VariableResolver(key -> key.equals("timestamp")
                ? Optional.of("1780395330000")
                : Optional.empty());

        ResolutionResult result = resolver.resolveExpression("runtime.timestamp", ScopedVariableStore.empty(), RenderMode.STRICT);

        assertTrue(result.resolved());
        assertEquals(Optional.of("1780395330000"), result.value());
        assertEquals(Optional.of(new ScopedVariableRef(VariableScope.RUNTIME, "timestamp")), result.ref());
    }

    @Test
    void blankExpressionIsMalformedWithoutThrowing() {
        VariableResolver resolver = new VariableResolver(key -> Optional.empty());

        ResolutionResult result = resolver.resolveExpression("   ", ScopedVariableStore.empty(), RenderMode.STRICT);

        assertEquals(VariableErrorType.MALFORMED_PLACEHOLDER, result.unresolved().orElseThrow().errorType());
    }
}
