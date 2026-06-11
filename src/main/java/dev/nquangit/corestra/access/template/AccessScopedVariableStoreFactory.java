package dev.nquangit.corestra.access.template;

import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.variable.ScopedVariableStore;
import dev.nquangit.corestra.access.variable.VariableScope;

import java.util.Map;
import java.util.Objects;

/**
 * Builds scoped variable stores from access-testing auth, data, environment, and scenario inputs.
 */
public final class AccessScopedVariableStoreFactory {
    /**
     * Creates a scoped variable store from access-testing inputs.
     *
     * <p>The auth scope includes {@link AuthState#variables()} only. Manual headers and cookies are not
     * implicitly converted to variables in PR 3.
     *
     * @param env environment variables
     * @param authState auth state whose variables become auth-scoped values
     * @param dataProfile data profile whose values become data-scoped values
     * @param scenario scenario variables
     * @param legacy compatibility variables
     * @return immutable scoped variable store
     */
    public ScopedVariableStore from(
            Map<String, String> env,
            AuthState authState,
            DataProfile dataProfile,
            Map<String, String> scenario,
            Map<String, String> legacy) {
        ScopedVariableStore.Builder builder = ScopedVariableStore.builder();
        builder.scope(VariableScope.ENV, emptyIfNull(env));
        if (authState != null) {
            builder.scope(VariableScope.AUTH, authState.variables());
        }
        if (dataProfile != null) {
            builder.scope(VariableScope.DATA, dataProfile.values());
        }
        builder.scope(VariableScope.SCENARIO, emptyIfNull(scenario));
        builder.scope(VariableScope.LEGACY, emptyIfNull(legacy));
        return builder.build();
    }

    private static Map<String, String> emptyIfNull(Map<String, String> values) {
        return Objects.requireNonNullElse(values, Map.of());
    }
}
