package dev.nquangit.corestra.flow.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.nquangit.corestra.flow.execution.ExecutionContext;
import dev.nquangit.corestra.flow.execution.StepExecutionRecord;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
 * Evaluates post-step conditions against the latest step record and execution context.
 */
public final class StepConditionEvaluator {
    private final ObjectMapper objectMapper;

    /**
     * Creates an evaluator with a default Jackson mapper.
     */
    public StepConditionEvaluator() {
        this(JsonMapper.builder().build());
    }

    /**
     * Creates an evaluator with an explicit mapper.
     *
     * @param objectMapper mapper used for JSON body parsing
     */
    public StepConditionEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Evaluates a condition/action rule.
     *
     * <p>Disabled conditions are ignored. When a rule has no enabled conditions it matches by
     * design, which lets users define explicit "always" actions.</p>
     *
     * @param rule condition/action rule
     * @param record latest step execution record
     * @param context current execution context
     * @param warnings mutable warning list
     * @return {@code true} when the rule matches
     */
    public boolean matchesRule(
            StepConditionActionRule rule,
            StepExecutionRecord record,
            ExecutionContext context,
            List<String> warnings) {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(warnings, "warnings");

        List<StepCondition> enabled = rule.conditions().stream()
                .filter(StepCondition::enabled)
                .toList();
        if (enabled.isEmpty()) {
            return true;
        }
        if (rule.matchPolicy() == ConditionMatchPolicy.ANY) {
            for (StepCondition condition : enabled) {
                if (matches(condition, record, context, warnings)) {
                    return true;
                }
            }
            return false;
        }
        for (StepCondition condition : enabled) {
            if (!matches(condition, record, context, warnings)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Evaluates a single condition.
     *
     * @param condition condition to evaluate
     * @param record latest step execution record
     * @param context current execution context
     * @param warnings mutable warning list
     * @return {@code true} when the condition matches
     */
    public boolean matches(
            StepCondition condition,
            StepExecutionRecord record,
            ExecutionContext context,
            List<String> warnings) {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(warnings, "warnings");

        Optional<String> actual = resolveActual(condition, record, context, warnings);
        boolean matched = compare(condition, actual, context, warnings);
        return condition.negate() ? !matched : matched;
    }

    private Optional<String> resolveActual(
            StepCondition condition,
            StepExecutionRecord record,
            ExecutionContext context,
            List<String> warnings) {
        MessageParts request = null;
        MessageParts response = null;
        return switch (condition.source()) {
            case EXECUTION_FAILURE -> Optional.of(Boolean.toString(!record.success()));
            case FAILURE_TYPE -> Optional.of(record.failureType().name());
            case ERROR_MESSAGE -> record.errorMessage().isBlank() ? Optional.empty() : Optional.of(record.errorMessage());
            case RESPONSE_STATUS -> record.responseStatusCode() > 0
                    ? Optional.of(Integer.toString(record.responseStatusCode()))
                    : Optional.empty();
            case RESPONSE_HEADER -> {
                response = MessageParts.parse(record.receivedResponse());
                yield response.header(condition.selector());
            }
            case RESPONSE_BODY -> {
                response = MessageParts.parse(record.receivedResponse());
                yield Optional.of(response.body());
            }
            case RESPONSE_JSON_PATH -> {
                response = MessageParts.parse(record.receivedResponse());
                yield jsonValue(response.body(), condition.selector(), warnings);
            }
            case RESPONSE_FORM_FIELD -> {
                response = MessageParts.parse(record.receivedResponse());
                yield Optional.ofNullable(parseForm(response.body()).get(condition.selector()));
            }
            case REQUEST_HEADER -> {
                request = MessageParts.parse(record.renderedRequest());
                yield request.header(condition.selector());
            }
            case REQUEST_BODY -> {
                request = MessageParts.parse(record.renderedRequest());
                yield Optional.of(request.body());
            }
            case REQUEST_JSON_PATH -> {
                request = MessageParts.parse(record.renderedRequest());
                yield jsonValue(request.body(), condition.selector(), warnings);
            }
            case REQUEST_FORM_FIELD -> {
                request = MessageParts.parse(record.renderedRequest());
                yield Optional.ofNullable(parseForm(request.body()).get(condition.selector()));
            }
            case VARIABLE -> Optional.ofNullable(context.variables().get(condition.selector()));
            case RESPONSE_FULL_MESSAGE -> record.receivedResponse() == null || record.receivedResponse().isEmpty()
                    ? Optional.empty() : Optional.of(record.receivedResponse());
            case REQUEST_FULL_MESSAGE -> record.renderedRequest() == null || record.renderedRequest().isEmpty()
                    ? Optional.empty() : Optional.of(record.renderedRequest());
        };
    }

    private boolean compare(
            StepCondition condition,
            Optional<String> actual,
            ExecutionContext context,
            List<String> warnings) {
        StepConditionOperator operator = condition.operator();
        if (operator == StepConditionOperator.EXISTS) {
            return actual.isPresent();
        }
        if (operator == StepConditionOperator.NOT_EXISTS) {
            return actual.isEmpty();
        }
        if (actual.isEmpty()) {
            return switch (operator) {
                case NOT_EQUALS, NOT_CONTAINS -> true;
                default -> false;
            };
        }

        String expected = resolveExpected(condition, context, warnings).orElse(null);
        if (expected == null) {
            return false;
        }
        String actualValue = actual.get();
        return switch (operator) {
            case EQUALS -> normalize(actualValue, condition).equals(normalize(expected, condition));
            case NOT_EQUALS -> !normalize(actualValue, condition).equals(normalize(expected, condition));
            case CONTAINS -> normalize(actualValue, condition).contains(normalize(expected, condition));
            case NOT_CONTAINS -> !normalize(actualValue, condition).contains(normalize(expected, condition));
            case MATCHES_REGEX -> matchesRegex(actualValue, expected, warnings);
            case IN -> matchesIn(actualValue, expected, condition);
            case NUMERIC_GT -> compareNumbers(actualValue, expected, warnings)
                    .map(comparison -> comparison > 0)
                    .orElse(false);
            case NUMERIC_GTE -> compareNumbers(actualValue, expected, warnings)
                    .map(comparison -> comparison >= 0)
                    .orElse(false);
            case NUMERIC_LT -> compareNumbers(actualValue, expected, warnings)
                    .map(comparison -> comparison < 0)
                    .orElse(false);
            case NUMERIC_LTE -> compareNumbers(actualValue, expected, warnings)
                    .map(comparison -> comparison <= 0)
                    .orElse(false);
            case EXISTS, NOT_EXISTS -> throw new IllegalStateException("Existence operators handled earlier");
        };
    }

    private Optional<String> resolveExpected(
            StepCondition condition,
            ExecutionContext context,
            List<String> warnings) {
        if (condition.expectedValueSource() == ConditionValueSource.STATIC) {
            return Optional.of(condition.expectedValue());
        }
        if (condition.expectedValue().isBlank()) {
            warnings.add("Condition '" + condition.name() + "' expects a variable but no variable key is configured");
            return Optional.empty();
        }
        if (!context.variables().containsKey(condition.expectedValue())) {
            warnings.add("Condition '" + condition.name() + "' expected variable not found: " + condition.expectedValue());
            return Optional.empty();
        }
        return Optional.ofNullable(context.variables().get(condition.expectedValue()));
    }

    private Optional<String> jsonValue(String body, String dotPath, List<String> warnings) {
        if (dotPath == null || dotPath.isBlank()) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(body == null ? "" : body);
        } catch (Exception exception) {
            warnings.add("JSON condition could not parse body: " + exception.getMessage());
            return Optional.empty();
        }
        for (String segment : dotPath.split("\\.")) {
            if (segment.isBlank() || node == null || node.isMissingNode() || node.isNull()) {
                return Optional.empty();
            }
            node = node.get(segment.strip());
        }
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            return Optional.of(node.asText());
        }
        return Optional.of(node.toString());
    }

    private Map<String, String> parseForm(String body) {
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

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private boolean matchesRegex(String actual, String pattern, List<String> warnings) {
        try {
            return Pattern.compile(pattern).matcher(actual).find();
        } catch (PatternSyntaxException exception) {
            warnings.add("Invalid condition regex: " + exception.getMessage());
            return false;
        }
    }

    private boolean matchesIn(String actual, String expected, StepCondition condition) {
        String normalizedActual = normalize(actual, condition);
        for (String part : expected.split(",")) {
            if (normalizedActual.equals(normalize(part.strip(), condition))) {
                return true;
            }
        }
        return false;
    }

    private Optional<Integer> compareNumbers(String actual, String expected, List<String> warnings) {
        try {
            return Optional.of(Double.compare(Double.parseDouble(actual.strip()), Double.parseDouble(expected.strip())));
        } catch (NumberFormatException exception) {
            warnings.add("Numeric condition comparison failed: " + exception.getMessage());
            return Optional.empty();
        }
    }

    private String normalize(String value, StepCondition condition) {
        String safe = value == null ? "" : value;
        return condition.caseSensitive() ? safe : safe.toLowerCase(Locale.ROOT);
    }

    private record MessageParts(String firstLine, List<String> headers, String body) {
        private static MessageParts parse(String raw) {
            String safe = raw == null ? "" : raw;
            String normalized = safe.replace("\r\n", "\n");
            int split = normalized.indexOf("\n\n");
            String head = split >= 0 ? normalized.substring(0, split) : normalized;
            String body = split >= 0 ? normalized.substring(split + 2) : "";
            String[] lines = head.split("\n", -1);
            String first = lines.length == 0 ? "" : lines[0];
            List<String> headers = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                if (!lines[i].isBlank()) {
                    headers.add(lines[i]);
                }
            }
            return new MessageParts(first, List.copyOf(headers), body);
        }

        private Optional<String> header(String name) {
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            String prefix = name.strip().toLowerCase(Locale.ROOT) + ":";
            for (String header : headers) {
                if (header.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    return Optional.of(header.substring(header.indexOf(':') + 1).strip());
                }
            }
            return Optional.empty();
        }
    }
}
