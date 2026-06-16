package dev.nquangit.corestra.access.runner;

import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.RenderResult;
import dev.nquangit.corestra.access.variable.ScopedVariableStore;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.flow.StoredRequestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Applies manual auth headers and cookies from an {@link AuthState} to a rendered request.
 */
public final class ManualAuthApplier {
    private final TemplateRenderer templateRenderer;

    /**
     * Creates a manual auth applier.
     *
     * @param templateRenderer scoped template renderer for manual auth values
     */
    public ManualAuthApplier(TemplateRenderer templateRenderer) {
        this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
    }

    /**
     * Applies manual auth headers and cookies to a rendered request template.
     *
     * <p>Manual headers override existing same-name headers case-insensitively. Cookie headers are normalized
     * into one Cookie header for PR 6.</p>
     *
     * @param request rendered request
     * @param authState auth state containing manual headers and cookies
     * @param store scoped variable store used to render auth header and cookie values
     * @param mode render mode
     * @return request with manual auth material applied
     */
    public StoredRequestTemplate apply(
            StoredRequestTemplate request,
            AuthState authState,
            ScopedVariableStore store,
            RenderMode mode) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(mode, "mode");
        if (authState == null) {
            return request;
        }

        LinkedHashMap<String, HeaderLine> preservedHeaders = new LinkedHashMap<>();
        LinkedHashMap<String, String> cookies = new LinkedHashMap<>();
        for (String header : request.headerLines()) {
            HeaderLine line = HeaderLine.parse(header);
            if (line == null) {
                preservedHeaders.put(header.toLowerCase(Locale.ROOT), new HeaderLine(header, ""));
                continue;
            }
            if ("cookie".equalsIgnoreCase(line.name())) {
                parseCookies(line.value(), cookies);
            } else {
                preservedHeaders.put(line.name().toLowerCase(Locale.ROOT), line);
            }
        }

        List<HeaderLine> appendedManualHeaders = new ArrayList<>();
        for (Map.Entry<String, String> entry : authState.headers().entrySet()) {
            String renderedName = render(entry.getKey(), store, mode);
            String renderedValue = render(entry.getValue(), store, mode);
            if ("cookie".equalsIgnoreCase(renderedName)) {
                parseCookies(renderedValue, cookies);
                continue;
            }
            preservedHeaders.remove(renderedName.toLowerCase(Locale.ROOT));
            appendedManualHeaders.add(new HeaderLine(renderedName, renderedValue));
        }

        for (Map.Entry<String, String> entry : authState.cookies().entrySet()) {
            cookies.put(render(entry.getKey(), store, mode), render(entry.getValue(), store, mode));
        }

        List<String> headers = new ArrayList<>();
        preservedHeaders.values().forEach(header -> headers.add(header.toString()));
        appendedManualHeaders.forEach(header -> headers.add(header.toString()));
        if (!cookies.isEmpty()) {
            headers.add("Cookie: " + cookieHeader(cookies));
        }

        return new StoredRequestTemplate(
                request.method(),
                request.scheme(),
                request.host(),
                request.port(),
                request.pathAndQuery(),
                headers,
                request.body());
    }

    private String render(String template, ScopedVariableStore store, RenderMode mode) {
        RenderResult result = templateRenderer.render(Objects.requireNonNullElse(template, ""), store, mode);
        return result.rendered();
    }

    private static void parseCookies(String value, Map<String, String> cookies) {
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

    private static String cookieHeader(Map<String, String> cookies) {
        List<String> parts = new ArrayList<>();
        cookies.forEach((key, value) -> parts.add(key + "=" + value));
        return String.join("; ", parts);
    }

    private record HeaderLine(String name, String value) {
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
