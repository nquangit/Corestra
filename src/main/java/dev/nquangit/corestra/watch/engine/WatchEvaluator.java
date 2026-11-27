package dev.nquangit.corestra.watch.engine;

import dev.nquangit.corestra.watch.WatchDefinition;
import dev.nquangit.corestra.watch.WatchExpectation;
import dev.nquangit.corestra.watch.WatchResult;
import dev.nquangit.corestra.watch.WatchResult.Row;
import dev.nquangit.corestra.watch.WatchResult.Status;
import dev.nquangit.corestra.watch.WatchResult.Verdict;
import dev.nquangit.corestra.watch.WatchSnapshot;
import dev.nquangit.corestra.watch.WatchSnapshot.Value;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compares two snapshots and checks expectations; pure and deterministic.
 *
 * <p>Rows: every key of BEFORE in order, then keys only present AFTER. A row is changed when
 * presence differs, numbers differ ({@code compareTo}), or (non-numeric) raw text differs.</p>
 *
 * <p>Each enabled expectation is checked for every key its glob matches, with {@code before} and
 * {@code after} bound to that key's numbers. A row's verdict is the worst of its checks
 * (ERROR &gt; FAIL &gt; OK) or INFO when none apply. <b>An enabled expectation that matches no key
 * adds a note and makes the overall status FAIL</b>: a watched account that disappears (or a
 * mistyped pattern) must not pass silently.</p>
 *
 * <p>Overall status: ERROR when BEFORE or AFTER failed entirely or any row is ERROR; else FAIL
 * when any row failed or an expectation matched nothing; else PASS when an expectation was
 * checked; else CHANGED or NO_CHANGE.</p>
 */
public final class WatchEvaluator {

    /**
     * Evaluates {@code before} against {@code after}.
     *
     * @param definition   watch
     * @param variables    request variables (may be null)
     * @param before       BEFORE snapshot
     * @param after        AFTER snapshot
     * @param settled      whether AFTER was stable
     * @param settleMillis settle duration
     * @param notes        notes to carry (may be null)
     * @return result
     */
    public WatchResult evaluate(WatchDefinition definition, Map<String, String> variables, WatchSnapshot before,
                                WatchSnapshot after, boolean settled, long settleMillis, List<String> notes) {
        List<String> allNotes = new ArrayList<>(notes == null ? List.of() : notes);
        WatchSnapshot safeBefore = before == null ? WatchSnapshot.failed(0, 0, "BEFORE was not captured") : before;
        WatchSnapshot safeAfter = after == null ? WatchSnapshot.failed(0, 0, "AFTER was not captured") : after;
        safeBefore.errors().forEach(e -> allNotes.add("BEFORE: " + e));
        safeAfter.errors().forEach(e -> allNotes.add("AFTER: " + e));
        boolean captureFailed = safeBefore.failed() || safeAfter.failed();

        Set<String> keys = new LinkedHashSet<>(safeBefore.values().keySet());
        keys.addAll(safeAfter.values().keySet());
        Map<String, List<Check>> checks = new HashMap<>();
        boolean anyMatched = false;
        boolean unmatched = false;
        if (!captureFailed) {
            for (WatchExpectation expectation : definition.expectations()) {
                if (expectation == null || !expectation.enabled()) continue;
                boolean matched = false;
                for (String key : keys) {
                    if (!WatchKeyGlob.matches(expectation.keyPattern(), key)) continue;
                    matched = true;
                    checks.computeIfAbsent(key, k -> new ArrayList<>()).add(
                            check(expectation, variables, safeBefore.values().get(key), safeAfter.values().get(key)));
                }
                if (matched) {
                    anyMatched = true;
                } else {
                    unmatched = true;
                    allNotes.add("Expectation " + expectation.type() + " '" + expectation.keyPattern()
                            + "' matched no observed key");
                }
            }
        }

        List<Row> rows = new ArrayList<>();
        boolean anyChanged = false;
        for (String key : keys) {
            Value b = safeBefore.values().get(key);
            Value a = safeAfter.values().get(key);
            boolean changed = changed(b, a);
            anyChanged |= changed;
            BigDecimal delta = b != null && a != null && b.number() != null && a.number() != null
                    ? a.number().subtract(b.number()) : null;
            List<Check> keyChecks = checks.getOrDefault(key, List.of());
            Verdict verdict = keyChecks.isEmpty() ? Verdict.INFO : worst(keyChecks);
            String explanation = String.join("; ", keyChecks.stream().map(Check::explanation).toList());
            rows.add(new Row(key, b == null ? null : b.raw(), a == null ? null : a.raw(), delta, changed, verdict,
                    explanation));
        }

        Status status;
        if (captureFailed || rows.stream().anyMatch(r -> r.verdict() == Verdict.ERROR)) status = Status.ERROR;
        else if (unmatched || rows.stream().anyMatch(r -> r.verdict() == Verdict.FAIL)) status = Status.FAIL;
        else if (anyMatched) status = Status.PASS;
        else status = anyChanged ? Status.CHANGED : Status.NO_CHANGE;
        return new WatchResult(definition.id(), definition.name(), status, rows, settled, settleMillis,
                safeBefore, safeAfter, allNotes);
    }

    private record Check(Verdict verdict, String explanation) {
    }

    private static Verdict worst(List<Check> checks) {
        if (checks.stream().anyMatch(c -> c.verdict() == Verdict.ERROR)) return Verdict.ERROR;
        if (checks.stream().anyMatch(c -> c.verdict() == Verdict.FAIL)) return Verdict.FAIL;
        return Verdict.OK;
    }

    private static boolean changed(Value before, Value after) {
        if (before == null || after == null) return before != after;
        if (before.number() != null && after.number() != null) return before.number().compareTo(after.number()) != 0;
        return !Objects.equals(before.raw(), after.raw());
    }

    private static Check check(WatchExpectation expectation, Map<String, String> variables, Value before, Value after) {
        String label = expectation.type().name();
        switch (expectation.type()) {
            case CHANGED:
                return changed(before, after)
                        ? new Check(Verdict.OK, "CHANGED: " + show(before) + " -> " + show(after))
                        : new Check(Verdict.FAIL, "expected a change; still " + show(after));
            case UNCHANGED:
                return changed(before, after)
                        ? new Check(Verdict.FAIL, "expected no change; " + show(before) + " -> " + show(after))
                        : new Check(Verdict.OK, "UNCHANGED: " + show(after));
            default:
                break;
        }
        Map<String, BigDecimal> bound = new HashMap<>();
        if (before != null && before.number() != null) bound.put("before", before.number());
        if (after != null && after.number() != null) bound.put("after", after.number());
        if (expectation.type() == WatchExpectation.Type.AFTER_AT_LEAST) {
            if (!bound.containsKey("after")) return new Check(Verdict.ERROR, label + ": AFTER is " + describeMissing(after));
            WatchExpression.Result min = WatchExpression.evaluate(expectation.expression(), variables, bound);
            if (!min.ok()) return new Check(Verdict.ERROR, label + ": " + min.explanation());
            BigDecimal value = bound.get("after");
            boolean ok = value.compareTo(min.value()) >= 0;
            return new Check(ok ? Verdict.OK : Verdict.FAIL, "after " + WatchExpression.format(value)
                    + (ok ? " >= " : " < ") + "minimum " + min.explanation());
        }
        if (!bound.containsKey("before") || !bound.containsKey("after")) {
            return new Check(Verdict.ERROR, label + ": needs numeric BEFORE and AFTER (BEFORE is "
                    + describeMissing(before) + ", AFTER is " + describeMissing(after) + ")");
        }
        BigDecimal delta = bound.get("after").subtract(bound.get("before"));
        String deltaText = "delta " + WatchExpression.format(delta);
        if (expectation.type() == WatchExpectation.Type.DELTA_EQUALS) {
            WatchExpression.Result expected = WatchExpression.evaluate(expectation.expression(), variables, bound);
            if (!expected.ok()) return new Check(Verdict.ERROR, label + ": " + expected.explanation());
            BigDecimal tolerance = BigDecimal.ZERO;
            if (!expectation.tolerance().isEmpty()) {
                tolerance = new WatchNumberParser().parseMachineFirst(expectation.tolerance(), null);
                if (tolerance == null) return new Check(Verdict.ERROR, label + ": tolerance '" + expectation.tolerance() + "' is not a number");
                tolerance = tolerance.abs();
            }
            boolean ok = delta.subtract(expected.value()).abs().compareTo(tolerance) <= 0;
            String suffix = tolerance.signum() == 0 ? "" : " (+/- " + WatchExpression.format(tolerance) + ")";
            return new Check(ok ? Verdict.OK : Verdict.FAIL,
                    deltaText + (ok ? " == " : " != ") + "expected " + expected.explanation() + suffix);
        }
        // DELTA_BETWEEN: blank bound = unbounded on that side.
        if (expectation.expression().isEmpty() && expectation.expression2().isEmpty()) {
            return new Check(Verdict.ERROR, label + ": no bounds configured");
        }
        StringBuilder text = new StringBuilder(deltaText);
        boolean ok = true;
        if (!expectation.expression().isEmpty()) {
            WatchExpression.Result low = WatchExpression.evaluate(expectation.expression(), variables, bound);
            if (!low.ok()) return new Check(Verdict.ERROR, label + " lower bound: " + low.explanation());
            boolean inside = delta.compareTo(low.value()) >= 0;
            ok = inside;
            text.append(inside ? " >= " : " < ").append("min ").append(low.explanation());
        }
        if (!expectation.expression2().isEmpty()) {
            WatchExpression.Result high = WatchExpression.evaluate(expectation.expression2(), variables, bound);
            if (!high.ok()) return new Check(Verdict.ERROR, label + " upper bound: " + high.explanation());
            boolean inside = delta.compareTo(high.value()) <= 0;
            ok &= inside;
            text.append(expectation.expression().isEmpty() ? "" : ",").append(inside ? " <= " : " > ")
                    .append("max ").append(high.explanation());
        }
        return new Check(ok ? Verdict.OK : Verdict.FAIL, text.toString());
    }

    private static String show(Value value) {
        return value == null ? "(absent)" : "'" + value.raw() + "'";
    }

    private static String describeMissing(Value value) {
        return value == null ? "absent" : value.number() == null ? "not numeric ('" + value.raw() + "')" : "numeric";
    }
}
