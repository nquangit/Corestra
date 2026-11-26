package dev.nquangit.corestra.access.runner;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.AccessRequestTemplateRenderer;
import dev.nquangit.corestra.access.template.AccessScopedVariableStoreFactory;
import dev.nquangit.corestra.access.template.RenderedAccessRequest;
import dev.nquangit.corestra.access.variable.DefaultRuntimeValueProvider;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.ScopedVariableStore;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.access.variable.VariableResolver;
import dev.nquangit.corestra.flow.StoredRequestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Renders an access target request and applies configured/manual auth without sending HTTP.
 */
public final class AccessTargetRequestPreparer {
    private final AccessRequestTemplateRenderer templateRenderer;
    private final AccessScopedVariableStoreFactory storeFactory;
    private final ManualAuthApplier manualAuthApplier;
    private final AuthProviderRequestApplier providerAuthApplier;
    private final StoredRequestHttpRequestAdapter requestAdapter;

    /**
     * Creates a target request preparer.
     *
     * @param templateRenderer access template renderer
     * @param storeFactory scoped variable store factory
     * @param manualAuthApplier manual auth applier
     * @param requestAdapter raw request adapter
     */
    public AccessTargetRequestPreparer(
            AccessRequestTemplateRenderer templateRenderer,
            AccessScopedVariableStoreFactory storeFactory,
            ManualAuthApplier manualAuthApplier,
            StoredRequestHttpRequestAdapter requestAdapter) {
        this(
                templateRenderer,
                storeFactory,
                manualAuthApplier,
                new AuthProviderRequestApplier(
                        new TemplateRenderer(new VariableResolver(new DefaultRuntimeValueProvider()))),
                requestAdapter);
    }

    AccessTargetRequestPreparer(
            AccessRequestTemplateRenderer templateRenderer,
            AccessScopedVariableStoreFactory storeFactory,
            ManualAuthApplier manualAuthApplier,
            AuthProviderRequestApplier providerAuthApplier,
            StoredRequestHttpRequestAdapter requestAdapter) {
        this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
        this.storeFactory = Objects.requireNonNull(storeFactory, "storeFactory");
        this.manualAuthApplier = Objects.requireNonNull(manualAuthApplier, "manualAuthApplier");
        this.providerAuthApplier = Objects.requireNonNull(providerAuthApplier, "providerAuthApplier");
        this.requestAdapter = Objects.requireNonNull(requestAdapter, "requestAdapter");
    }

    /**
     * Prepares one target request for later synchronized sending.
     *
     * @param template access request template
     * @param authProfile target auth profile
     * @param authProvider target auth provider, or null for manual auth
     * @param dataProfile target data profile, or null
     * @param env environment variables
     * @param scenario scenario variables
     * @param legacy legacy variables
     * @param mode render mode
     * @param baseWarnings existing warnings to carry forward
     * @return prepared request
     */
    public PreparedAccessRequest prepare(
            AccessRequestTemplate template,
            AuthProfile authProfile,
            AuthProvider authProvider,
            DataProfile dataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode mode,
            List<String> baseWarnings) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(authProfile, "authProfile");
        RenderMode renderMode = Objects.requireNonNullElse(mode, RenderMode.STRICT);
        ScopedVariableStore store = storeFactory.from(env, authProfile.state(), dataProfile, scenario, legacy);
        RenderedAccessRequest rendered = templateRenderer.render(
                CapturedIdentityScrubber.forActor(authProfile, isManual(authProfile) ? null : authProvider).scrub(template),
                store,
                renderMode);
        List<String> renderWarnings = warnings(baseWarnings, rendered.warnings());
        if (!rendered.success()) {
            return PreparedAccessRequest.invalid(
                    "Unresolved variables: " + unresolvedSummary(rendered),
                    renderWarnings);
        }

        StoredRequestTemplate request = rendered.renderedRequest();
        List<String> preparedWarnings = new ArrayList<>(renderWarnings);
        if (authProvider != null && !isManual(authProfile)) {
            AuthProviderApplicationResult providerResult = providerAuthApplier.apply(
                    request,
                    authProvider,
                    store,
                    renderMode);
            request = providerResult.request();
            preparedWarnings.addAll(providerResult.warnings());
        }
        AuthState authState = authProfile.state();
        request = manualAuthApplier.apply(request, authState, store, renderMode);
        return PreparedAccessRequest.success(request, requestAdapter.rawRequest(request), preparedWarnings);
    }

    private static String unresolvedSummary(RenderedAccessRequest rendered) {
        return rendered.issues().stream()
                .map(issue -> issue.location() + " " + issue.unresolvedVariable().placeholder()
                        + " " + issue.unresolvedVariable().errorType())
                .collect(Collectors.joining("; "));
    }

    @SafeVarargs
    private static List<String> warnings(List<String>... groups) {
        ArrayList<String> values = new ArrayList<>();
        for (List<String> group : groups) {
            if (group != null) {
                values.addAll(group);
            }
        }
        return List.copyOf(values);
    }

    private static boolean isManual(AuthProfile authProfile) {
        return authProfile.providerId().isBlank() || AuthProfile.MANUAL_PROVIDER_ID.equals(authProfile.providerId());
    }
}
