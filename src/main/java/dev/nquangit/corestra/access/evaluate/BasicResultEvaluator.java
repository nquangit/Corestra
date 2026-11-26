package dev.nquangit.corestra.access.evaluate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.result.Verdict;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Minimal ALLOW/DENY response evaluator for manual single access tests.
 */
public final class BasicResultEvaluator {
    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    /**
     * Evaluates a response status code against an expected access result.
     *
     * @param expected expected access outcome
     * @param statusCode HTTP response status code
     * @return evaluation result
     */
    public EvaluationResult evaluate(ExpectedResult expected, int statusCode) {
        ExpectedResult normalizedExpected = Objects.requireNonNullElse(expected, ExpectedResult.REVIEW);
        if (isServerError(statusCode)) {
            return new EvaluationResult(Verdict.ERROR, "Server error response: " + statusCode, List.of());
        }
        if (isRedirect(statusCode)) {
            return new EvaluationResult(Verdict.REVIEW, "Redirect response requires review: " + statusCode, List.of());
        }
        return switch (normalizedExpected) {
            case DENY -> evaluateDeny(statusCode);
            case ALLOW -> evaluateAllow(statusCode);
            case REVIEW, CUSTOM, MASKED, READ_ONLY -> new EvaluationResult(
                    Verdict.REVIEW,
                    "Advanced evaluation is not implemented yet for " + normalizedExpected,
                    List.of());
        };
    }

    /**
     * Evaluates a response using an optional advanced evaluation profile.
     *
     * @param expected expected access outcome
     * @param statusCode HTTP response status code
     * @param rawResponse raw HTTP response
     * @param profile selected evaluation profile, or null for status-only fallback
     * @return evaluation result
     */
    public EvaluationResult evaluate(
            ExpectedResult expected,
            int statusCode,
            String rawResponse,
            EvaluationProfile profile) {
        if (profile == null || !profile.enabled()) {
            return evaluate(expected, statusCode);
        }
        ExpectedResult normalizedExpected = Objects.requireNonNullElse(expected, ExpectedResult.REVIEW);
        ResponseSnapshot snapshot = ResponseSnapshot.from(statusCode, rawResponse);
        List<String> warnings = new ArrayList<>();
        boolean denyMatched = matchesAny(profile.denyIndicators(), snapshot, warnings);
        boolean allowMatched = matchesAny(profile.allowIndicators(), snapshot, warnings);

        if (normalizedExpected == ExpectedResult.MASKED) {
            if (denyMatched) {
                return new EvaluationResult(Verdict.SUSPICIOUS,
                        "Expected masked response but deny indicator matched", warnings);
            }
            Optional<String> leak = sensitiveLeak(profile, snapshot, warnings);
            if (leak.isPresent()) {
                return new EvaluationResult(Verdict.SUSPICIOUS, leak.get(), warnings);
            }
            if (allowMatched || isAllowed(statusCode)) {
                return new EvaluationResult(Verdict.OK, "Sensitive fields are absent or masked", warnings);
            }
            return new EvaluationResult(Verdict.REVIEW,
                    "Sensitive fields are absent or masked, but response outcome needs review", warnings);
        }

        if (denyMatched) {
            return normalizedExpected == ExpectedResult.DENY
                    ? new EvaluationResult(Verdict.OK, "Denied as expected by evaluation profile", warnings)
                    : new EvaluationResult(Verdict.SUSPICIOUS, "Deny indicator matched evaluation profile", warnings);
        }
        if (allowMatched) {
            return normalizedExpected == ExpectedResult.ALLOW
                    ? new EvaluationResult(Verdict.OK, "Allowed as expected by evaluation profile", warnings)
                    : new EvaluationResult(Verdict.SUSPICIOUS, "Allow indicator matched evaluation profile", warnings);
        }

        EvaluationResult fallback = evaluate(normalizedExpected, statusCode);
        return new EvaluationResult(fallback.verdict(), fallback.reason(), warnings(warnings, fallback.warnings()));
    }

    private boolean matchesAny(
            List<ResponseIndicator> indicators,
            ResponseSnapshot snapshot,
            List<String> warnings) {
        for (ResponseIndicator indicator : indicators) {
            if (indicator.enabled() && matches(indicator, snapshot, warnings)) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(ResponseIndicator indicator, ResponseSnapshot snapshot, List<String> warnings) {
        return switch (indicator.type()) {
            case STATUS_CODE_IN -> matchesStatus(indicator, snapshot.statusCode(), warnings);
            case BODY_CONTAINS -> snapshot.body().contains(indicator.expectedValue());
            case BODY_NOT_CONTAINS -> !snapshot.body().contains(indicator.expectedValue());
            case HEADER_EXISTS -> snapshot.header(indicator.selector()).isPresent();
            case HEADER_VALUE_CONTAINS -> snapshot.header(indicator.selector())
                    .map(value -> value.contains(indicator.expectedValue()))
                    .orElse(false);
            case JSON_PATH_EQUALS -> jsonValues(snapshot, indicator.selector()).stream()
                    .anyMatch(node -> jsonComparableValue(node).equals(indicator.expectedValue()));
            case JSON_PATH_EXISTS -> !jsonValues(snapshot, indicator.selector()).isEmpty();
            case JSON_PATH_NOT_EXISTS -> jsonValues(snapshot, indicator.selector()).isEmpty();
        };
    }

    private boolean matchesStatus(ResponseIndicator indicator, int statusCode, List<String> warnings) {
        String source = !indicator.expectedValue().isBlank() ? indicator.expectedValue() : indicator.selector();
        if (source.isBlank()) {
            warnings.add("STATUS_CODE_IN indicator has no status list: " + indicator.name());
            return false;
        }
        for (String part : source.split(",")) {
            String token = part.strip();
            if (token.isEmpty()) {
                continue;
            }
            if (token.contains("-")) {
                String[] bounds = token.split("-", 2);
                try {
                    int lower = Integer.parseInt(bounds[0].strip());
                    int upper = Integer.parseInt(bounds[1].strip());
                    if (statusCode >= lower && statusCode <= upper) {
                        return true;
                    }
                } catch (NumberFormatException exception) {
                    warnings.add("Invalid status range in indicator " + indicator.name() + ": " + token);
                }
                continue;
            }
            try {
                if (statusCode == Integer.parseInt(token)) {
                    return true;
                }
            } catch (NumberFormatException exception) {
                warnings.add("Invalid status code in indicator " + indicator.name() + ": " + token);
            }
        }
        return false;
    }

    private Optional<String> sensitiveLeak(
            EvaluationProfile profile,
            ResponseSnapshot snapshot,
            List<String> warnings) {
        for (SensitiveFieldRule rule : profile.sensitiveFieldRules()) {
            if (!rule.enabled()) {
                continue;
            }
            List<JsonNode> values = jsonValues(snapshot, rule.jsonPath());
            for (int index = 0; index < values.size(); index++) {
                String text = jsonComparableValue(values.get(index));
                if (!isMasked(rule.jsonPath(), text, profile.maskedFieldRules(), warnings)) {
                    return Optional.of("Sensitive field leaked: " + rule.jsonPath()
                            + (values.size() > 1 ? " (match " + (index + 1) + " of " + values.size() + ")" : ""));
                }
            }
        }
        return Optional.empty();
    }

    private boolean isMasked(
            String jsonPath,
            String value,
            List<MaskingRule> maskingRules,
            List<String> warnings) {
        for (MaskingRule rule : maskingRules) {
            if (!rule.enabled() || !rule.jsonPath().equals(jsonPath)) {
                continue;
            }
            try {
                if (Pattern.compile(rule.maskedValuePattern()).matcher(value).matches()) {
                    return true;
                }
            } catch (PatternSyntaxException exception) {
                warnings.add("Invalid masking pattern for " + rule.jsonPath() + ": " + exception.getMessage());
            }
        }
        return false;
    }

    private List<JsonNode> jsonValues(ResponseSnapshot snapshot, String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        return JsonPathSelector.select(snapshot.jsonBody(objectMapper).orElse(null), path);
    }

    private String jsonComparableValue(JsonNode node) {
        return JsonPathSelector.comparableText(node);
    }

    private EvaluationResult evaluateDeny(int statusCode) {
        if (isDenied(statusCode)) {
            return new EvaluationResult(Verdict.OK, "Denied as expected with status " + statusCode, List.of());
        }
        if (isAllowed(statusCode)) {
            return new EvaluationResult(Verdict.SUSPICIOUS, "Expected deny but received status " + statusCode, List.of());
        }
        return new EvaluationResult(Verdict.REVIEW, "Unexpected status for deny expectation: " + statusCode, List.of());
    }

    private EvaluationResult evaluateAllow(int statusCode) {
        if (isAllowed(statusCode)) {
            return new EvaluationResult(Verdict.OK, "Allowed as expected with status " + statusCode, List.of());
        }
        if (isDenied(statusCode)) {
            return new EvaluationResult(Verdict.SUSPICIOUS, "Expected allow but received status " + statusCode, List.of());
        }
        return new EvaluationResult(Verdict.REVIEW, "Unexpected status for allow expectation: " + statusCode, List.of());
    }

    private static boolean isAllowed(int statusCode) {
        return statusCode == 200 || statusCode == 201 || statusCode == 204;
    }

    private static boolean isDenied(int statusCode) {
        return statusCode == 401 || statusCode == 403 || statusCode == 404;
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode >= 300 && statusCode < 400;
    }

    private static boolean isServerError(int statusCode) {
        return statusCode >= 500 && statusCode < 600;
    }

    private static List<String> warnings(List<String> first, List<String> second) {
        ArrayList<String> warnings = new ArrayList<>();
        warnings.addAll(first);
        warnings.addAll(second);
        return List.copyOf(warnings);
    }

    private static final class ResponseSnapshot {
        private final int statusCode;
        private final Map<String, String> headers;
        private final String body;
        private JsonNode parsedJson;
        private boolean jsonParsed;

        private ResponseSnapshot(int statusCode, Map<String, String> headers, String body) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
        }

        private static ResponseSnapshot from(int statusCode, String rawResponse) {
            String raw = Objects.requireNonNullElse(rawResponse, "");
            String[] sections = raw.split("\\r?\\n\\r?\\n", 2);
            String head = sections.length > 0 ? sections[0] : "";
            String body = sections.length > 1 ? sections[1] : "";
            LinkedHashMap<String, String> headers = new LinkedHashMap<>();
            String[] lines = head.split("\\r?\\n");
            for (int index = 1; index < lines.length; index++) {
                String line = lines[index];
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT),
                            line.substring(colon + 1).strip());
                }
            }
            return new ResponseSnapshot(statusCode, Map.copyOf(headers), body);
        }

        private int statusCode() {
            return statusCode;
        }

        private String body() {
            return body;
        }

        private Optional<String> header(String name) {
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(headers.get(name.strip().toLowerCase(Locale.ROOT)));
        }

        private Optional<JsonNode> jsonBody(ObjectMapper objectMapper) {
            if (!jsonParsed) {
                jsonParsed = true;
                try {
                    parsedJson = objectMapper.readTree(body);
                } catch (RuntimeException | java.io.IOException ignored) {
                    parsedJson = null;
                }
            }
            return Optional.ofNullable(parsedJson);
        }
    }
}
