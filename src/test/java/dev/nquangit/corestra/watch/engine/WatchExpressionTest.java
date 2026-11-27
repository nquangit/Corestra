package dev.nquangit.corestra.watch.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchExpressionTest {

    private static BigDecimal eval(String expression) {
        WatchExpression.Result result = WatchExpression.evaluate(expression, Map.of());
        assertTrue(result.ok(), () -> "unexpected error: " + result.error());
        return result.value();
    }

    private static void assertValue(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void arithmeticPrecedenceAndParentheses() {
        assertValue("7", eval("1 + 2 * 3"));
        assertValue("9", eval("(1 + 2) * 3"));
        assertValue("-1", eval("2 - 3"));
        assertValue("4", eval("10 - 3 - 3"));
        assertValue("5", eval("--5"));
        assertValue("-6", eval("-(2 * 3)"));
        assertValue("0.3", eval("0.1 + 0.2"));
        assertValue("2.5", eval("5 / 2"));
        assertValue("0.3333333333333333", eval("1 / 3"));
        assertValue("2", eval("+2"));
        assertValue("0.5", eval(".5"));
    }

    @Test
    void functions() {
        assertValue("1", eval("min(1, 2)"));
        assertValue("2", eval("max(1, 2)"));
        assertValue("3.5", eval("abs(-3.5)"));
        assertValue("2.35", eval("round(2.345, 2)"));
        assertValue("-2.35", eval("round(-2.345, 2)"));
        assertValue("1200", eval("round(1234, -2)"));
        assertValue("5500", eval("MAX(500000 * 0.011, 5000)"));
    }

    @Test
    void bankFeeExampleWithExplanation() {
        WatchExpression.Result result = WatchExpression.evaluate(
                "-{{amount}} - max({{amount}} * 0.011, 5000)", Map.of("amount", "500000"));
        assertTrue(result.ok());
        assertValue("-505500", result.value());
        assertEquals("-500000 - max(500000 * 0.011, 5000) = -505500", result.explanation());
    }

    @Test
    void bareIdentifiersAndNumericBindings() {
        WatchExpression.Result result = WatchExpression.evaluate("after - before - fee.flat",
                Map.of("fee.flat", "1,000", "before", "999"),
                Map.of("before", new BigDecimal("10.125"), "after", new BigDecimal("20")));
        assertTrue(result.ok(), result::error);
        assertValue("-990.125", result.value());
        assertEquals("20 - 10.125 - 1000 = -990.125", result.explanation());
    }

    @Test
    void negativeSubstitutionsAreParenthesized() {
        WatchExpression.Result result = WatchExpression.evaluate("-x", Map.of("x", "-5"));
        assertValue("5", result.value());
        assertEquals("-(-5) = 5", result.explanation());
    }

    @Test
    void textVariablesUseMachineFirstParsing() {
        assertValue("10.125", WatchExpression.evaluate("{{a}}", Map.of("a", "10.125")).value());
        assertValue("1500000", WatchExpression.evaluate("{{a}}", Map.of("a", "1.500.000 VND")).value());
    }

    @Test
    void errorsAreReportedNotThrown() {
        assertError("1 +", "end");
        assertError("(1 + 2", "')'");
        assertError("1 / 0", "Division by zero");
        assertError("1 / (2 - 2)", "Division by zero");
        assertError("{{missing}} + 1", "Unknown variable 'missing'");
        assertError("foo(1)", "Unknown function");
        assertError("min(1)", "takes 2 arguments");
        assertError("round(1.5, 0.5)", "integer");
        assertError("1 $ 2", "Unexpected character '$'");
        assertError("{{open", "Unclosed");
        assertError("{{  }}", "Empty variable");
        assertError("", "empty");
        assertError("1 2", "Unexpected '2'");
        assertError("x", "not a number");
        assertNull(WatchExpression.evaluate(null, null).value());
        assertError("(".repeat(500) + "1" + ")".repeat(500), "nested too deeply");
    }

    @Test
    void errorExplanationKeepsSubstitutedText() {
        WatchExpression.Result result = WatchExpression.evaluate("{{a}} + {{b}}", Map.of("a", "5"));
        assertFalse(result.ok());
        assertEquals("5 + {{b}}", result.substituted());
        assertTrue(result.explanation().contains("Unknown variable 'b'"));
    }

    @Test
    void formatStripsTrailingZeros() {
        assertEquals("1200", WatchExpression.format(new BigDecimal("1.2E+3")));
        assertEquals("0", WatchExpression.format(new BigDecimal("0.000")));
        assertEquals("1.5", WatchExpression.format(new BigDecimal("1.500")));
        assertEquals("", WatchExpression.format(null));
    }

    private static void assertError(String expression, String fragment) {
        WatchExpression.Result result = WatchExpression.evaluate(expression, Map.of("x", "abc"));
        assertFalse(result.ok(), () -> "expected error for " + expression);
        assertNull(result.value());
        assertTrue(result.error().contains(fragment), () -> "error '" + result.error() + "' lacks '" + fragment + "'");
    }
}
