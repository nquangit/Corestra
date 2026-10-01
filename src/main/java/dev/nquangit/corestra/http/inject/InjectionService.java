package dev.nquangit.corestra.http.inject;

import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.flow.rule.InjectRule.WriteStrategy;
import dev.nquangit.corestra.http.transform.ValueTransformResult;
import dev.nquangit.corestra.http.transform.ValueTransformService;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Orchestrates HTTP request mutation by applying a list of {@link InjectRule} objects.
 *
 * <p>For each enabled rule the service:
 * <ol>
 *   <li>Resolves the value — either from the supplied context map (for
 *       {@link InjectRule.ValueSourceType#VARIABLE}) or directly (for
 *       {@link InjectRule.ValueSourceType#STATIC}).</li>
 *   <li>Dispatches to the appropriate injector based on {@link InjectRule#target()}.</li>
 *   <li>Accumulates per-rule diagnostic messages.</li>
 * </ol>
 *
 * <p>Rules that reference a missing context variable are skipped with a diagnostic entry.
 * Failures within individual injectors are recorded in diagnostics without stopping
 * the processing of subsequent rules.
 */
public final class InjectionService {

    private final HeaderInjector headerInjector;
    private final CookieInjector cookieInjector;
    private final JsonBodyInjector jsonBodyInjector;
    private final RegexBodyInjector regexBodyInjector;
    private final ValueTransformService transformService;

    /**
     * Creates an {@code InjectionService} with default injector instances.
     */
    public InjectionService() {
        this.headerInjector = new HeaderInjector();
        this.cookieInjector = new CookieInjector();
        this.jsonBodyInjector = new JsonBodyInjector();
        this.regexBodyInjector = new RegexBodyInjector();
        this.transformService = new ValueTransformService();
    }

    /**
     * Applies all enabled rules in {@code rules} to {@code request}.
     *
     * @param rules   the ordered list of injection rules to apply; must not be {@code null}
     * @param context the variable map used to resolve {@link InjectRule.ValueSourceType#VARIABLE}
     *                values; must not be {@code null}
     * @param request the mutable request model to mutate; must not be {@code null}
     * @return a {@link RequestMutationResult} containing the final headers, body, and diagnostics
     */
    public RequestMutationResult apply(
            List<InjectRule> rules,
            Map<String, String> context,
            MutableHttpRequest request) {

        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(request, "request");

        List<String> headers = new ArrayList<>(request.headers());
        String body = request.body();
        String requestLine = request.requestLine();
        List<String> diagnostics = new ArrayList<>();

        for (InjectRule rule : rules) {
            if (!rule.enabled()) {
                continue;
            }

            String value = resolveValue(rule, context, diagnostics);
            if (value == null) {
                continue;
            }
            ValueTransformResult transformed = transformService.apply(value, rule.transformChain());
            if (!transformed.success()) {
                diagnostics.add("Rule '" + rule.name() + "': transform failed: " + transformed.error());
                continue;
            }
            value = transformed.value();

            switch (rule.target()) {
                case REQUEST_HEADER -> {
                    headers = headerInjector.inject(headers, rule.targetSelector(), value, rule.writeStrategy());
                    diagnostics.add("Rule '" + rule.name() + "': injected header '" + rule.targetSelector() + "'");
                }
                case REQUEST_COOKIE -> {
                    headers = cookieInjector.inject(headers, rule.targetSelector(), value, rule.writeStrategy());
                    diagnostics.add("Rule '" + rule.name() + "': injected cookie '" + rule.targetSelector() + "'");
                }
                case REQUEST_QUERY_PARAMETER -> {
                    requestLine = injectQueryParam(requestLine, rule.targetSelector(), value, rule.writeStrategy(), rule.name(), diagnostics);
                }
                case REQUEST_FORM_FIELD -> {
                    body = injectFormField(body, rule.targetSelector(), value, rule.writeStrategy(), rule.name(), diagnostics);
                }
                case REQUEST_JSON_PATH -> {
                    JsonBodyInjector.InjectionOutcome outcome =
                            jsonBodyInjector.inject(body, rule.targetSelector(), value, rule.writeStrategy());
                    if (outcome.succeeded()) {
                        body = outcome.body();
                        diagnostics.add("Rule '" + rule.name() + "': injected JSON path '" + rule.targetSelector() + "'");
                    } else {
                        diagnostics.add("Rule '" + rule.name() + "': " + outcome.message());
                    }
                }
                case REQUEST_REGEX_REPLACEMENT -> {
                    RegexBodyInjector.ReplaceOutcome outcome =
                            regexBodyInjector.inject(body, rule.targetSelector(), value);
                    if (outcome.succeeded()) {
                        body = outcome.body();
                        diagnostics.add("Rule '" + rule.name() + "': regex replacement applied for pattern '" + rule.targetSelector() + "'");
                    } else {
                        diagnostics.add("Rule '" + rule.name() + "': " + outcome.failureReason());
                    }
                }
                case REQUEST_RAW_BODY_APPEND -> {
                    body = body + value;
                    diagnostics.add("Rule '" + rule.name() + "': appended raw body content");
                }
                case REQUEST_RAW_BODY_REPLACE -> {
                    body = value;
                    diagnostics.add("Rule '" + rule.name() + "': replaced raw body content");
                }
            }
        }

        return RequestMutationResult.of(requestLine, headers, body, diagnostics);
    }

    // -----------------------------------------------------------------------
    // Value resolution
    // -----------------------------------------------------------------------

    /**
     * Resolves the effective injection value for a rule, returning {@code null} and adding a
     * diagnostic when a required context variable is absent.
     */
    private String resolveValue(InjectRule rule, Map<String, String> context, List<String> diagnostics) {
        if (rule.valueSourceType() == InjectRule.ValueSourceType.STATIC) {
            return rule.staticValue();
        }
        String key = rule.valueSourceKey();
        if (!context.containsKey(key)) {
            diagnostics.add("Rule '" + rule.name() + "': skipped — context variable '" + key + "' not found");
            return null;
        }
        return context.get(key);
    }

    // -----------------------------------------------------------------------
    // Query-parameter injection
    // -----------------------------------------------------------------------

    /**
     * Injects a query parameter into the request line (e.g. {@code GET /path?key=val HTTP/1.1}).
     */
    private String injectQueryParam(
            String requestLine,
            String paramName,
            String value,
            WriteStrategy strategy,
            String ruleName,
            List<String> diagnostics) {

        // Request line format: METHOD SP request-target SP HTTP-version
        String[] parts = requestLine.split(" ", 3);
        if (parts.length < 2) {
            diagnostics.add("Rule '" + ruleName + "': cannot parse request line for query injection");
            return requestLine;
        }

        String method = parts[0];
        String target = parts[1];
        String version = parts.length > 2 ? parts[2] : "";

        int queryStart = target.indexOf('?');
        String path = queryStart >= 0 ? target.substring(0, queryStart) : target;
        String queryString = queryStart >= 0 ? target.substring(queryStart + 1) : "";

        Map<String, String> params = parseQueryString(queryString);
        boolean present = params.containsKey(paramName);

        if (strategy == WriteStrategy.SKIP_IF_PRESENT && present) {
            diagnostics.add("Rule '" + ruleName + "': skipped query parameter '" + paramName + "' — already present (SKIP_IF_PRESENT strategy)");
            return requestLine;
        }

        if (strategy == WriteStrategy.APPEND || strategy == WriteStrategy.OVERWRITE || !present) {
            params.put(paramName, value);
        }

        String newQuery = buildQueryString(params);
        String newTarget = newQuery.isEmpty() ? path : path + "?" + newQuery;
        String newRequestLine = version.isEmpty() ? method + " " + newTarget : method + " " + newTarget + " " + version;

        diagnostics.add("Rule '" + ruleName + "': injected query parameter '" + paramName + "'");
        return newRequestLine;
    }

    private Map<String, String> parseQueryString(String queryString) {
        Map<String, String> params = new LinkedHashMap<>();
        if (queryString == null || queryString.isBlank()) {
            return params;
        }
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                params.put(decode(pair), "");
            } else {
                params.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return params;
    }

    private String buildQueryString(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return sb.toString();
    }

    private String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    // Form-field injection
    // -----------------------------------------------------------------------

    /**
     * Injects a form field into a {@code application/x-www-form-urlencoded} body.
     */
    private String injectFormField(
            String body,
            String fieldName,
            String value,
            WriteStrategy strategy,
            String ruleName,
            List<String> diagnostics) {

        Map<String, String> fields = parseFormBody(body);
        boolean present = fields.containsKey(fieldName);

        if (strategy == WriteStrategy.SKIP_IF_PRESENT && present) {
            diagnostics.add("Rule '" + ruleName + "': skipped form field '" + fieldName + "' — already present (SKIP_IF_PRESENT strategy)");
            return body;
        }

        if (strategy == WriteStrategy.APPEND || strategy == WriteStrategy.OVERWRITE || !present) {
            fields.put(fieldName, value);
        }

        diagnostics.add("Rule '" + ruleName + "': injected form field '" + fieldName + "'");
        return buildFormBody(fields);
    }

    private Map<String, String> parseFormBody(String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return fields;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                fields.put(decode(pair), "");
            } else {
                fields.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return fields;
    }

    private String buildFormBody(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // MutableHttpRequest
    // -----------------------------------------------------------------------

    /**
     * A plain, Montoya-free representation of an HTTP request suitable for mutation by
     * {@link InjectionService}.
     *
     * <p>The {@code requestLine} carries the HTTP request line
     * (e.g. {@code "GET /path?q=1 HTTP/1.1"}). The {@code headers} list holds header lines
     * in {@code "Name: Value"} format. The {@code body} carries the raw request body.
     */
    public record MutableHttpRequest(
            String requestLine,
            List<String> headers,
            String body) {

        /**
         * Creates a {@code MutableHttpRequest}.
         *
         * @param requestLine the HTTP request line; must not be {@code null}
         * @param headers     the header lines; must not be {@code null}
         * @param body        the request body; must not be {@code null}
         */
        public MutableHttpRequest {
            Objects.requireNonNull(requestLine, "requestLine");
            Objects.requireNonNull(headers, "headers");
            Objects.requireNonNull(body, "body");
            headers = List.copyOf(headers);
        }
    }
}
