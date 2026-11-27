package dev.nquangit.corestra.watch.engine;

import dev.nquangit.corestra.watch.WatchNumberFormat;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic arithmetic over exact decimals for Watch expectations (no scripting engine).
 *
 * <p>Grammar:</p>
 * <pre>
 * expr    := term (('+' | '-') term)*
 * term    := unary (('*' | '/') unary)*
 * unary   := ('-' | '+') unary | primary
 * primary := NUMBER | VARIABLE | FUNC '(' expr (',' expr)* ')' | '(' expr ')'
 * FUNC    := min(a,b) | max(a,b) | abs(a) | round(a,scale)   (round is HALF_UP)
 * VARIABLE:= '{{' name '}}' | [A-Za-z_][A-Za-z0-9_.]*
 * </pre>
 * <p>Number literals use {@code .} as the decimal point. Division uses
 * {@link MathContext#DECIMAL64}. Numeric bindings (e.g. {@code before}/{@code after}) win over
 * text variables, which are parsed with {@link WatchNumberParser#parseMachineFirst} in automatic
 * mode. Errors never escape: they are reported in {@link Result#error()}.</p>
 */
public final class WatchExpression {
    private static final int MAX_LENGTH = 4_000;
    private static final int MAX_DEPTH = 100;
    private static final WatchNumberParser PARSER = new WatchNumberParser();

    private WatchExpression() {
    }

    /**
     * Evaluation outcome.
     *
     * @param value       result, or null on error
     * @param substituted expression text with every resolvable variable replaced by its value
     * @param error       error message, or null on success
     */
    public record Result(BigDecimal value, String substituted, String error) {
        /**
         * Returns whether evaluation succeeded.
         *
         * @return true when {@link #value()} is set
         */
        public boolean ok() {
            return error == null;
        }

        /**
         * Returns e.g. {@code "-500000 - max(500000 * 0.011, 5000) = -505500"}, or the substituted
         * text followed by the error.
         *
         * @return human-readable explanation
         */
        public String explanation() {
            return ok() ? substituted + " = " + format(value) : substituted + " (" + error + ")";
        }
    }

    /**
     * Evaluates with text variables only.
     *
     * @param expression expression text
     * @param variables  variable name to text value (may be null)
     * @return result
     */
    public static Result evaluate(String expression, Map<String, String> variables) {
        return evaluate(expression, variables, Map.of());
    }

    /**
     * Evaluates with text variables and exact numeric bindings (which take precedence).
     *
     * @param expression expression text
     * @param variables  variable name to text value (may be null)
     * @param numbers    variable name to exact value (may be null; null values are ignored)
     * @return result
     */
    public static Result evaluate(String expression, Map<String, String> variables, Map<String, BigDecimal> numbers) {
        String source = expression == null ? "" : expression.strip();
        if (source.isEmpty()) return new Result(null, "", "Expression is empty");
        if (source.length() > MAX_LENGTH) return new Result(null, "", "Expression is too long");
        List<Token> tokens;
        try {
            tokens = tokenize(source);
        } catch (ExpressionException invalid) {
            return new Result(null, source, invalid.getMessage());
        }
        Map<Token, BigDecimal> resolved = new HashMap<>();
        String firstError = null;
        for (Token token : tokens) {
            if (token.kind != Kind.VARIABLE) continue;
            BigDecimal value = numbers == null ? null : numbers.get(token.text);
            if (value == null) {
                String text = variables == null ? null : variables.get(token.text);
                if (text == null) {
                    if (firstError == null) firstError = "Unknown variable '" + token.text + "'";
                    continue;
                }
                value = PARSER.parseMachineFirst(text, WatchNumberFormat.automatic());
                if (value == null) {
                    if (firstError == null) firstError = "Variable '" + token.text + "' is not a number: '" + text + "'";
                    continue;
                }
            }
            resolved.put(token, value);
        }
        String substituted = substitute(source, tokens, resolved);
        if (firstError != null) return new Result(null, substituted, firstError);
        try {
            Evaluator evaluator = new Evaluator(tokens, resolved);
            BigDecimal value = evaluator.parseAll();
            return new Result(value, substituted, null);
        } catch (ExpressionException | ArithmeticException invalid) {
            return new Result(null, substituted, invalid.getMessage());
        }
    }

    /**
     * Formats a decimal without exponent and without trailing zeros.
     *
     * @param value value (may be null)
     * @return plain text, or "" for null
     */
    public static String format(BigDecimal value) {
        if (value == null) return "";
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static String substitute(String source, List<Token> tokens, Map<Token, BigDecimal> resolved) {
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (Token token : tokens) {
            BigDecimal value = resolved.get(token);
            if (value == null) continue;
            out.append(source, cursor, token.start);
            String text = format(value);
            out.append(value.signum() < 0 ? "(" + text + ")" : text);
            cursor = token.end;
        }
        return out.append(source.substring(cursor)).toString();
    }

    private enum Kind { NUMBER, VARIABLE, IDENT, OP, END }

    /** Identity-compared token so equal names at different positions resolve separately. */
    private static final class Token {
        final Kind kind;
        final String text;
        final int start;
        final int end;

        Token(Kind kind, String text, int start, int end) {
            this.kind = kind;
            this.text = text;
            this.start = start;
            this.end = end;
        }
    }

    private static List<Token> tokenize(String s) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) {
                int start = i;
                boolean dot = false;
                while (i < s.length() && (Character.isDigit(s.charAt(i)) || (s.charAt(i) == '.' && !dot))) {
                    if (s.charAt(i) == '.') dot = true;
                    i++;
                }
                tokens.add(new Token(Kind.NUMBER, s.substring(start, i), start, i));
            } else if (s.startsWith("{{", i)) {
                int close = s.indexOf("}}", i + 2);
                if (close < 0) throw new ExpressionException("Unclosed '{{' at position " + (i + 1));
                String name = s.substring(i + 2, close).strip();
                if (name.isEmpty()) throw new ExpressionException("Empty variable at position " + (i + 1));
                tokens.add(new Token(Kind.VARIABLE, name, i, close + 2));
                i = close + 2;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '.')) i++;
                int next = i;
                while (next < s.length() && Character.isWhitespace(s.charAt(next))) next++;
                boolean call = next < s.length() && s.charAt(next) == '(';
                tokens.add(new Token(call ? Kind.IDENT : Kind.VARIABLE, s.substring(start, i), start, i));
            } else if ("+-*/(),".indexOf(c) >= 0) {
                tokens.add(new Token(Kind.OP, String.valueOf(c), i, i + 1));
                i++;
            } else {
                throw new ExpressionException("Unexpected character '" + c + "' at position " + (i + 1));
            }
        }
        tokens.add(new Token(Kind.END, "", s.length(), s.length()));
        return tokens;
    }

    private static final class Evaluator {
        private final List<Token> tokens;
        private final Map<Token, BigDecimal> values;
        private int position;
        private int depth;

        Evaluator(List<Token> tokens, Map<Token, BigDecimal> values) {
            this.tokens = tokens;
            this.values = values;
        }

        BigDecimal parseAll() {
            BigDecimal value = expression();
            Token rest = peek();
            if (rest.kind != Kind.END) throw new ExpressionException("Unexpected '" + rest.text + "' at position " + (rest.start + 1));
            return value;
        }

        private BigDecimal expression() {
            if (++depth > MAX_DEPTH) throw new ExpressionException("Expression is nested too deeply");
            BigDecimal value = term();
            while (isOp("+") || isOp("-")) {
                String op = next().text;
                BigDecimal right = term();
                value = op.equals("+") ? value.add(right) : value.subtract(right);
            }
            depth--;
            return value;
        }

        private BigDecimal term() {
            BigDecimal value = unary();
            while (isOp("*") || isOp("/")) {
                String op = next().text;
                BigDecimal right = unary();
                if (op.equals("*")) {
                    value = value.multiply(right);
                } else {
                    if (right.signum() == 0) throw new ExpressionException("Division by zero");
                    value = value.divide(right, MathContext.DECIMAL64);
                }
            }
            return value;
        }

        private BigDecimal unary() {
            if (isOp("-") || isOp("+")) {
                boolean minus = next().text.equals("-");
                if (++depth > MAX_DEPTH) throw new ExpressionException("Expression is nested too deeply");
                BigDecimal value = unary();
                depth--;
                return minus ? value.negate() : value;
            }
            return primary();
        }

        private BigDecimal primary() {
            Token token = next();
            switch (token.kind) {
                case NUMBER:
                    return new BigDecimal(token.text.endsWith(".") ? token.text + "0" : token.text);
                case VARIABLE:
                    return values.get(token);
                case IDENT:
                    return call(token);
                case OP:
                    if (token.text.equals("(")) {
                        BigDecimal value = expression();
                        expect(")");
                        return value;
                    }
                    throw new ExpressionException("Unexpected '" + token.text + "' at position " + (token.start + 1));
                default:
                    throw new ExpressionException("Unexpected end of expression");
            }
        }

        private BigDecimal call(Token name) {
            expect("(");
            List<BigDecimal> args = new ArrayList<>();
            args.add(expression());
            while (isOp(",")) {
                next();
                args.add(expression());
            }
            expect(")");
            String function = name.text.toLowerCase(java.util.Locale.ROOT);
            switch (function) {
                case "min":
                    arity(name, args, 2);
                    return args.get(0).min(args.get(1));
                case "max":
                    arity(name, args, 2);
                    return args.get(0).max(args.get(1));
                case "abs":
                    arity(name, args, 1);
                    return args.get(0).abs();
                case "round":
                    arity(name, args, 2);
                    BigDecimal scale = args.get(1);
                    if (scale.stripTrailingZeros().scale() > 0) throw new ExpressionException("round() scale must be an integer");
                    int digits = scale.intValueExact();
                    if (digits < -20 || digits > 20) throw new ExpressionException("round() scale must be between -20 and 20");
                    return args.get(0).setScale(digits, RoundingMode.HALF_UP);
                default:
                    throw new ExpressionException("Unknown function '" + name.text + "'");
            }
        }

        private static void arity(Token name, List<BigDecimal> args, int expected) {
            if (args.size() != expected) {
                throw new ExpressionException(name.text + "() takes " + expected + " argument" + (expected == 1 ? "" : "s"));
            }
        }

        private void expect(String op) {
            Token token = next();
            if (token.kind != Kind.OP || !token.text.equals(op)) {
                throw new ExpressionException(token.kind == Kind.END ? "Expected '" + op + "' before end of expression"
                        : "Expected '" + op + "' at position " + (token.start + 1));
            }
        }

        private boolean isOp(String op) {
            Token token = peek();
            return token.kind == Kind.OP && token.text.equals(op);
        }

        private Token peek() {
            return tokens.get(position);
        }

        private Token next() {
            Token token = tokens.get(position);
            if (token.kind != Kind.END) position++;
            return token;
        }
    }

    private static final class ExpressionException extends RuntimeException {
        ExpressionException(String message) {
            super(message, null, false, false);
        }
    }
}
