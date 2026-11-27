package dev.nquangit.corestra.watch;

import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.http.extract.JsonBodyExtractor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Checks a {@link WatchDefinition} before it is saved: regex and JSON selector syntax, required
 * fields per observation source and expectation type, variable names, and a lightweight syntax
 * check of expectation expressions (balanced parentheses, well-formed {@code {{name}}}, allowed
 * characters). Expressions are fully parsed only by the engine at run time.
 *
 * <p>A disabled watch may be an incomplete draft: missing probe/observations are only errors for
 * enabled watches.</p>
 */
public final class WatchDefinitionValidator {
    private static final Pattern METHOD = Pattern.compile("[A-Z][A-Z0-9_-]*");
    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}]*?)\\s*}}");
    private static final Pattern EXPRESSION_CHARS = Pattern.compile("[0-9A-Za-z_.,+\\-*/()\\s]*");

    private WatchDefinitionValidator() {
    }

    /**
     * Validation outcome.
     *
     * @param errors   problems that must be fixed before saving
     * @param warnings advisory messages (for example unknown {@code {{variables}}})
     */
    public record Result(List<String> errors, List<String> warnings) {
        /**
         * Copies lists.
         *
         * @param errors   errors
         * @param warnings warnings
         */
        public Result {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }

        /**
         * Returns whether there are no errors.
         *
         * @return true when valid
         */
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    /**
     * Validates a definition.
     *
     * @param watch definition
     * @return errors and warnings
     */
    public static Result validate(WatchDefinition watch) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        scope(watch.scope(), errors);
        probe(watch, errors);
        Set<String> variables = variables(watch.requestVariables(), errors);
        observations(watch, errors);
        expectations(watch.expectations(), variables, errors, warnings);
        return new Result(errors, warnings);
    }

    private static void scope(WatchScope scope, List<String> errors) {
        for (String method : scope.methods()) {
            if (!METHOD.matcher(method).matches()) {
                errors.add("Scope: invalid HTTP method \"" + method + "\".");
            }
        }
        regex("Scope: Host regex", scope.hostPattern(), errors);
        regex("Scope: Path regex", scope.pathPattern(), errors);
    }

    private static void probe(WatchDefinition watch, List<String> errors) {
        if (watch.enabled() && !watch.probe().configured()) {
            errors.add("Probe: an enabled watch needs a probe request.");
        }
        for (String header : watch.probe().inheritHeaders()) {
            if (!HEADER_NAME.matcher(header).matches()) {
                errors.add("Probe: invalid inherited header name \"" + header + "\".");
            }
        }
        if (watch.enabled() && watch.observations().isEmpty()) {
            errors.add("Observations: an enabled watch needs at least one observation.");
        }
    }

    private static Set<String> variables(List<ExtractRule> rules, List<String> errors) {
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            ExtractRule rule = rules.get(i);
            String label = "Request variable #" + (i + 1) + " (" + rule.targetVariableName() + ")";
            if (rule.sourceSide() != ExtractRule.SourceSide.REQUEST) {
                errors.add(label + ": must read the REQUEST side.");
            }
            if (!VARIABLE_NAME.matcher(rule.targetVariableName()).matches()) {
                errors.add(label + ": invalid variable name (letters, digits, _ . - ; not starting with a digit).");
            }
            if (!names.add(rule.targetVariableName())) {
                errors.add("Duplicate request variable \"" + rule.targetVariableName() + "\".");
            }
            switch (rule.extractorType()) {
                case REGEX -> regex(label + ": regex", rule.sourceSelector(), errors);
                case JSON_PATH -> JsonBodyExtractor.validateSelector(rule.sourceSelector())
                        .ifPresent(message -> errors.add(label + ": " + message));
                default -> {
                }
            }
        }
        return names;
    }

    private static void observations(WatchDefinition watch, List<String> errors) {
        Set<String> names = new HashSet<>();
        List<WatchObservation> observations = watch.observations();
        for (int i = 0; i < observations.size(); i++) {
            WatchObservation observation = observations.get(i);
            String label = "Observation #" + (i + 1) + " (" + observation.name() + ")";
            if (observation.name().isBlank()) {
                errors.add(label + ": name is required.");
            } else if (observation.name().contains("[") || observation.name().contains("]")) {
                errors.add(label + ": name must not contain brackets (they are added for collection keys).");
            } else if (!names.add(observation.name())) {
                errors.add("Duplicate observation name \"" + observation.name() + "\".");
            }
            switch (observation.source()) {
                case JSON_SELECTOR -> {
                    if (observation.selector().isBlank()) {
                        errors.add(label + ": JSON selector is required.");
                    } else {
                        JsonBodyExtractor.validateSelector(observation.selector())
                                .ifPresent(message -> errors.add(label + ": " + message));
                    }
                }
                case JSON_COLLECTION -> {
                    if (observation.itemsPath().isBlank()) {
                        errors.add(label + ": items path (JSONPath to the array) is required.");
                    } else {
                        JsonBodyExtractor.validateSelector(observation.itemsPath())
                                .ifPresent(message -> errors.add(label + ": items path: " + message));
                    }
                    if (observation.keyPath().isBlank()) {
                        errors.add(label + ": key path is required.");
                    }
                    if (observation.valuePath().isBlank()) {
                        errors.add(label + ": value path is required.");
                    }
                }
                case REGEX -> {
                    if (observation.selector().isBlank()) {
                        errors.add(label + ": regex is required.");
                    } else {
                        regex(label + ": regex", observation.selector(), errors);
                    }
                }
                case REGEX_COLLECTION -> {
                    if (observation.selector().isBlank()) {
                        errors.add(label + ": regex is required.");
                    } else if (regex(label + ": regex", observation.selector(), errors)
                            && !(observation.selector().contains("(?<key>") && observation.selector().contains("(?<value>"))) {
                        errors.add(label + ": regex collection needs named groups (?<key>...) and (?<value>...).");
                    }
                }
            }
            regex(label + ": Strip regex", observation.numberFormat().stripPattern(), errors);
        }
    }

    private static void expectations(List<WatchExpectation> expectations, Set<String> variables,
                                     List<String> errors, List<String> warnings) {
        for (int i = 0; i < expectations.size(); i++) {
            WatchExpectation expectation = expectations.get(i);
            if (!expectation.enabled()) {
                continue;
            }
            String label = "Expectation #" + (i + 1);
            if (expectation.keyPattern().isBlank()) {
                errors.add(label + ": key pattern is required (e.g. balance[*]).");
            }
            boolean needsLower = switch (expectation.type()) {
                case DELTA_EQUALS, DELTA_BETWEEN, AFTER_AT_LEAST -> true;
                case CHANGED, UNCHANGED -> false;
            };
            if (needsLower && expectation.expression().isBlank()) {
                errors.add(label + ": " + expectation.type() + " needs an expression.");
            } else if (needsLower) {
                expression(label + ": expression", expectation.expression(), variables, errors, warnings);
            }
            if (expectation.type() == WatchExpectation.Type.DELTA_BETWEEN) {
                if (expectation.expression2().isBlank()) {
                    errors.add(label + ": DELTA_BETWEEN needs an upper bound (expression 2).");
                } else {
                    expression(label + ": expression 2", expectation.expression2(), variables, errors, warnings);
                }
            }
            if (!expectation.tolerance().isBlank()) {
                try {
                    if (new BigDecimal(expectation.tolerance()).signum() < 0) {
                        errors.add(label + ": Tolerance must not be negative.");
                    }
                } catch (NumberFormatException exception) {
                    errors.add(label + ": Tolerance must be a decimal number.");
                }
            }
        }
    }

    /**
     * Performs the lightweight expression syntax check used on save.
     *
     * @param expression expression text
     * @return first problem, or null when it looks well-formed
     */
    public static String expressionProblem(String expression) {
        List<String> errors = new ArrayList<>();
        expression("expression", expression, null, errors, new ArrayList<>());
        return errors.isEmpty() ? null : errors.getFirst();
    }

    private static void expression(String label, String expression, Set<String> variables,
                                   List<String> errors, List<String> warnings) {
        int open = expression.indexOf("{{");
        int close = expression.indexOf("}}");
        Matcher matcher = PLACEHOLDER.matcher(expression);
        StringBuilder stripped = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            stripped.append(expression, last, matcher.start()).append(" v ");
            String name = matcher.group(1);
            if (name.isEmpty()) {
                errors.add(label + ": empty {{}} placeholder.");
                return;
            }
            if (variables != null && !variables.contains(name)) {
                warnings.add(label + ": {{" + name + "}} is not a request variable of this watch.");
            }
            last = matcher.end();
        }
        stripped.append(expression.substring(last));
        String rest = stripped.toString();
        if (open >= 0 && close < 0 || rest.contains("{") || rest.contains("}")) {
            errors.add(label + ": malformed {{variable}} placeholder.");
            return;
        }
        if (!EXPRESSION_CHARS.matcher(rest).matches()) {
            errors.add(label + ": unsupported characters (allowed: numbers, + - * / ( ) , names, {{variables}}).");
            return;
        }
        int depth = 0;
        for (char c : rest.toCharArray()) {
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth < 0) {
                break;
            }
        }
        if (depth != 0) {
            errors.add(label + ": unbalanced parentheses.");
        }
    }

    private static boolean regex(String label, String pattern, List<String> errors) {
        if (pattern == null || pattern.isEmpty()) {
            return true;
        }
        try {
            Pattern.compile(pattern);
            return true;
        } catch (PatternSyntaxException exception) {
            errors.add(label + " is invalid: " + exception.getDescription() + ".");
            return false;
        }
    }
}
