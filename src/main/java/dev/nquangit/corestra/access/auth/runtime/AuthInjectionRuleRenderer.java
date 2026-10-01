package dev.nquangit.corestra.access.auth.runtime;

import dev.nquangit.corestra.access.auth.AuthInjectionRule;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.variable.DefaultRuntimeValueProvider;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.RenderResult;
import dev.nquangit.corestra.access.variable.ScopedVariableStore;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.access.variable.VariableResolver;
import dev.nquangit.corestra.access.variable.VariableScope;
import dev.nquangit.corestra.flow.rule.InjectRule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Renders one shared Auth Profile and Auth Provider into ordinary request injection rules. */
public final class AuthInjectionRuleRenderer {
    private final TemplateRenderer templateRenderer;

    /** Creates a renderer using the standard scoped-variable resolver. */
    public AuthInjectionRuleRenderer() {
        this(new TemplateRenderer(new VariableResolver(new DefaultRuntimeValueProvider())));
    }

    /** Creates a renderer with an explicit template renderer. */
    public AuthInjectionRuleRenderer(TemplateRenderer templateRenderer) {
        this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
    }

    /**
     * Renders provider rules plus manual profile headers and cookies.
     * Unresolved or unsupported provider rules are omitted so live Burp traffic is never broken.
     */
    public List<InjectRule> render(AuthProfile profile, AuthProvider provider) {
        Objects.requireNonNull(profile, "profile");
        ArrayList<InjectRule> rendered = new ArrayList<>();
        if (provider != null && provider.enabled()) {
            ScopedVariableStore store = authStore(profile);
            for (AuthInjectionRule rule : provider.injectionRules()) {
                renderProviderRule(rule, store).ifPresent(rendered::add);
            }
        }
        profile.state().headers().forEach((name, value) -> rendered.add(staticRule(
                "linked-header-" + name, "Manual auth header", InjectRule.Target.REQUEST_HEADER,
                name, value, InjectRule.WriteStrategy.OVERWRITE)));
        profile.state().cookies().forEach((name, value) -> rendered.add(staticRule(
                "linked-cookie-" + name, "Manual auth cookie", InjectRule.Target.REQUEST_COOKIE,
                name, value, InjectRule.WriteStrategy.OVERWRITE)));
        return List.copyOf(rendered);
    }

    private java.util.Optional<InjectRule> renderProviderRule(
            AuthInjectionRule rule,
            ScopedVariableStore store) {
        if (rule == null || !rule.enabled()) {
            return java.util.Optional.empty();
        }
        InjectRule.Target target = switch (rule.location()) {
            case HEADER -> InjectRule.Target.REQUEST_HEADER;
            case COOKIE -> InjectRule.Target.REQUEST_COOKIE;
            case QUERY -> InjectRule.Target.REQUEST_QUERY_PARAMETER;
            case BODY_JSON, BODY_FORM, PATH -> null;
        };
        if (target == null) {
            return java.util.Optional.empty();
        }
        RenderResult selector = templateRenderer.render(rule.target(), store, RenderMode.STRICT);
        RenderResult value = templateRenderer.render(rule.valueTemplate(), store, RenderMode.STRICT);
        if (!selector.isSuccess() || selector.rendered().isBlank() || !value.isSuccess()) {
            return java.util.Optional.empty();
        }
        InjectRule.WriteStrategy strategy = switch (rule.mode()) {
            case OVERRIDE -> InjectRule.WriteStrategy.OVERWRITE;
            case APPEND -> InjectRule.WriteStrategy.APPEND;
            case SKIP_IF_PRESENT -> InjectRule.WriteStrategy.SKIP_IF_PRESENT;
        };
        return java.util.Optional.of(staticRule(
                "linked-provider-" + rule.id(), rule.name(), target,
                selector.rendered().strip(), value.rendered(), strategy));
    }

    private static ScopedVariableStore authStore(AuthProfile profile) {
        LinkedHashMap<String, String> authValues = new LinkedHashMap<>(profile.state().variables());
        profile.state().headers().forEach((key, value) -> authValues.put("header." + key, value));
        profile.state().cookies().forEach((key, value) -> authValues.put("cookie." + key, value));
        return ScopedVariableStore.builder().scope(VariableScope.AUTH, authValues).build();
    }

    private static InjectRule staticRule(
            String id,
            String name,
            InjectRule.Target target,
            String selector,
            String value,
            InjectRule.WriteStrategy strategy) {
        return new InjectRule(id, name, true, target, selector, InjectRule.ValueSourceType.STATIC,
                "", value, strategy, List.of());
    }
}
