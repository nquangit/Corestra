package dev.nquangit.corestra.flow.control;

import dev.nquangit.corestra.flow.execution.ExecutionContext;
import dev.nquangit.corestra.flow.execution.StepExecutionRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepConditionEvaluatorTest {

    private final StepConditionEvaluator evaluator = new StepConditionEvaluator();

    @Test
    void matchesStatusHeaderJsonFormAndVariableComparisons() {
        StepExecutionRecord record = record(
                "POST /submit HTTP/1.1\r\nX-Request: visible\r\n\r\nformToken=abc",
                "HTTP/1.1 200 OK\r\nX-Result: wrapped\r\n\r\n{\"data\":{\"ok\":true}}",
                200);
        ExecutionContext context = context(Map.of("expectedHeader", "wrapped", "token", "abc"));
        List<String> warnings = new ArrayList<>();

        assertTrue(evaluator.matches(condition(
                StepConditionSource.RESPONSE_STATUS,
                "",
                StepConditionOperator.EQUALS,
                ConditionValueSource.STATIC,
                "200"), record, context, warnings));
        assertTrue(evaluator.matches(condition(
                StepConditionSource.RESPONSE_HEADER,
                "x-result",
                StepConditionOperator.EQUALS,
                ConditionValueSource.VARIABLE,
                "expectedHeader"), record, context, warnings));
        assertTrue(evaluator.matches(condition(
                StepConditionSource.RESPONSE_JSON_PATH,
                "data.ok",
                StepConditionOperator.EQUALS,
                ConditionValueSource.STATIC,
                "true"), record, context, warnings));
        assertTrue(evaluator.matches(condition(
                StepConditionSource.REQUEST_FORM_FIELD,
                "formToken",
                StepConditionOperator.EQUALS,
                ConditionValueSource.VARIABLE,
                "token"), record, context, warnings));
    }

    @Test
    void invalidRegexDoesNotCrashAndReturnsFalse() {
        StepExecutionRecord record = record(
                "GET / HTTP/1.1\r\n\r\n",
                "HTTP/1.1 200 OK\r\n\r\nhello",
                200);
        List<String> warnings = new ArrayList<>();

        boolean matched = evaluator.matches(condition(
                StepConditionSource.RESPONSE_BODY,
                "",
                StepConditionOperator.MATCHES_REGEX,
                ConditionValueSource.STATIC,
                "["), record, context(Map.of()), warnings);

        assertFalse(matched);
        assertFalse(warnings.isEmpty());
    }

    private static StepCondition condition(
            StepConditionSource source,
            String selector,
            StepConditionOperator operator,
            ConditionValueSource valueSource,
            String expected) {
        return new StepCondition(
                "condition-1",
                "Condition",
                true,
                source,
                selector,
                operator,
                valueSource,
                expected,
                false,
                false);
    }

    private static ExecutionContext context(Map<String, String> variables) {
        ExecutionContext context = ExecutionContext.start("run-1", "flow-1", Map.of(), 1_000L);
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            context = context.withVariable(entry.getKey(), entry.getValue());
        }
        return context;
    }

    private static StepExecutionRecord record(String request, String response, int status) {
        return new StepExecutionRecord(
                "step-1",
                request,
                response,
                status,
                true,
                "",
                1_000L,
                1_001L,
                Map.of());
    }
}
