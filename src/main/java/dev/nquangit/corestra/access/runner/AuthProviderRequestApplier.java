package dev.nquangit.corestra.access.runner;

import dev.nquangit.corestra.access.auth.AuthInjectionLocation;
import dev.nquangit.corestra.access.auth.AuthInjectionMode;
import dev.nquangit.corestra.access.auth.AuthInjectionRule;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.RenderResult;
import dev.nquangit.corestra.access.variable.ScopedVariableStore;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.flow.StoredRequestTemplate;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class AuthProviderRequestApplier {
    private final TemplateRenderer templateRenderer;

    AuthProviderRequestApplier(TemplateRenderer templateRenderer) {
        this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
    }

    AuthProviderApplicationResult apply(
            StoredRequestTemplate request,
            AuthProvider provider,
            ScopedVariableStore store,
            RenderMode mode) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(mode, "mode");

        if (!provider.enabled() || provider.injectionRules().isEmpty()) {
            return new AuthProviderApplicationResult(request, List.of());
        }

        StoredRequestTemplate current = request;
        List<String> warnings = new ArrayList<>();
        for (AuthInjectionRule rule : provider.injectionRules()) {
            if (!rule.enabled()) {
                continue;
            }
            RenderedRuleText target = renderRuleText(rule, "target", rule.target(), store, mode, warnings);
            if (target.hasUnresolvedVariables() || target.value().isBlank()) {
                warnings.add("Auth injection rule '" + rule.name() + "': skipped because target is blank or unresolved");
                continue;
            }

            RenderedRuleText value = renderRuleText(rule, "value", rule.valueTemplate(), store, mode, warnings);
            current = applyRule(current, rule, target.value().strip(), value.value(), warnings);
        }
        return new AuthProviderApplicationResult(current, warnings);
    }

    private StoredRequestTemplate applyRule(
            StoredRequestTemplate request,
            AuthInjectionRule rule,
            String target,
            String value,
            List<String> warnings) {
        AuthInjectionLocation location = Objects.requireNonNullElse(rule.location(), AuthInjectionLocation.HEADER);
        return switch (location) {
            case HEADER -> withHeader(request, target, value, rule.mode());
            case COOKIE -> withCookie(request, target, value, rule.mode());
            case QUERY -> withQueryParameter(request, target, value, rule.mode());
            case BODY_JSON, BODY_FORM, PATH -> {
                warnings.add("Auth injection rule '" + rule.name() + "': " + location
                        + " injection is not supported in PR 9C");
                yield request;
            }
        };
    }

    private StoredRequestTemplate withHeader(
            StoredRequestTemplate request,
            String name,
            String value,
            AuthInjectionMode mode) {
        List<String> headers = new ArrayList<>(request.headerLines());
        boolean present = headerExists(headers, name);
        AuthInjectionMode writeMode = Objects.requireNonNullElse(mode, AuthInjectionMode.OVERRIDE);
        if (writeMode == AuthInjectionMode.SKIP_IF_PRESENT && present) {
            return request;
        }
        if (writeMode == AuthInjectionMode.OVERRIDE) {
            headers.removeIf(header -> {
                HeaderLine line = HeaderLine.parse(header);
                return line != null && line.name().equalsIgnoreCase(name);
            });
        }
        headers.add(name + ": " + value);
        return copyWithHeaders(request, headers);
    }

    private StoredRequestTemplate withCookie(
            StoredRequestTemplate request,
            String name,
            String value,
            AuthInjectionMode mode) {
        List<String> preservedHeaders = new ArrayList<>();
        LinkedHashMap<String, String> cookies = new LinkedHashMap<>();
        for (String header : request.headerLines()) {
            HeaderLine line = HeaderLine.parse(header);
            if (line != null && "cookie".equalsIgnoreCase(line.name())) {
                parseCookies(line.value(), cookies);
            } else {
                preservedHeaders.add(header);
            }
        }

        boolean present = cookies.containsKey(name);
        AuthInjectionMode writeMode = Objects.requireNonNullElse(mode, AuthInjectionMode.OVERRIDE);
        if (writeMode == AuthInjectionMode.SKIP_IF_PRESENT && present) {
            return request;
        }
        cookies.put(name, value);
        if (!cookies.isEmpty()) {
            preservedHeaders.add("Cookie: " + cookieHeader(cookies));
        }
        return copyWithHeaders(request, preservedHeaders);
    }

    private StoredRequestTemplate withQueryParameter(
            StoredRequestTemplate request,
            String name,
            String value,
            AuthInjectionMode mode) {
        String target = request.pathAndQuery();
        int queryStart = target.indexOf('?');
        String path = queryStart >= 0 ? target.substring(0, queryStart) : target;
        String query = queryStart >= 0 ? target.substring(queryStart + 1) : "";
        LinkedHashMap<String, String> parameters = parseQuery(query);
        boolean present = parameters.containsKey(name);
        AuthInjectionMode writeMode = Objects.requireNonNullElse(mode, AuthInjectionMode.OVERRIDE);
        if (writeMode == AuthInjectionMode.SKIP_IF_PRESENT && present) {
            return request;
        }
        parameters.put(name, value);
        String renderedQuery = buildQuery(parameters);
        String pathAndQuery = renderedQuery.isEmpty() ? path : path + "?" + renderedQuery;
        return new StoredRequestTemplate(
                request.method(),
                request.scheme(),
                request.host(),
                request.port(),
                pathAndQuery,
                request.headerLines(),
                request.body());
    }

    private RenderedRuleText renderRuleText(
            AuthInjectionRule rule,
            String label,
            String template,
            ScopedVariableStore store,
            RenderMode mode,
            List<String> warnings) {
        RenderResult result = templateRenderer.render(Objects.requireNonNullElse(template, ""), store, mode);
        warnings.addAll(result.warnings());
        if (result.hasUnresolvedVariables()) {
            warnings.add("Auth injection rule '" + rule.name() + "': unresolved variables in " + label
                    + " template were preserved");
        }
        return new RenderedRuleText(result.rendered(), result.hasUnresolvedVariables());
    }

    private boolean headerExists(List<String> headers, String name) {
        for (String header : headers) {
            HeaderLine line = HeaderLine.parse(header);
            if (line != null && line.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private void parseCookies(String value, Map<String, String> cookies) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String part : value.split(";")) {
            String trimmed = part.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int equalsIndex = trimmed.indexOf('=');
            if (equalsIndex < 1) {
                cookies.put(trimmed, "");
            } else {
                cookies.put(trimmed.substring(0, equalsIndex).strip(), trimmed.substring(equalsIndex + 1).strip());
            }
        }
    }

    private String cookieHeader(Map<String, String> cookies) {
        List<String> parts = new ArrayList<>();
        cookies.forEach((key, value) -> parts.add(key + "=" + value));
        return String.join("; ", parts);
    }

    private LinkedHashMap<String, String> parseQuery(String query) {
        LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return parameters;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equalsIndex = pair.indexOf('=');
            if (equalsIndex < 0) {
                parameters.put(decode(pair), "");
            } else {
                parameters.put(decode(pair.substring(0, equalsIndex)), decode(pair.substring(equalsIndex + 1)));
            }
        }
        return parameters;
    }

    private String buildQuery(LinkedHashMap<String, String> parameters) {
        List<String> parts = new ArrayList<>();
        parameters.forEach((key, value) -> parts.add(encode(key) + "=" + encode(value)));
        return String.join("&", parts);
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return value;
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private StoredRequestTemplate copyWithHeaders(StoredRequestTemplate request, List<String> headers) {
        return new StoredRequestTemplate(
                request.method(),
                request.scheme(),
                request.host(),
                request.port(),
                request.pathAndQuery(),
                headers,
                request.body());
    }

    private record RenderedRuleText(String value, boolean hasUnresolvedVariables) {
        private RenderedRuleText {
            value = Objects.requireNonNull(value, "value");
        }
    }

    private record HeaderLine(String name, String value) {
        private HeaderLine {
            name = Objects.requireNonNull(name, "name");
            value = Objects.requireNonNull(value, "value");
        }

        private static HeaderLine parse(String raw) {
            int colonIndex = raw == null ? -1 : raw.indexOf(':');
            if (colonIndex < 0) {
                return null;
            }
            return new HeaderLine(raw.substring(0, colonIndex).strip(), raw.substring(colonIndex + 1).strip());
        }

        @Override
        public String toString() {
            if (value.isEmpty()) {
                return name;
            }
            return name + ": " + value;
        }
    }
}
