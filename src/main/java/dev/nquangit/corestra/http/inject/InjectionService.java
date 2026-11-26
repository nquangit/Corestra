package dev.nquangit.corestra.http.inject;

import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.flow.rule.InjectValueTemplate;
import dev.nquangit.corestra.flow.rule.InjectRule.WriteStrategy;
import dev.nquangit.corestra.http.transform.ValueTransformResult;
import dev.nquangit.corestra.http.transform.ValueTransformService;

import dev.nquangit.corestra.interaction.InteractiveInputPrompt;
import dev.nquangit.corestra.interaction.InteractionAbortedException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Orchestrates HTTP request mutation by applying a list of {@link InjectRule} objects.
 *
 * <p>For each enabled rule the service:
 * <ol>
 *   <li>Resolves the value — either from the supplied context map (for
 *       {@link InjectRule.ValueSourceType#VARIABLE}) or directly (for
 *       {@link InjectRule.ValueSourceType#STATIC}), or by rendering
 *       {@link InjectRule.ValueSourceType#TEMPLATE} placeholders, or asking for
 *       {@link InjectRule.ValueSourceType#INTERACTIVE} operator input.</li>
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
    private final QueryParameterInjector queryParameterInjector = new QueryParameterInjector();
    private final ValueTransformService transformService;
    private final InteractiveInputPrompt inputPrompt;

    /**
     * Creates an {@code InjectionService} with default injector instances.
     */
    public InjectionService() {
        this((name, config) -> { throw new InteractionAbortedException("Interactive input is unavailable in this execution context"); });
    }

    /** Creates injectors with an operator-input boundary; ordinary rules never invoke it. */
    public InjectionService(InteractiveInputPrompt inputPrompt) {
        this.inputPrompt = Objects.requireNonNull(inputPrompt, "inputPrompt");
        this.headerInjector = new HeaderInjector();
        this.cookieInjector = new CookieInjector();
        this.jsonBodyInjector = new JsonBodyInjector();
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
        return apply(rules, context, request, false);
    }

    /**
     * Applies mandatory injection rules, rejecting missing/blank variables and failed mutations.
     * The caller must not send the original request when this method fails.
     *
     * @param rules ordered injection rules
     * @param context actor-specific variables
     * @param request stored request to mutate
     * @return successfully mutated request
     * @throws IllegalArgumentException when an enabled rule cannot be applied
     */
    public RequestMutationResult applyRequired(List<InjectRule> rules, Map<String, String> context,
                                               MutableHttpRequest request) {
        return apply(rules, context, request, true);
    }

    private RequestMutationResult apply(List<InjectRule> rules, Map<String, String> context,
                                        MutableHttpRequest request, boolean required) {

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

            boolean mandatory = required || rule.valueSourceType() == InjectRule.ValueSourceType.INTERACTIVE;
            if (required) {
                for (String key : rule.referencedVariableKeys()) {
                    if (context.get(key) == null || context.get(key).isBlank()) {
                        throw new IllegalArgumentException("Rule '" + rule.name()
                                + "': required variable '" + key + "' is missing or blank");
                    }
                }
            }

            try {
                String value = resolveValue(rule, context, diagnostics);
                if (value == null) {
                    if (required) throw new IllegalArgumentException("Rule '" + rule.name() + "': value could not be resolved");
                    continue;
                }
                ValueTransformResult transformed = transformService.apply(value, rule.transformChain());
                if (!transformed.success()) {
                    if (rule.valueSourceType() == InjectRule.ValueSourceType.INTERACTIVE) {
                        throw new InteractionAbortedException("Transform failed for interactive rule '" + rule.name() + "'");
                    }
                    if (required) throw new IllegalArgumentException("Rule '" + rule.name() + "': transform failed");
                    diagnostics.add("Rule '" + rule.name() + "': transform failed: " + transformed.error());
                    continue;
                }
                value = transformed.value();

                if (mandatory && rule.target() == InjectRule.Target.REQUEST_QUERY_PARAMETER && requestLine.split(" ", 3).length < 2) {
                    throw new IllegalArgumentException("Cannot parse request line for required query injection");
                }
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
                        } else if (outcome.skipped()) {
                            diagnostics.add("Rule '" + rule.name() + "': " + outcome.message());
                        } else {
                            if (rule.valueSourceType() == InjectRule.ValueSourceType.INTERACTIVE) throw new InteractionAbortedException("JSON injection failed for rule '" + rule.name() + "'");
                            if (required) throw new IllegalArgumentException("Rule '" + rule.name()
                                    + "': JSON injection failed: " + outcome.message());
                            diagnostics.add("Rule '" + rule.name() + "': " + outcome.message());
                        }
                    }
                    case REQUEST_REGEX_REPLACEMENT -> {
                        String replaced = regexReplace(rule, body, value, mandatory, "request body", diagnostics);
                        if (replaced != null) {
                            body = replaced;
                        }
                    }
                    case REQUEST_FULL_REGEX_REPLACEMENT -> {
                        String raw = requestLine
                                + (headers.isEmpty() ? "" : "\r\n" + String.join("\r\n", headers))
                                + "\r\n\r\n" + body;
                        String replaced = regexReplace(rule, raw, value, mandatory, "full request", diagnostics);
                        if (replaced != null) {
                            int separator = replaced.indexOf("\r\n\r\n");
                            if (separator < 0) {
                                if (mandatory) throw new IllegalArgumentException("Rule '" + rule.name()
                                        + "': replacement removed the header/body separator");
                                diagnostics.add("Rule '" + rule.name()
                                        + "': skipped — replacement removed the header/body separator");
                            } else {
                                String[] head = replaced.substring(0, separator).split("\r\n", -1);
                                requestLine = head[0];
                                List<String> rebuilt = new ArrayList<>();
                                for (int index = 1; index < head.length; index++) {
                                    if (!head[index].isEmpty()) rebuilt.add(head[index]);
                                }
                                headers = rebuilt;
                                body = replaced.substring(separator + 4);
                            }
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
            } catch (InteractionAbortedException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                if (rule.valueSourceType() == InjectRule.ValueSourceType.INTERACTIVE) {
                    throw new InteractionAbortedException("Interactive injection failed for rule '" + rule.name() + "'");
                }
                throw exception;
            }
        }

        return RequestMutationResult.of(requestLine, headers, body, diagnostics);
    }

    // -----------------------------------------------------------------------
    // Regex replacement
    // -----------------------------------------------------------------------

    /**
     * Applies a regex replacement rule to {@code text}.
     *
     * <p>STATIC and TEMPLATE values use Java replacement syntax ({@code $1} group references).
     * VARIABLE and INTERACTIVE values come from traffic or a user and are inserted literally, so
     * a captured token containing {@code $} or {@code \} cannot be misread as a group reference.
     *
     * @return the replaced text, or {@code null} when the rule was skipped with a diagnostic
     * @throws IllegalArgumentException when a mandatory rule cannot be applied
     */
    private String regexReplace(InjectRule rule, String text, String value, boolean mandatory,
                                String scope, List<String> diagnostics) {
        boolean interactive = rule.valueSourceType() == InjectRule.ValueSourceType.INTERACTIVE;
        Pattern pattern;
        try {
            pattern = Pattern.compile(rule.targetSelector());
        } catch (java.util.regex.PatternSyntaxException invalid) {
            if (interactive) throw new InteractionAbortedException("Invalid regex for rule '" + rule.name() + "'");
            if (mandatory) throw new IllegalArgumentException("Rule '" + rule.name()
                    + "': Invalid regex pattern '" + rule.targetSelector() + "': " + invalid.getDescription());
            diagnostics.add("Rule '" + rule.name() + "': Invalid regex pattern '" + rule.targetSelector()
                    + "': " + invalid.getMessage());
            return null;
        }
        if (mandatory && !pattern.matcher(text).find()) {
            if (interactive) throw new InteractionAbortedException("Regex did not match for rule '" + rule.name() + "'");
            throw new IllegalArgumentException("Rule '" + rule.name() + "': regex did not match " + scope);
        }
        boolean literal = interactive || rule.valueSourceType() == InjectRule.ValueSourceType.VARIABLE;
        try {
            String replaced = pattern.matcher(text).replaceAll(
                    literal ? java.util.regex.Matcher.quoteReplacement(value) : value);
            diagnostics.add("Rule '" + rule.name() + "': regex replacement applied to " + scope
                    + " for pattern '" + rule.targetSelector() + "'");
            return replaced;
        } catch (IllegalArgumentException | IndexOutOfBoundsException badReplacement) {
            // e.g. "$9" referencing a missing group, or a trailing backslash in STATIC/TEMPLATE text.
            if (interactive) throw new InteractionAbortedException("Regex injection failed for rule '" + rule.name() + "'");
            if (mandatory) throw new IllegalArgumentException("Rule '" + rule.name()
                    + "': invalid replacement: " + badReplacement.getMessage());
            diagnostics.add("Rule '" + rule.name() + "': invalid replacement: " + badReplacement.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Value resolution
    // -----------------------------------------------------------------------

    /**
     * Resolves the effective injection value for a rule, returning {@code null} and adding a
     * diagnostic when a required context variable is absent.
     */
    private String resolveValue(InjectRule rule, Map<String, String> context, List<String> diagnostics) {
        if (rule.valueSourceType() == InjectRule.ValueSourceType.INTERACTIVE) {
            if (Thread.currentThread().isInterrupted()) throw new InteractionAbortedException("Input wait interrupted");
            String value = inputPrompt.ask(rule.name(), rule.interactiveInput())
                    .orElseThrow(() -> new InteractionAbortedException("Input cancelled for rule '" + rule.name() + "'"));
            if (value.isBlank()) throw new InteractionAbortedException("Input cannot be blank for rule '" + rule.name() + "'");
            return value;
        }
        if (rule.valueSourceType() == InjectRule.ValueSourceType.STATIC) {
            return rule.staticValue();
        }
        if (rule.valueSourceType() == InjectRule.ValueSourceType.TEMPLATE) {
            try {
                return InjectValueTemplate.render(rule.staticValue(), context);
            } catch (IllegalArgumentException exception) {
                diagnostics.add("Rule '" + rule.name() + "': skipped — " + exception.getMessage());
                return null;
            }
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

        QueryParameterInjector.InjectionOutcome outcome = queryParameterInjector.inject(target, paramName, value, strategy);
        if (outcome.skipped()) {
            diagnostics.add("Rule '" + ruleName + "': skipped query parameter '" + paramName + "' — already present (SKIP_IF_PRESENT strategy)");
            return requestLine;
        }

        String newTarget = outcome.target();
        String newRequestLine = version.isEmpty() ? method + " " + newTarget : method + " " + newTarget + " " + version;

        diagnostics.add("Rule '" + ruleName + "': injected query parameter '" + paramName + "'");
        return newRequestLine;
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
