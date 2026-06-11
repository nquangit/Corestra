package dev.nquangit.corestra.access.variable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable store for scoped variable values.
 */
public final class ScopedVariableStore {
    private static final ScopedVariableStore EMPTY = new ScopedVariableStore(Map.of());

    private final Map<VariableScope, Map<String, String>> valuesByScope;

    private ScopedVariableStore(Map<VariableScope, Map<String, String>> valuesByScope) {
        EnumMap<VariableScope, Map<String, String>> copied = new EnumMap<>(VariableScope.class);
        for (VariableScope scope : VariableScope.values()) {
            copied.put(scope, copyScopeValues(valuesByScope.getOrDefault(scope, Map.of())));
        }
        this.valuesByScope = Collections.unmodifiableMap(copied);
    }

    /**
     * Returns an empty immutable variable store.
     *
     * @return an empty store
     */
    public static ScopedVariableStore empty() {
        return EMPTY;
    }

    /**
     * Creates a builder for an immutable variable store.
     *
     * @return a store builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Resolves a scoped variable key.
     *
     * @param scopedKey the full scoped key
     * @return the stored value, or empty when absent or invalid
     */
    public Optional<String> resolve(String scopedKey) {
        return ScopedVariableRef.tryParse(scopedKey).flatMap(this::resolve);
    }

    /**
     * Resolves a scoped variable reference.
     *
     * @param ref the variable reference
     * @return the stored value, or empty when absent
     */
    public Optional<String> resolve(ScopedVariableRef ref) {
        Objects.requireNonNull(ref, "ref");
        return Optional.ofNullable(valuesByScope.get(ref.scope()).get(ref.key()));
    }

    /**
     * Returns all values for a scope as an unmodifiable map.
     *
     * @param scope the scope to inspect
     * @return immutable values for the scope
     */
    public Map<String, String> values(VariableScope scope) {
        Objects.requireNonNull(scope, "scope");
        return valuesByScope.get(scope);
    }

    /**
     * Returns a new store with all values for a scope replaced.
     *
     * @param scope the scope to replace
     * @param values the values to store in that scope
     * @return a new immutable store
     */
    public ScopedVariableStore withScope(VariableScope scope, Map<String, String> values) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(values, "values");
        EnumMap<VariableScope, Map<String, String>> next = mutableCopy();
        next.put(scope, copyScopeValues(values));
        return new ScopedVariableStore(next);
    }

    /**
     * Returns a new store with one scoped value set.
     *
     * @param scope the value scope
     * @param key the key inside the scope
     * @param value the value to set
     * @return a new immutable store
     */
    public ScopedVariableStore withValue(VariableScope scope, String key, String value) {
        Objects.requireNonNull(scope, "scope");
        validateKey(key);
        Objects.requireNonNull(value, "value");
        EnumMap<VariableScope, Map<String, String>> next = mutableCopy();
        LinkedHashMap<String, String> scopeValues = new LinkedHashMap<>(next.get(scope));
        scopeValues.put(key, value);
        next.put(scope, scopeValues);
        return new ScopedVariableStore(next);
    }

    /**
     * Returns a new store with an environment value set.
     *
     * @param key the environment key
     * @param value the value to set
     * @return a new immutable store
     */
    public ScopedVariableStore withEnv(String key, String value) {
        return withValue(VariableScope.ENV, key, value);
    }

    /**
     * Returns a new store with an authentication value set.
     *
     * @param key the authentication key
     * @param value the value to set
     * @return a new immutable store
     */
    public ScopedVariableStore withAuth(String key, String value) {
        return withValue(VariableScope.AUTH, key, value);
    }

    /**
     * Returns a new store with a data value set.
     *
     * @param key the data key
     * @param value the value to set
     * @return a new immutable store
     */
    public ScopedVariableStore withData(String key, String value) {
        return withValue(VariableScope.DATA, key, value);
    }

    /**
     * Returns a new store with a scenario value set.
     *
     * @param key the scenario key
     * @param value the value to set
     * @return a new immutable store
     */
    public ScopedVariableStore withScenario(String key, String value) {
        return withValue(VariableScope.SCENARIO, key, value);
    }

    /**
     * Returns a new store with a legacy compatibility value set.
     *
     * @param key the legacy key
     * @param value the value to set
     * @return a new immutable store
     */
    public ScopedVariableStore withLegacy(String key, String value) {
        return withValue(VariableScope.LEGACY, key, value);
    }

    private EnumMap<VariableScope, Map<String, String>> mutableCopy() {
        EnumMap<VariableScope, Map<String, String>> copy = new EnumMap<>(VariableScope.class);
        for (VariableScope scope : VariableScope.values()) {
            copy.put(scope, new LinkedHashMap<>(valuesByScope.get(scope)));
        }
        return copy;
    }

    private static Map<String, String> copyScopeValues(Map<String, String> values) {
        LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            validateKey(key);
            Objects.requireNonNull(value, "value");
            copied.put(key, value);
        });
        return Collections.unmodifiableMap(copied);
    }

    private static void validateKey(String key) {
        Objects.requireNonNull(key, "key");
        if (!ScopedVariableRef.isValidKey(key)) {
            throw new IllegalArgumentException("Variable key must match segment(.segment)* with non-blank segments");
        }
    }

    /**
     * Mutable builder that produces immutable scoped variable stores.
     */
    public static final class Builder {
        private final EnumMap<VariableScope, Map<String, String>> valuesByScope = new EnumMap<>(VariableScope.class);

        private Builder() {
            for (VariableScope scope : VariableScope.values()) {
                valuesByScope.put(scope, new LinkedHashMap<>());
            }
        }

        /**
         * Adds or replaces one scoped value in the builder.
         *
         * @param scope the value scope
         * @param key the key inside the scope
         * @param value the value to set
         * @return this builder
         */
        public Builder value(VariableScope scope, String key, String value) {
            Objects.requireNonNull(scope, "scope");
            validateKey(key);
            Objects.requireNonNull(value, "value");
            valuesByScope.get(scope).put(key, value);
            return this;
        }

        /**
         * Replaces all values for a scope in the builder.
         *
         * @param scope the scope to replace
         * @param values the values to store
         * @return this builder
         */
        public Builder scope(VariableScope scope, Map<String, String> values) {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(values, "values");
            valuesByScope.put(scope, new LinkedHashMap<>(copyScopeValues(values)));
            return this;
        }

        /**
         * Builds an immutable scoped variable store.
         *
         * @return the immutable store
         */
        public ScopedVariableStore build() {
            return new ScopedVariableStore(valuesByScope);
        }
    }
}
