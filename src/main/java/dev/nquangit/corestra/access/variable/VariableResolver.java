package dev.nquangit.corestra.access.variable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves scoped variable expressions against a store and runtime provider.
 */
public final class VariableResolver {
    private final RuntimeValueProvider runtimeValueProvider;

    /**
     * Creates a resolver.
     *
     * @param runtimeValueProvider the provider for generated runtime values
     */
    public VariableResolver(RuntimeValueProvider runtimeValueProvider) {
        this.runtimeValueProvider = Objects.requireNonNull(runtimeValueProvider, "runtimeValueProvider");
    }

    /**
     * Resolves a full scoped key directly from the store.
     *
     * @param scopedKey the scoped key to resolve
     * @param store the scoped variable store
     * @return the stored value, or empty when absent or invalid
     */
    public Optional<String> resolve(String scopedKey, ScopedVariableStore store) {
        Objects.requireNonNull(scopedKey, "scopedKey");
        Objects.requireNonNull(store, "store");
        return ScopedVariableRef.tryParse(scopedKey).flatMap(store::resolve);
    }

    /**
     * Resolves one template expression.
     *
     * @param expression the trimmed or untrimmed placeholder expression
     * @param store the scoped variable store
     * @param mode the render mode
     * @return the resolution result
     */
    public ResolutionResult resolveExpression(String expression, ScopedVariableStore store, RenderMode mode) {
        return resolveExpression(expression, store, mode, new HashMap<>());
    }

    ResolutionResult resolveExpression(
            String expression,
            ScopedVariableStore store,
            RenderMode mode,
            Map<String, String> runtimeCache) {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(runtimeCache, "runtimeCache");

        String trimmed = expression.trim();
        if (trimmed.isEmpty()) {
            return unresolved(expression, "", Optional.empty(), VariableErrorType.MALFORMED_PLACEHOLDER,
                    "Placeholder expression must not be blank");
        }

        int dotIndex = trimmed.indexOf('.');
        if (dotIndex < 0) {
            return resolveAmbiguous(trimmed, store, mode);
        }
        if (dotIndex == 0 || dotIndex == trimmed.length() - 1) {
            return unresolved(expression, trimmed, Optional.empty(), VariableErrorType.INVALID_KEY,
                    "Scoped variable key must include a scope and non-blank key");
        }

        String scopeText = trimmed.substring(0, dotIndex);
        Optional<VariableScope> scope = VariableScope.fromPrefix(scopeText);
        if (scope.isEmpty()) {
            return unresolved(expression, trimmed, Optional.empty(), VariableErrorType.INVALID_SCOPE,
                    "Unsupported variable scope: " + scopeText);
        }

        String key = trimmed.substring(dotIndex + 1);
        if (!ScopedVariableRef.isValidKey(key)) {
            return unresolved(expression, trimmed, Optional.empty(), VariableErrorType.INVALID_KEY,
                    "Variable key must match segment(.segment)* with non-blank segments");
        }

        ScopedVariableRef ref = new ScopedVariableRef(scope.get(), key);
        return resolveRef(expression, trimmed, ref, store, runtimeCache, Optional.empty());
    }

    private ResolutionResult resolveAmbiguous(String expression, ScopedVariableStore store, RenderMode mode) {
        if (!ScopedVariableRef.isValidKey(expression)) {
            return unresolved(expression, expression, Optional.empty(), VariableErrorType.INVALID_KEY,
                    "Ambiguous variable key contains invalid characters");
        }
        if (mode == RenderMode.STRICT) {
            return unresolved(expression, expression, Optional.empty(), VariableErrorType.AMBIGUOUS_VARIABLE,
                    "Ambiguous variable must use a scope prefix");
        }

        ScopedVariableRef legacyRef = new ScopedVariableRef(VariableScope.LEGACY, expression);
        return resolveRef(expression, expression, legacyRef, store, Map.of(),
                Optional.of("Compatibility mode resolved {{" + expression + "}} as {{legacy." + expression + "}}"));
    }

    private ResolutionResult resolveRef(
            String placeholderExpression,
            String trimmedExpression,
            ScopedVariableRef ref,
            ScopedVariableStore store,
            Map<String, String> runtimeCache,
            Optional<String> warning) {
        Optional<String> storedValue = store.resolve(ref);
        if (storedValue.isPresent()) {
            return ResolutionResult.resolved(storedValue.get(), ref, warning);
        }

        if (ref.scope() == VariableScope.RUNTIME) {
            Optional<String> runtimeValue = resolveRuntimeValue(ref.key(), runtimeCache);
            if (runtimeValue.isPresent()) {
                return ResolutionResult.resolved(runtimeValue.get(), ref, warning);
            }
        }

        return unresolved(placeholderExpression, trimmedExpression, Optional.of(ref), VariableErrorType.MISSING_VARIABLE,
                "No value found for scoped variable: " + ref.scopedKey());
    }

    private Optional<String> resolveRuntimeValue(String key, Map<String, String> runtimeCache) {
        if (runtimeCache.containsKey(key)) {
            return Optional.of(runtimeCache.get(key));
        }
        Optional<String> value = runtimeValueProvider.resolve(key);
        value.ifPresent(resolved -> runtimeCache.put(key, resolved));
        return value;
    }

    private ResolutionResult unresolved(
            String placeholder,
            String expression,
            Optional<ScopedVariableRef> ref,
            VariableErrorType errorType,
            String message) {
        return ResolutionResult.unresolved(new UnresolvedVariable(
                placeholder,
                expression,
                ref,
                errorType,
                message,
                0,
                placeholder.length()));
    }
}
