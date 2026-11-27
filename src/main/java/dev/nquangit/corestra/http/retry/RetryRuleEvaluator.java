package dev.nquangit.corestra.http.retry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.nquangit.corestra.access.evaluate.JsonPathSelector;
import dev.nquangit.corestra.flow.control.StepConditionOperator;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Evaluates {@link RetryRule}s against a {@link RetryMessage}. Rules are checked in order and the
 * first enabled rule whose conditions match decides. Within a rule, conditions sharing a group are
 * AND-ed and groups are OR-ed. Evaluation never throws for bad input; an unparseable JSON body or a
 * non-numeric value simply does not match.
 */
public final class RetryRuleEvaluator {
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /**
     * Returns the first matching enabled rule.
     *
     * @param rules   ordered rules
     * @param message exchange to test
     * @return matching rule, or empty
     */
    public Optional<RetryRule> firstMatch(List<RetryRule> rules, RetryMessage message) {
        Evaluation evaluation = new Evaluation(message);
        for (RetryRule rule : rules) {
            if (rule.enabled() && matches(rule, evaluation)) return Optional.of(rule);
        }
        return Optional.empty();
    }

    /**
     * Returns whether one rule matches, ignoring its enabled flag.
     *
     * @param rule    rule
     * @param message exchange
     * @return {@code true} when any OR-group has all its conditions matching
     */
    public boolean matches(RetryRule rule, RetryMessage message) {
        return matches(rule, new Evaluation(message));
    }

    /**
     * Returns whether one condition matches.
     *
     * @param condition condition
     * @param message   exchange
     * @return result, including {@link RetryCondition#negate()}
     */
    public boolean matches(RetryCondition condition, RetryMessage message) {
        return matches(condition, new Evaluation(message));
    }

    private boolean matches(RetryRule rule, Evaluation evaluation) {
        for (List<RetryCondition> group : rule.enabledGroups()) {
            boolean all = true;
            for (RetryCondition condition : group) {
                if (!matches(condition, evaluation)) {
                    all = false;
                    break;
                }
            }
            if (all) return true;
        }
        return false;
    }

    private boolean matches(RetryCondition condition, Evaluation evaluation) {
        List<String> actual = evaluation.values(condition);
        boolean result = compareAll(condition, actual);
        return condition.negate() != result;
    }

    private static boolean compareAll(RetryCondition condition, List<String> actual) {
        StepConditionOperator operator = condition.operator();
        return switch (operator) {
            case EXISTS -> !actual.isEmpty();
            case NOT_EXISTS -> actual.isEmpty();
            // A negative comparison holds when no selected value has the positive property.
            case NOT_EQUALS -> actual.stream().noneMatch(value -> compare(condition, StepConditionOperator.EQUALS, value));
            case NOT_CONTAINS -> actual.stream().noneMatch(value -> compare(condition, StepConditionOperator.CONTAINS, value));
            default -> actual.stream().anyMatch(value -> compare(condition, operator, value));
        };
    }

    private static boolean compare(RetryCondition condition, StepConditionOperator operator, String actual) {
        String expected = condition.value();
        return switch (operator) {
            case EQUALS -> fold(actual.strip(), condition).equals(fold(expected.strip(), condition));
            case CONTAINS -> fold(actual, condition).contains(fold(expected, condition));
            case MATCHES_REGEX -> RetryProfile.pattern((condition.caseSensitive() ? "" : "(?i)") + expected)
                    .matcher(actual).find();
            case IN -> {
                String folded = fold(actual.strip(), condition);
                for (String option : expected.split(",")) {
                    if (folded.equals(fold(option.strip(), condition))) yield true;
                }
                yield false;
            }
            case NUMERIC_GT, NUMERIC_GTE, NUMERIC_LT, NUMERIC_LTE -> {
                Optional<Integer> comparison = compareNumbers(actual, expected);
                if (comparison.isEmpty()) yield false;
                int c = comparison.get();
                yield switch (operator) {
                    case NUMERIC_GT -> c > 0;
                    case NUMERIC_GTE -> c >= 0;
                    case NUMERIC_LT -> c < 0;
                    default -> c <= 0;
                };
            }
            default -> false;
        };
    }

    private static Optional<Integer> compareNumbers(String actual, String expected) {
        try {
            return Optional.of(Double.compare(Double.parseDouble(actual.strip()), Double.parseDouble(expected.strip())));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static String fold(String text, RetryCondition condition) {
        return condition.caseSensitive() ? text : text.toLowerCase(Locale.ROOT);
    }

    /** Lazily derived views of one message, shared by all conditions of one evaluation. */
    private static final class Evaluation {
        private final RetryMessage message;
        private JsonNode json;
        private boolean jsonParsed;

        private Evaluation(RetryMessage message) {
            this.message = message;
        }

        List<String> values(RetryCondition condition) {
            return switch (condition.source()) {
                case STATUS -> List.of(Integer.toString(message.status()));
                case RESPONSE_HEADER -> message.responseHeader(condition.selector()).map(List::of).orElse(List.of());
                case RESPONSE_HEADERS -> List.of(message.headerBlock());
                case RESPONSE_BODY -> List.of(message.body());
                case RESPONSE_FULL_MESSAGE -> List.of(message.fullMessage());
                case RESPONSE_JSON_PATH -> json().map(root -> JsonPathSelector.select(root, condition.selector())
                        .stream().map(JsonPathSelector::comparableText).toList()).orElse(List.of());
                case BODY_LENGTH -> List.of(Integer.toString(message.body().length()));
                case REQUEST_METHOD -> List.of(message.requestMethod());
                case REQUEST_PATH -> List.of(message.requestPath());
                case REQUEST_HEADER -> message.requestHeader(condition.selector()).map(List::of).orElse(List.of());
            };
        }

        private Optional<JsonNode> json() {
            if (!jsonParsed) {
                jsonParsed = true;
                try {
                    String body = message.body();
                    json = body.isBlank() ? null : MAPPER.readTree(body);
                } catch (Exception notJson) {
                    json = null;
                }
            }
            return Optional.ofNullable(json);
        }
    }
}
