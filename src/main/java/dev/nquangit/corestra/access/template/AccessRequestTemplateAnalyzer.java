package dev.nquangit.corestra.access.template;

import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.flow.StoredRequestTemplate;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Discovers scoped variable placeholders in access request templates.
 */
public final class AccessRequestTemplateAnalyzer {
    private final TemplateRenderer templateRenderer;

    /**
     * Creates an access request template analyzer.
     *
     * @param templateRenderer scoped template renderer
     */
    public AccessRequestTemplateAnalyzer(TemplateRenderer templateRenderer) {
        this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
    }

    /**
     * Discovers variable references in all string-bearing request parts.
     *
     * @param template access request template to scan
     * @param mode render mode controlling compatibility placeholders
     * @return immutable discovered references
     */
    public Set<ScopedVariableRef> discoverPlaceholders(AccessRequestTemplate template, RenderMode mode) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(mode, "mode");
        StoredRequestTemplate request = Objects.requireNonNull(template.baseRequest(), "baseRequest");

        LinkedHashSet<ScopedVariableRef> refs = new LinkedHashSet<>();
        refs.addAll(templateRenderer.findVariableRefs(request.method(), mode));
        refs.addAll(templateRenderer.findVariableRefs(request.scheme(), mode));
        refs.addAll(templateRenderer.findVariableRefs(request.host(), mode));
        refs.addAll(templateRenderer.findVariableRefs(request.pathAndQuery(), mode));
        for (String header : request.headerLines()) {
            refs.addAll(templateRenderer.findVariableRefs(header, mode));
        }
        refs.addAll(templateRenderer.findVariableRefs(request.body(), mode));
        return Collections.unmodifiableSet(refs);
    }
}
