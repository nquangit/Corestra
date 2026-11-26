package dev.nquangit.corestra.intel.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.ExtractRule.ExtractorType;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.flow.rule.InjectRule.Target;
import dev.nquangit.corestra.http.extract.ExtractionResult;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;
import dev.nquangit.corestra.intel.RawHttpMessages;
import dev.nquangit.corestra.intel.SuggestionConfidence;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Suggests extract/inject rule pairs by matching values captured from earlier steps' responses
 * against the literal values in a later step's request.
 *
 * <p>Unlike name-based suggestions, this finds {@code Authorization: Bearer <token>} for an
 * {@code access_token} JSON field, CSRF tokens echoed into form fields, cookies, and identifiers
 * returned by one step and reused in a later step's path, query or JSON body. Analysis is offline:
 * it only reads each step's captured {@code lastResponseRaw} and request template.</p>
 */
public final class ValueCorrelationService {
    private static final int MAX_VALUES_PER_STEP = 2_000;
    private static final int MAX_JSON_DEPTH = 32;
    private static final int MAX_JSON_BODY_CHARS = 1_048_576;
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");
    private static final Pattern UUID_SHAPE = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern PATH_SAFE = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Set<String> COMMON_VALUES = Set.of(
            "true", "false", "null", "none", "ok", "success", "error", "failed", "active", "enabled",
            "disabled", "pending", "user", "admin", "default", "bearer", "json", "application/json");
    private static final Set<String> IGNORED_RESPONSE_HEADERS = Set.of(
            "date", "content-length", "content-type", "server", "connection", "cache-control", "expires",
            "pragma", "vary", "transfer-encoding", "last-modified", "accept-ranges", "keep-alive",
            "strict-transport-security", "x-content-type-options", "x-frame-options", "x-xss-protection",
            "content-security-policy", "referrer-policy", "permissions-policy", "age", "location",
            "content-encoding", "alt-svc", "via", "set-cookie");
    private static final Set<String> IGNORED_REQUEST_HEADERS = Set.of(
            "host", "content-length", "content-type", "accept", "accept-encoding", "accept-language",
            "user-agent", "connection", "origin", "referer", "cache-control", "pragma", "priority", "te",
            "upgrade-insecure-requests", "cookie", "dnt");

    private final ExtractionService extraction = new ExtractionService();
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Finds correlations for one step.
     *
     * @param flow flow containing the step
     * @param targetStep step whose request should receive inject rules
     * @return suggestions ordered by confidence, then request position
     */
    public List<CorrelationSuggestion> suggest(FlowDefinition flow, FlowStep targetStep) {
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(targetStep, "targetStep");
        List<FlowStep> earlier = earlierEnabledSteps(flow, targetStep);
        if (earlier.isEmpty()) {
            return List.of();
        }

        Map<String, ValueSource> existing = new LinkedHashMap<>();
        Map<String, ValueSource> candidates = new LinkedHashMap<>();
        Set<String> takenVariables = new HashSet<>();
        flow.steps().forEach(step -> step.extractRules()
                .forEach(rule -> takenVariables.add(rule.targetVariableName().toLowerCase(Locale.ROOT))));
        StringBuilder requestsSoFar = new StringBuilder();
        for (FlowStep step : earlier) {
            requestsSoFar.append(requestText(step.requestTemplate())).append('\n');
            HttpMessageSnapshot request = RawHttpMessages.request(step.requestTemplate());
            HttpMessageSnapshot response = RawHttpMessages.responseFromBase64(step.lastResponseRaw()).orElse(null);
            for (ExtractRule rule : step.extractRules()) {
                if (!rule.enabled()) {
                    continue;
                }
                ExtractionResult result = extraction.extract(rule, request, response);
                if (result.isSuccess() && eligible(result.value())) {
                    existing.put(result.value(), new ValueSource(step, rule.targetVariableName(), null,
                            echoed(result.value(), requestsSoFar), response));
                }
            }
            if (response != null) {
                for (ResponseValue value : responseValues(response)) {
                    if (existing.containsKey(value.value())) {
                        continue;
                    }
                    candidates.put(value.value(), new ValueSource(step, value.suggestedName(), value.rule(),
                            echoed(value.value(), requestsSoFar), response));
                }
            }
        }

        Set<String> alreadyInjected = new HashSet<>();
        targetStep.injectRules().forEach(rule -> alreadyInjected.add(slot(rule.target(), rule.targetSelector())));
        Map<String, String> allocatedNames = new LinkedHashMap<>();
        List<CorrelationSuggestion> suggestions = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        for (RequestValue location : requestValues(targetStep.requestTemplate())) {
            Match match = match(location, existing, candidates);
            if (match == null) {
                continue;
            }
            Target target = location.target();
            String selector = location.selector(match.value());
            if (selector == null || alreadyInjected.contains(slot(target, selector))
                    || !emitted.add(slot(target, selector))) {
                continue;
            }
            ValueSource source = match.source();
            String variable = source.newRule() == null
                    ? source.variable()
                    : allocatedNames.computeIfAbsent(match.value(),
                    ignored -> uniqueName(source.variable(), takenVariables));
            ExtractRule newRule = source.newRule() == null ? null : withVariable(source.newRule(), variable);
            if (newRule != null && !extractsExactly(newRule, source.response(), match.value())) {
                continue; // only propose extractors that reproduce the value with the runtime extractor
            }
            InjectRule inject = location.injectRule(match.value(), variable);
            if (inject == null) {
                continue;
            }
            SuggestionConfidence confidence = confidence(match.value(), source.echoed());
            suggestions.add(new CorrelationSuggestion(
                    source.step().id(),
                    source.step().name(),
                    variable,
                    newRule,
                    inject,
                    location.description(),
                    preview(match.value()),
                    confidence,
                    reason(source, match.value())));
        }
        suggestions.sort((left, right) -> Integer.compare(left.confidence().ordinal(), right.confidence().ordinal()));
        return List.copyOf(suggestions);
    }

    // ------------------------------------------------------------------ producers

    private List<ResponseValue> responseValues(HttpMessageSnapshot response) {
        List<ResponseValue> values = new ArrayList<>();
        Set<String> seenCookieNames = new HashSet<>();
        Set<String> seenHeaderNames = new HashSet<>();
        for (String line : response.headers()) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).strip();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("set-cookie")) {
                String pair = line.substring(colon + 1).strip().split(";", 2)[0];
                int equals = pair.indexOf('=');
                if (equals > 0 && seenCookieNames.add(pair.substring(0, equals).strip())) {
                    String cookie = pair.substring(0, equals).strip();
                    addVerified(values, response, ExtractorType.COOKIE, cookie, cookie);
                }
            } else if (!IGNORED_RESPONSE_HEADERS.contains(lower) && seenHeaderNames.add(lower)) {
                addVerified(values, response, ExtractorType.HEADER, name, headerVariable(name));
            }
        }
        String body = response.body();
        String trimmed = body.stripLeading();
        if ((trimmed.startsWith("{") || trimmed.startsWith("[")) && body.length() <= MAX_JSON_BODY_CHARS) {
            try {
                visitJson(json.readTree(body), "", new ArrayList<>(), values, response, 0);
            } catch (Exception ignored) {
                // Not JSON after all: cookies and headers are still analysed.
            }
        }
        return values;
    }

    private void visitJson(JsonNode node, String pointer, List<String> names, List<ResponseValue> values,
                           HttpMessageSnapshot response, int depth) {
        if (node == null || node.isNull() || depth > MAX_JSON_DEPTH || values.size() >= MAX_VALUES_PER_STEP) {
            return;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                List<String> childNames = new ArrayList<>(names);
                childNames.add(field.getKey());
                visitJson(field.getValue(), pointer + "/" + escapePointer(field.getKey()), childNames, values,
                        response, depth + 1);
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                visitJson(node.get(index), pointer + "/" + index, names, values, response, depth + 1);
            }
        } else if (node.isTextual() || node.isNumber()) {
            String value = node.asText();
            if (eligible(value)) {
                values.add(new ResponseValue(value, jsonVariable(names),
                        rule(ExtractorType.JSON_PATH, "pointer:" + pointer, "value")));
            }
        }
    }

    private void addVerified(List<ResponseValue> values, HttpMessageSnapshot response, ExtractorType type,
                             String selector, String suggestedName) {
        ExtractRule rule = rule(type, selector, "value");
        ExtractionResult result = extraction.extract(rule, null, response);
        if (result.isSuccess() && eligible(result.value())) {
            values.add(new ResponseValue(result.value(), suggestedName, rule));
        }
    }

    // ------------------------------------------------------------------ consumers

    private List<RequestValue> requestValues(StoredRequestTemplate request) {
        List<RequestValue> values = new ArrayList<>();
        String pathAndQuery = Objects.requireNonNullElse(request.pathAndQuery(), "");
        int queryStart = pathAndQuery.indexOf('?');
        String path = queryStart < 0 ? pathAndQuery : pathAndQuery.substring(0, queryStart);
        if (!path.contains("//") && path.startsWith("/")) {
            String[] segments = path.substring(1).split("/", -1);
            for (int index = 0; index < segments.length; index++) {
                if (!segments[index].isEmpty()) {
                    values.add(RequestValue.pathSegment(index, segments[index]));
                }
            }
        }
        if (queryStart >= 0) {
            String query = pathAndQuery.substring(queryStart + 1);
            int fragment = query.indexOf('#');
            for (Map.Entry<String, String> pair : pairs(fragment < 0 ? query : query.substring(0, fragment))) {
                values.add(RequestValue.exact(Target.REQUEST_QUERY_PARAMETER, pair.getKey(), pair.getValue(),
                        "Query " + pair.getKey()));
            }
        }
        String contentType = "";
        for (String line : request.headerLines()) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("content-type")) {
                contentType = value.toLowerCase(Locale.ROOT);
            }
            if (lower.equals("cookie")) {
                for (String part : value.split(";")) {
                    int equals = part.indexOf('=');
                    if (equals > 0) {
                        String cookie = part.substring(0, equals).strip();
                        values.add(RequestValue.exact(Target.REQUEST_COOKIE, cookie,
                                part.substring(equals + 1).strip(), "Cookie " + cookie));
                    }
                }
            } else if (!IGNORED_REQUEST_HEADERS.contains(lower) && !lower.startsWith("sec-")) {
                values.add(RequestValue.header(name, value));
            }
        }
        String body = Objects.requireNonNullElse(request.body(), "");
        if (contentType.contains("application/x-www-form-urlencoded")) {
            for (Map.Entry<String, String> pair : pairs(body)) {
                values.add(RequestValue.exact(Target.REQUEST_FORM_FIELD, pair.getKey(), pair.getValue(),
                        "Form " + pair.getKey()));
            }
        } else if (contentType.contains("json") || body.stripLeading().startsWith("{")) {
            try {
                JsonNode root = json.readTree(body);
                if (root != null && root.isObject()) {
                    collectJsonLeaves(root, "", values, 0);
                }
            } catch (Exception ignored) {
                // Malformed JSON bodies cannot receive structured JSON injection.
            }
        }
        return values;
    }

    private void collectJsonLeaves(JsonNode node, String path, List<RequestValue> values, int depth) {
        if (node == null || depth > MAX_JSON_DEPTH) {
            return;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String key = field.getKey();
                if (key.isEmpty() || key.contains(".") || key.contains("[") || key.contains("]") || key.startsWith("$")
                        || key.chars().allMatch(Character::isDigit)) {
                    continue;
                }
                collectJsonLeaves(field.getValue(), path.isEmpty() ? key : path + "." + key, values, depth + 1);
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                collectJsonLeaves(node.get(index), path + "[" + index + "]", values, depth + 1);
            }
        } else if ((node.isTextual() || node.isNumber()) && !path.isEmpty()) {
            values.add(RequestValue.exact(Target.REQUEST_JSON_PATH, path, node.asText(), "JSON " + path));
        }
    }

    private Match match(RequestValue location, Map<String, ValueSource> existing, Map<String, ValueSource> candidates) {
        if (location.kind() == RequestKind.HEADER) {
            Match exact = lookup(location.value(), existing, candidates);
            if (exact != null) {
                return exact;
            }
            Match best = null;
            for (Map<String, ValueSource> sources : List.of(existing, candidates)) {
                for (Map.Entry<String, ValueSource> entry : sources.entrySet()) {
                    String value = entry.getKey();
                    if (value.length() >= 8 && containsAsToken(location.value(), value)
                            && (best == null || value.length() > best.value().length())) {
                        best = new Match(value, entry.getValue());
                    }
                }
                if (best != null) {
                    return best;
                }
            }
            return null;
        }
        return lookup(location.value(), existing, candidates);
    }

    private static Match lookup(String value, Map<String, ValueSource> existing, Map<String, ValueSource> candidates) {
        if (!eligible(value)) {
            return null;
        }
        ValueSource source = existing.get(value);
        if (source == null) {
            source = candidates.get(value);
        }
        return source == null ? null : new Match(value, source);
    }

    // ------------------------------------------------------------------ helpers

    static boolean eligible(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.strip();
        if (!trimmed.equals(value) || trimmed.isEmpty() || trimmed.length() > 4_096) {
            return false;
        }
        if (trimmed.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        if (COMMON_VALUES.contains(trimmed.toLowerCase(Locale.ROOT))) {
            return false;
        }
        boolean digitsOnly = trimmed.chars().allMatch(Character::isDigit);
        if (digitsOnly) {
            return trimmed.length() >= 3;
        }
        if (trimmed.chars().allMatch(ch -> Character.isDigit(ch) || ch == '.' || ch == '-')) {
            return false; // decimals, negative numbers, dates such as 2024-01-01 are too ambiguous
        }
        return trimmed.length() >= 5;
    }

    private static SuggestionConfidence confidence(String value, boolean echoed) {
        if (echoed) {
            return SuggestionConfidence.LOW;
        }
        if (JWT.matcher(value).matches() || UUID_SHAPE.matcher(value).matches()) {
            return SuggestionConfidence.HIGH;
        }
        boolean hasLetter = value.chars().anyMatch(Character::isLetter);
        boolean hasDigit = value.chars().anyMatch(Character::isDigit);
        if (value.length() >= 16 && hasLetter && hasDigit) {
            return SuggestionConfidence.HIGH;
        }
        if (value.length() >= 6 || value.chars().allMatch(Character::isDigit)) {
            return SuggestionConfidence.MEDIUM;
        }
        return SuggestionConfidence.LOW;
    }

    private static String reason(ValueSource source, String value) {
        String origin = source.newRule() == null
                ? "extractor {{" + source.variable() + "}} of step '" + source.step().name() + "' yields this value"
                : "captured response of step '" + source.step().name() + "' contains this value";
        if (source.echoed()) {
            return origin + "; the value also appears in an earlier request, so it may be a fixed input";
        }
        return origin + " (" + value.length() + " chars)";
    }

    private boolean extractsExactly(ExtractRule rule, HttpMessageSnapshot response, String expected) {
        if (response == null) {
            return false;
        }
        ExtractionResult result = extraction.extract(rule, null, response);
        return result.isSuccess() && expected.equals(result.value());
    }

    private static boolean echoed(String value, StringBuilder requestsSoFar) {
        return requestsSoFar.indexOf(value) >= 0;
    }

    private static String requestText(StoredRequestTemplate request) {
        return request.method() + " " + request.pathAndQuery() + "\n"
                + String.join("\n", request.headerLines()) + "\n\n" + request.body();
    }

    private static boolean containsAsToken(String haystack, String needle) {
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            boolean leftOk = index == 0 || !Character.isLetterOrDigit(haystack.charAt(index - 1));
            int end = index + needle.length();
            boolean rightOk = end == haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            index = haystack.indexOf(needle, index + 1);
        }
        return false;
    }

    private static List<FlowStep> earlierEnabledSteps(FlowDefinition flow, FlowStep targetStep) {
        List<FlowStep> earlier = new ArrayList<>();
        for (FlowStep step : flow.steps()) {
            if (step.id().equals(targetStep.id())) {
                return earlier;
            }
            if (step.enabled()) {
                earlier.add(step);
            }
        }
        return List.of();
    }

    private static List<Map.Entry<String, String>> pairs(String encoded) {
        List<Map.Entry<String, String>> pairs = new ArrayList<>();
        if (encoded == null || encoded.isBlank()) {
            return pairs;
        }
        for (String pair : encoded.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            Optional<String> name = decode(pair.substring(0, equals));
            Optional<String> value = decode(pair.substring(equals + 1));
            if (name.isPresent() && value.isPresent()) {
                pairs.add(Map.entry(name.get(), value.get()));
            }
        }
        return pairs;
    }

    private static Optional<String> decode(String value) {
        try {
            return Optional.of(URLDecoder.decode(value, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static String slot(Target target, String selector) {
        String normalized = target == Target.REQUEST_HEADER ? selector.toLowerCase(Locale.ROOT) : selector;
        return target + "\u0000" + normalized;
    }

    private static ExtractRule rule(ExtractorType type, String selector, String variable) {
        return new ExtractRule(UUID.randomUUID().toString(), "Extract " + variable, true,
                ExtractRule.SourceSide.RESPONSE, type, selector, variable, List.of());
    }

    private static ExtractRule withVariable(ExtractRule template, String variable) {
        return new ExtractRule(template.id(), "Extract " + variable + " (suggested)", true,
                ExtractRule.SourceSide.RESPONSE, template.extractorType(), template.sourceSelector(), variable,
                List.of());
    }

    private static String uniqueName(String base, Set<String> taken) {
        String cleaned = base.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (cleaned.isBlank() || cleaned.chars().allMatch(ch -> ch == '_')) {
            cleaned = "value";
        }
        String name = cleaned;
        for (int suffix = 2; taken.contains(name.toLowerCase(Locale.ROOT)); suffix++) {
            name = cleaned + suffix;
        }
        taken.add(name.toLowerCase(Locale.ROOT));
        return name;
    }

    static String headerVariable(String header) {
        String stripped = header.toLowerCase(Locale.ROOT).startsWith("x-") ? header.substring(2) : header;
        return camel(stripped.split("[-_]"));
    }

    static String jsonVariable(List<String> names) {
        if (names.isEmpty()) {
            return "value";
        }
        String last = names.getLast();
        if (names.size() >= 2 && (last.equalsIgnoreCase("id") || last.equalsIgnoreCase("uuid"))) {
            return camel(new String[]{names.get(names.size() - 2), last});
        }
        return last;
    }

    private static String camel(String[] parts) {
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            String lower = part.toLowerCase(Locale.ROOT);
            if (result.isEmpty()) {
                result.append(part.equals(part.toUpperCase(Locale.ROOT)) ? lower : Character.toLowerCase(part.charAt(0))
                        + part.substring(1));
            } else {
                result.append(Character.toUpperCase(lower.charAt(0))).append(lower.substring(1));
            }
        }
        return result.isEmpty() ? "value" : result.toString();
    }

    static String preview(String value) {
        if (value.length() <= 12) {
            return value;
        }
        return value.substring(0, 6) + "…" + value.substring(value.length() - 4) + " (" + value.length() + " chars)";
    }

    private static String escapePointer(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }

    // ------------------------------------------------------------------ records

    private record ValueSource(FlowStep step, String variable, ExtractRule newRule, boolean echoed,
                               HttpMessageSnapshot response) {
    }

    private record ResponseValue(String value, String suggestedName, ExtractRule rule) {
    }

    private record Match(String value, ValueSource source) {
    }

    private enum RequestKind { EXACT, HEADER, PATH }

    private record RequestValue(RequestKind kind, Target target, String name, String value, int segmentIndex,
                                String description) {
        static RequestValue exact(Target target, String name, String value, String description) {
            return new RequestValue(RequestKind.EXACT, target, name, value, -1, description);
        }

        static RequestValue header(String name, String value) {
            return new RequestValue(RequestKind.HEADER, Target.REQUEST_HEADER, name, value, -1, "Header " + name);
        }

        static RequestValue pathSegment(int index, String value) {
            return new RequestValue(RequestKind.PATH, Target.REQUEST_FULL_REGEX_REPLACEMENT, "", value, index,
                    "Path segment " + (index + 1));
        }

        String selector(String matchedValue) {
            if (kind == RequestKind.PATH) {
                if (!PATH_SAFE.matcher(matchedValue).matches()) {
                    return null;
                }
                // Lookarounds keep the match to the segment itself, so the rule can use a literal
                // VARIABLE value (no $-group syntax for runtime values to break).
                // Java look-behinds need an obvious maximum length, so earlier segments are unrolled.
                return "(?<=^\\S{1,24} /" + "[^/?# ]{0,512}/".repeat(segmentIndex) + ")"
                        + Pattern.quote(matchedValue) + "(?=[/?# ])";
            }
            return name;
        }

        InjectRule injectRule(String matchedValue, String variable) {
            String id = UUID.randomUUID().toString();
            String ruleName = "Inject {{" + variable + "}} into " + description;
            return switch (kind) {
                case EXACT -> new InjectRule(id, ruleName, true, target, name,
                        InjectRule.ValueSourceType.VARIABLE, variable, "", InjectRule.WriteStrategy.OVERWRITE);
                case HEADER -> {
                    if (value.equals(matchedValue)) {
                        yield new InjectRule(id, ruleName, true, target, name,
                                InjectRule.ValueSourceType.VARIABLE, variable, "", InjectRule.WriteStrategy.OVERWRITE);
                    }
                    if (value.contains("{") || value.contains("}") || value.indexOf(matchedValue) != value.lastIndexOf(matchedValue)) {
                        yield null;
                    }
                    yield new InjectRule(id, ruleName, true, target, name, InjectRule.ValueSourceType.TEMPLATE, "",
                            value.replace(matchedValue, "{{" + variable + "}}"), InjectRule.WriteStrategy.OVERWRITE);
                }
                case PATH -> {
                    String selector = selector(matchedValue);
                    // urlencode keeps a later runtime value with '/', '?' or spaces inside its segment.
                    yield selector == null ? null : new InjectRule(id, ruleName, true, target, selector,
                            InjectRule.ValueSourceType.VARIABLE, variable, "",
                            InjectRule.WriteStrategy.OVERWRITE, List.of("urlencode"));
                }
            };
        }
    }
}
