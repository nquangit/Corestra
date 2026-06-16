package dev.nquangit.corestra.access.auth.runtime;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.variable.RenderMode;

import java.util.Map;

/**
 * Runs an AuthProvider flow for a given profile and purpose.
 */
public interface AuthFlowRunner {
    /**
     * Runs an auth flow.
     *
     * @param flowId flow id to run
     * @param profile auth profile
     * @param provider auth provider
     * @param env environment variables
     * @param purpose flow purpose
     * @param renderMode render mode requested by the caller
     * @return auth flow run result
     */
    AuthFlowRunResult runAuthFlow(
            String flowId,
            AuthProfile profile,
            AuthProvider provider,
            Map<String, String> env,
            AuthFlowPurpose purpose,
            RenderMode renderMode);
}
