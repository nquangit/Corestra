package dev.nquangit.corestra.access.variable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Renders string templates containing {@code {{scope.key}}} placeholders.
 */
public final class TemplateRenderer {
    private final VariableResolver resolver;

    /**
     * Creates a template renderer.
     *
     * @param resolver the resolver used for variable values
     */
    public TemplateRenderer(VariableResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /**
     * Renders a template, preserving unresolved placeholders in the output.
     *
     * @param template the template text
     * @param store the scoped variable store
     * @param mode the render mode
     * @return the render result
     */
    public RenderResult render(String template, ScopedVariableStore store, RenderMode mode) {
        return renderWithCache(template, store, mode, new HashMap<>());
    }

    /**
     * Renders multiple templates while sharing generated runtime values across the batch.
     *
     * @param templates the template texts
     * @param store the scoped variable store
     * @param mode the render mode
     * @return render results in the same order as the input templates
     */
    public List<RenderResult> renderAll(List<String> templates, ScopedVariableStore store, RenderMode mode) {
        Objects.requireNonNull(templates, "templates");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(mode, "mode");

        HashMap<String, String> runtimeCache = new HashMap<>();
        List<RenderResult> results = new ArrayList<>();
        for (String template : templates) {
            results.add(renderWithCache(Objects.requireNonNull(template, "template"), store, mode, runtimeCache));
        }
        return List.copyOf(results);
    }

    private RenderResult renderWithCache(
            String template,
            ScopedVariableStore store,
            RenderMode mode,
            HashMap<String, String> runtimeCache) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(runtimeCache, "runtimeCache");

        StringBuilder rendered = new StringBuilder(template.length());
        List<ScopedVariableRef> resolvedVariables = new ArrayList<>();
        List<UnresolvedVariable> unresolvedVariables = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        int cursor = 0;
        while (cursor < template.length()) {
            int nextOpen = template.indexOf("{{", cursor);
            int nextClose = template.indexOf("}}", cursor);
            if (nextClose >= 0 && (nextOpen < 0 || nextClose < nextOpen)) {
                rendered.append(template, cursor, nextClose);
                String placeholder = template.substring(nextClose, nextClose + 2);
                rendered.append(placeholder);
                unresolvedVariables.add(unresolved(placeholder, "", Optional.empty(),
                        VariableErrorType.MALFORMED_PLACEHOLDER,
                        "Stray closing braces", nextClose, nextClose + 2));
                cursor = nextClose + 2;
                continue;
            }
            if (nextOpen < 0) {
                rendered.append(template, cursor, template.length());
                break;
            }

            rendered.append(template, cursor, nextOpen);
            ParseOutcome outcome = parseAt(template, nextOpen);
            rendered.append(outcome.placeholder());
            if (outcome.errorType().isPresent()) {
                unresolvedVariables.add(unresolved(
                        outcome.placeholder(),
                        outcome.expression(),
                        Optional.empty(),
                        outcome.errorType().get(),
                        outcome.message().orElse("Malformed placeholder"),
                        outcome.startOffset(),
                        outcome.endOffset()));
                cursor = outcome.endOffset();
                continue;
            }

            ResolutionResult resolution = resolver.resolveExpression(outcome.expression(), store, mode, runtimeCache);
            if (resolution.resolved()) {
                rendered.setLength(rendered.length() - outcome.placeholder().length());
                rendered.append(resolution.value().orElseThrow());
                resolution.ref().ifPresent(resolvedVariables::add);
                resolution.warning().ifPresent(warnings::add);
            } else {
                resolution.unresolved()
                        .map(unresolved -> relocate(unresolved, outcome.placeholder(), outcome.startOffset(), outcome.endOffset()))
                        .ifPresent(unresolvedVariables::add);
            }
            cursor = outcome.endOffset();
        }

        return new RenderResult(template, rendered.toString(), resolvedVariables, unresolvedVariables, warnings);
    }

    /**
     * Finds syntactically valid placeholders in a template.
     *
     * @param template the template text
     * @return placeholders with inclusive start and exclusive end offsets
     */
    public List<TemplatePlaceholder> findPlaceholders(String template) {
        Objects.requireNonNull(template, "template");
        List<TemplatePlaceholder> placeholders = new ArrayList<>();
        int cursor = 0;
        while (cursor < template.length()) {
            int nextOpen = template.indexOf("{{", cursor);
            int nextClose = template.indexOf("}}", cursor);
            if (nextClose >= 0 && (nextOpen < 0 || nextClose < nextOpen)) {
                cursor = nextClose + 2;
                continue;
            }
            if (nextOpen < 0) {
                break;
            }
            ParseOutcome outcome = parseAt(template, nextOpen);
            if (outcome.errorType().isEmpty()) {
                placeholders.add(new TemplatePlaceholder(
                        outcome.placeholder(),
                        outcome.expression(),
                        outcome.startOffset(),
                        outcome.endOffset()));
            }
            cursor = outcome.endOffset();
        }
        return List.copyOf(placeholders);
    }

    /**
     * Finds variable references represented by syntactically valid placeholders.
     *
     * @param template the template text
     * @param mode the render mode
     * @return variable references in template order
     */
    public List<ScopedVariableRef> findVariableRefs(String template, RenderMode mode) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(mode, "mode");
        List<ScopedVariableRef> refs = new ArrayList<>();
        for (TemplatePlaceholder placeholder : findPlaceholders(template)) {
            refForExpression(placeholder.expression(), mode).ifPresent(refs::add);
        }
        return List.copyOf(refs);
    }

    private Optional<ScopedVariableRef> refForExpression(String expression, RenderMode mode) {
        String trimmed = expression.trim();
        if (trimmed.indexOf('.') < 0) {
            if (mode == RenderMode.COMPATIBILITY && ScopedVariableRef.isValidKey(trimmed)) {
                return Optional.of(new ScopedVariableRef(VariableScope.LEGACY, trimmed));
            }
            return Optional.empty();
        }
        return ScopedVariableRef.tryParse(trimmed);
    }

    private ParseOutcome parseAt(String template, int startOffset) {
        if (startOffset + 2 < template.length() && template.charAt(startOffset + 2) == '{') {
            int tripleEnd = template.indexOf("}}}", startOffset + 3);
            int endOffset = tripleEnd >= 0 ? tripleEnd + 3 : fallbackMalformedEnd(template, startOffset);
            return new ParseOutcome(
                    template.substring(startOffset, endOffset),
                    "",
                    startOffset,
                    endOffset,
                    Optional.of(VariableErrorType.MALFORMED_PLACEHOLDER),
                    Optional.of("Triple braces are not supported"));
        }

        int closeOffset = template.indexOf("}}", startOffset + 2);
        if (closeOffset < 0) {
            return new ParseOutcome(
                    template.substring(startOffset),
                    "",
                    startOffset,
                    template.length(),
                    Optional.of(VariableErrorType.MALFORMED_PLACEHOLDER),
                    Optional.of("Unmatched opening braces"));
        }

        String inner = template.substring(startOffset + 2, closeOffset);
        if (inner.contains("{{") || inner.contains("}}")) {
            int endOffset = closeOffset + 2;
            int nextClose = template.indexOf("}}", endOffset);
            if (nextClose == endOffset) {
                endOffset = nextClose + 2;
            }
            return new ParseOutcome(
                    template.substring(startOffset, endOffset),
                    inner.trim(),
                    startOffset,
                    endOffset,
                    Optional.of(VariableErrorType.NESTED_PLACEHOLDER),
                    Optional.of("Nested placeholders are not supported"));
        }

        String placeholder = template.substring(startOffset, closeOffset + 2);
        String expression = inner.trim();
        if (expression.isEmpty()) {
            return new ParseOutcome(
                    placeholder,
                    expression,
                    startOffset,
                    closeOffset + 2,
                    Optional.of(VariableErrorType.MALFORMED_PLACEHOLDER),
                    Optional.of("Placeholder expression must not be blank"));
        }

        return new ParseOutcome(
                placeholder,
                expression,
                startOffset,
                closeOffset + 2,
                Optional.empty(),
                Optional.empty());
    }

    private int fallbackMalformedEnd(String template, int startOffset) {
        int firstClose = template.indexOf("}}", startOffset + 2);
        if (firstClose >= 0) {
            return firstClose + 2;
        }
        return template.length();
    }

    private UnresolvedVariable unresolved(
            String placeholder,
            String expression,
            Optional<ScopedVariableRef> ref,
            VariableErrorType errorType,
            String message,
            int startOffset,
            int endOffset) {
        return new UnresolvedVariable(placeholder, expression, ref, errorType, message, startOffset, endOffset);
    }

    private UnresolvedVariable relocate(
            UnresolvedVariable unresolved,
            String placeholder,
            int startOffset,
            int endOffset) {
        return new UnresolvedVariable(
                placeholder,
                unresolved.expression(),
                unresolved.ref(),
                unresolved.errorType(),
                unresolved.message(),
                startOffset,
                endOffset);
    }

    private record ParseOutcome(
            String placeholder,
            String expression,
            int startOffset,
            int endOffset,
            Optional<VariableErrorType> errorType,
            Optional<String> message) {
        private ParseOutcome {
            Objects.requireNonNull(placeholder, "placeholder");
            Objects.requireNonNull(expression, "expression");
            errorType = Objects.requireNonNull(errorType, "errorType");
            message = Objects.requireNonNull(message, "message");
        }
    }
}
