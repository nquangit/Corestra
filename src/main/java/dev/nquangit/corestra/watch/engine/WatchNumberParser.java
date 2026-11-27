package dev.nquangit.corestra.watch.engine;

import dev.nquangit.corestra.watch.WatchNumberFormat;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Normalizes textual numbers (usually money) to exact {@link BigDecimal}s; never uses
 * {@code double} and never throws: unparseable input yields {@code null}.
 *
 * <p>Steps: scientific literals such as {@code 1.0E7} are parsed directly; a value wrapped in
 * parentheses ({@code (1,000)}) is negative; {@code stripPattern} is removed; whitespace
 * (including non-breaking spaces) is removed; one leading {@code +}/{@code -} or one trailing
 * {@code -} sets the sign; separators are normalized; {@code minorUnitScale} divides by 10^n.</p>
 *
 * <p><b>Automatic separator heuristic</b> (when {@link WatchNumberFormat#auto()} is true):</p>
 * <ul>
 *   <li>both {@code .} and {@code ,} occur: the one occurring last is the decimal separator and
 *       must occur once; the other is the group separator;</li>
 *   <li>only one kind occurs, more than once: it is a group separator ({@code 1.234.567});</li>
 *   <li>only one kind occurs, once: it is a group separator when followed by exactly three
 *       digits and preceded by a non-zero integer part ({@code 1,234}, {@code 1.234} → 1234);
 *       otherwise it is the decimal separator ({@code 1.5}, {@code 12,34}, {@code 0.500}).</li>
 * </ul>
 * <p>{@link #parseMachineFirst} first accepts canonical machine literals ({@code 10.125}),
 * which is the right choice for JSON values and request variables.</p>
 */
public final class WatchNumberParser {
    private static final Pattern SCIENTIFIC = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)[eE][+-]?\\d+");
    private static final Pattern MACHINE = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");
    private static final Pattern PLAIN = Pattern.compile("\\d+(\\.\\d*)?|\\.\\d+");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0\\u2007\\u202F]+");
    private static final Pattern NOT_NUMERIC_OR_PAREN = Pattern.compile("[^0-9().,\\-]");

    /**
     * Parses {@code text} with {@code format} (automatic when null).
     *
     * @param text   text to parse
     * @param format number format, or null for {@link WatchNumberFormat#automatic()}
     * @return exact value, or null when the text is not a number
     */
    public BigDecimal parse(String text, WatchNumberFormat format) {
        return safeParse(text, format, false);
    }

    /**
     * Like {@link #parse} but, in automatic mode, first accepts a canonical machine literal
     * ({@code -12.345}, {@code 1e3}); so {@code "1.234"} is 1.234 here but 1234 in {@link #parse}.
     *
     * @param text   text to parse
     * @param format number format, or null for automatic
     * @return exact value, or null when the text is not a number
     */
    public BigDecimal parseMachineFirst(String text, WatchNumberFormat format) {
        return safeParse(text, format, true);
    }

    private BigDecimal safeParse(String text, WatchNumberFormat format, boolean machineFirst) {
        try {
            return parseOrNull(text, format == null ? WatchNumberFormat.automatic() : format, machineFirst);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private BigDecimal parseOrNull(String text, WatchNumberFormat format, boolean machineFirst) {
        if (text == null) return null;
        String s = text.strip().replace('−', '-');
        if (s.isEmpty()) return null;
        if (SCIENTIFIC.matcher(s).matches() || (machineFirst && format.auto() && MACHINE.matcher(s).matches())) {
            return scale(new BigDecimal(s.startsWith("+") ? s.substring(1) : s), format);
        }
        boolean negative = false;
        String shape = NOT_NUMERIC_OR_PAREN.matcher(s).replaceAll("");
        if (shape.length() > 2 && shape.startsWith("(") && shape.endsWith(")")) {
            negative = true;
            s = s.replace("(", "").replace(")", "");
        }
        if (!format.stripPattern().isEmpty()) s = s.replaceAll(format.stripPattern(), "");
        s = WHITESPACE.matcher(s).replaceAll("");
        if (s.startsWith("+")) {
            s = s.substring(1);
        } else if (s.startsWith("-")) {
            negative = true;
            s = s.substring(1);
        } else if (s.endsWith("-")) {
            negative = true;
            s = s.substring(0, s.length() - 1);
        }
        String normalized = format.auto() ? normalizeAuto(s) : normalizeManual(s, format);
        if (normalized == null || !PLAIN.matcher(normalized).matches()) return null;
        BigDecimal value = new BigDecimal(normalized);
        return scale(negative ? value.negate() : value, format);
    }

    private static String normalizeAuto(String s) {
        int lastDot = s.lastIndexOf('.');
        int lastComma = s.lastIndexOf(',');
        if (lastDot >= 0 && lastComma >= 0) {
            char decimal = lastDot > lastComma ? '.' : ',';
            char group = decimal == '.' ? ',' : '.';
            if (count(s, decimal) != 1) return null;
            return s.replace(String.valueOf(group), "").replace(decimal, '.');
        }
        if (lastDot < 0 && lastComma < 0) return s;
        char separator = lastDot >= 0 ? '.' : ',';
        int index = Math.max(lastDot, lastComma);
        if (count(s, separator) > 1) return s.replace(String.valueOf(separator), "");
        String integerPart = s.substring(0, index);
        String fraction = s.substring(index + 1);
        boolean group = fraction.length() == 3 && fraction.chars().allMatch(Character::isDigit)
                && !integerPart.isEmpty() && !integerPart.chars().allMatch(c -> c == '0');
        return group ? s.replace(String.valueOf(separator), "") : s.replace(separator, '.');
    }

    private static String normalizeManual(String s, WatchNumberFormat format) {
        String decimal = format.decimalSeparator();
        String group = format.groupSeparator();
        if (!group.isEmpty() && !group.equals(decimal)) s = s.replace(group, "");
        if (!decimal.equals(".")) {
            if (s.contains(".")) return null;
            s = s.replace(decimal, ".");
        }
        return s;
    }

    private static BigDecimal scale(BigDecimal value, WatchNumberFormat format) {
        return format.minorUnitScale() > 0 ? value.movePointLeft(format.minorUnitScale()) : value;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
