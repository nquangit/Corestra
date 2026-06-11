package dev.nquangit.corestra.access.template;

import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.RenderResult;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.ScopedVariableStore;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.access.variable.UnresolvedVariable;
import dev.nquangit.corestra.flow.StoredRequestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Renders all string-bearing parts of an access request template.
 */
public final class AccessRequestTemplateRenderer {
    private final TemplateRenderer templateRenderer;

    /**
     * Creates an access request template renderer.
     *
     * @param templateRenderer scoped template renderer
     */
    public AccessRequestTemplateRenderer(TemplateRenderer templateRenderer) {
        this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
    }

    /**
     * Renders an access request template without mutating the original request template.
     *
     * <p>Missing, unresolved, and malformed placeholders are preserved in the rendered request and reported
     * through {@link RenderedAccessRequest#unresolvedVariables()} and {@link RenderedAccessRequest#issues()}.
     * This method throws only for programmer errors such as null arguments or an invalid template object.
     *
     * @param template access request template to render
     * @param store scoped variable store
     * @param mode render mode
     * @return rendered access request and diagnostics
     */
    public RenderedAccessRequest render(
            AccessRequestTemplate template,
            ScopedVariableStore store,
            RenderMode mode) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(mode, "mode");

        StoredRequestTemplate original = Objects.requireNonNull(template.baseRequest(), "baseRequest");
        RequestParts parts = RequestParts.from(original);
        List<RenderResult> renderResults = templateRenderer.renderAll(parts.values(), store, mode);

        StoredRequestTemplate rendered = new StoredRequestTemplate(
                renderResults.get(0).rendered(),
                renderResults.get(1).rendered(),
                renderResults.get(2).rendered(),
                original.port(),
                renderResults.get(3).rendered(),
                renderedHeaders(renderResults, parts.headerCount()),
                renderResults.get(renderResults.size() - 1).rendered());

        List<ScopedVariableRef> resolvedVariables = new ArrayList<>();
        List<UnresolvedVariable> unresolvedVariables = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<TemplateRenderIssue> issues = new ArrayList<>();

        for (int index = 0; index < renderResults.size(); index++) {
            RenderResult result = renderResults.get(index);
            String location = parts.locations().get(index);
            resolvedVariables.addAll(result.resolvedVariables());
            unresolvedVariables.addAll(result.unresolvedVariables());
            warnings.addAll(result.warnings());
            for (UnresolvedVariable unresolved : result.unresolvedVariables()) {
                issues.add(new TemplateRenderIssue(location, unresolved));
            }
        }

        return new RenderedAccessRequest(
                template.id(),
                original,
                rendered,
                resolvedVariables,
                unresolvedVariables,
                warnings,
                issues,
                unresolvedVariables.isEmpty());
    }

    private List<String> renderedHeaders(List<RenderResult> renderResults, int headerCount) {
        List<String> headers = new ArrayList<>();
        int firstHeaderIndex = 4;
        for (int offset = 0; offset < headerCount; offset++) {
            headers.add(renderResults.get(firstHeaderIndex + offset).rendered());
        }
        return headers;
    }

    private record RequestParts(List<String> values, List<String> locations, int headerCount) {
        private static RequestParts from(StoredRequestTemplate template) {
            List<String> values = new ArrayList<>();
            List<String> locations = new ArrayList<>();

            values.add(template.method());
            locations.add("method");
            values.add(template.scheme());
            locations.add("scheme");
            values.add(template.host());
            locations.add("host");
            values.add(template.pathAndQuery());
            locations.add("path");
            List<String> headers = template.headerLines();
            for (int index = 0; index < headers.size(); index++) {
                values.add(headers.get(index));
                locations.add("header[" + index + "]");
            }
            values.add(template.body());
            locations.add("body");

            return new RequestParts(List.copyOf(values), List.copyOf(locations), headers.size());
        }

        private RequestParts {
            values = List.copyOf(Objects.requireNonNull(values, "values"));
            locations = List.copyOf(Objects.requireNonNull(locations, "locations"));
            if (values.size() != locations.size()) {
                throw new IllegalArgumentException("values and locations must have the same size");
            }
            if (headerCount < 0) {
                throw new IllegalArgumentException("headerCount must not be negative");
            }
        }
    }
}
