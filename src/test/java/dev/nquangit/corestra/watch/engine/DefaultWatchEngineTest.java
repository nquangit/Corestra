package dev.nquangit.corestra.watch.engine;

import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.watch.WatchDefinition;
import dev.nquangit.corestra.watch.WatchExpectation;
import dev.nquangit.corestra.watch.WatchExpectation.Type;
import dev.nquangit.corestra.watch.WatchNumberFormat;
import dev.nquangit.corestra.watch.WatchProbe;
import dev.nquangit.corestra.watch.WatchProbeClient;
import dev.nquangit.corestra.watch.WatchProbeClient.ProbeResponse;
import dev.nquangit.corestra.watch.WatchResult;
import dev.nquangit.corestra.watch.WatchResult.Row;
import dev.nquangit.corestra.watch.WatchResult.Status;
import dev.nquangit.corestra.watch.WatchResult.Verdict;
import dev.nquangit.corestra.watch.WatchSnapshot;
import dev.nquangit.corestra.watch.WatchSnapshot.Value;
import dev.nquangit.corestra.watch.WatchTiming;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static dev.nquangit.corestra.watch.engine.WatchFixtures.bankAccounts;
import static dev.nquangit.corestra.watch.engine.WatchFixtures.jsonCollection;
import static dev.nquangit.corestra.watch.engine.WatchFixtures.ok;
import static dev.nquangit.corestra.watch.engine.WatchFixtures.regexCollection;
import static dev.nquangit.corestra.watch.engine.WatchFixtures.vndHtml;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultWatchEngineTest {
    private static final String FEE = "-{{amount}} - max({{amount}} * 0.011, 5000)";
    private static final Map<String, String> AMOUNT = Map.of("amount", "500000");

    private final AtomicLong clock = new AtomicLong(1_000);
    private final List<Long> sleeps = new ArrayList<>();
    private final Deque<ProbeResponse> scripted = new ArrayDeque<>();
    private final List<Long> timeouts = new ArrayList<>();
    private final WatchProbeClient client = (definition, variables, headers, timeoutMillis) -> {
        timeouts.add(timeoutMillis);
        clock.addAndGet(50);
        return scripted.size() > 1 ? scripted.poll() : scripted.peek();
    };
    private final DefaultWatchEngine engine = new DefaultWatchEngine(client, clock::get, millis -> {
        sleeps.add(millis);
        clock.addAndGet(millis);
    });

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private static WatchDefinition bankWatch(WatchTiming timing, WatchExpectation... expectations) {
        StoredRequestTemplate probe = new StoredRequestTemplate("GET", "https", "bank.test", 443, "/api/accounts",
                List.of("Host: bank.test"), "");
        return WatchDefinition.empty("w1", "Transfer")
                .withProbe(new WatchProbe(probe, null))
                .withObservations(List.of(jsonCollection("balance", "$.accounts", "accountNo", "balance.available")))
                .withExpectations(List.of(expectations))
                .withTiming(timing);
    }

    private static WatchTiming noSettle() {
        return new WatchTiming(5_000, 700, 1, 0, 60_000);
    }

    private static WatchExpectation expect(String pattern, Type type, String expression, String expression2, String tolerance) {
        return new WatchExpectation("e-" + pattern, true, pattern, type, expression, expression2, tolerance);
    }

    private static Row row(WatchResult result, String key) {
        return result.rows().stream().filter(r -> r.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void captureReadsBankAccountsWithTimeout() {
        scripted.add(ok(bankAccounts("15000000", "2000000")));
        WatchSnapshot snapshot = engine.capture(bankWatch(new WatchTiming(1_500, 700, 2, 6_000, 60_000)), AMOUNT, List.of());
        assertFalse(snapshot.failed());
        assertEquals(200, snapshot.probeStatusCode());
        assertEquals(1_050, snapshot.capturedAtMillis());
        assertEquals(List.of(1_500L), timeouts);
        assertEquals(0, new BigDecimal("15000000").compareTo(snapshot.values().get("balance[0123]").number()));
    }

    @Test
    void captureFailuresBecomeFailedSnapshots() {
        scripted.add(ProbeResponse.failure("timed out"));
        WatchSnapshot failed = engine.capture(bankWatch(noSettle()), AMOUNT, List.of());
        assertTrue(failed.failed());
        assertTrue(failed.errors().get(0).contains("timed out"));

        assertTrue(engine.capture(WatchDefinition.empty("x", "x"), Map.of(), List.of()).errors().get(0).contains("not configured"));

        WatchProbeClient throwing = (d, v, h, t) -> { throw new IllegalStateException("boom"); };
        WatchSnapshot thrown = new DefaultWatchEngine(throwing, clock::get, m -> { }).capture(bankWatch(noSettle()), null, null);
        assertTrue(thrown.failed());
        assertTrue(thrown.errors().get(0).contains("boom"));

        scripted.clear();
        scripted.add(ok("{}"));
        assertTrue(engine.capture(bankWatch(noSettle()).withObservations(List.of()), Map.of(), List.of()).failed());
    }

    @Test
    void nonSuccessStatusIsStillParsedButNoted() {
        scripted.add(new ProbeResponse(503, List.of(), bankAccounts("1", "2"), null));
        WatchSnapshot snapshot = engine.capture(bankWatch(noSettle()), AMOUNT, List.of());
        assertFalse(snapshot.failed());
        assertEquals(2, snapshot.values().size());
        assertEquals("Probe returned HTTP 503", snapshot.errors().get(0));

        scripted.clear();
        scripted.add(new ProbeResponse(500, List.of(), "<html>oops</html>", null));
        WatchSnapshot broken = engine.capture(bankWatch(noSettle()), AMOUNT, List.of());
        assertTrue(broken.failed());
    }

    @Test
    void transferWithFeePasses() {
        WatchDefinition watch = bankWatch(noSettle(),
                expect("balance[0123]", Type.DELTA_EQUALS, FEE, "", ""),
                expect("balance[0456]", Type.UNCHANGED, "", "", ""));
        scripted.add(ok(bankAccounts("15000000", "2000000")));
        WatchSnapshot before = engine.capture(watch, AMOUNT, List.of());
        scripted.clear();
        scripted.add(ok(bankAccounts("14494500", "2000000")));
        WatchResult result = engine.complete(watch, AMOUNT, List.of(), before, List.of("flow ok"));

        assertEquals(Status.PASS, result.status(), result.toString());
        assertTrue(result.settled());
        assertEquals(0, result.settleMillis());
        assertTrue(sleeps.isEmpty());
        Row checking = row(result, "balance[0123]");
        assertEquals(Verdict.OK, checking.verdict());
        assertEquals(0, new BigDecimal("-505500").compareTo(checking.delta()));
        assertTrue(checking.changed());
        assertEquals("delta -505500 == expected -500000 - max(500000 * 0.011, 5000) = -505500", checking.explanation());
        assertEquals(Verdict.OK, row(result, "balance[0456]").verdict());
        assertEquals(List.of("flow ok"), result.notes());
        assertEquals("w1", result.watchId());
        assertEquals("Transfer", result.watchName());
    }

    @Test
    void feeNotChargedFails() {
        WatchDefinition watch = bankWatch(noSettle(), expect("balance[0123]", Type.DELTA_EQUALS, FEE, "", ""));
        WatchResult result = engine.evaluate(watch, AMOUNT, snap("balance[0123]", "15000000"),
                snap("balance[0123]", "14500000"), true, 0, List.of());
        assertEquals(Status.FAIL, result.status());
        Row checking = row(result, "balance[0123]");
        assertEquals(Verdict.FAIL, checking.verdict());
        assertTrue(checking.explanation().startsWith("delta -500000 != expected"), checking.explanation());
    }

    @Test
    void toleranceAndBetweenAndAtLeast() {
        Map<String, String> vars = Map.of("amount", "100");
        WatchDefinition watch = bankWatch(noSettle(),
                expect("a", Type.DELTA_EQUALS, "-{{amount}}", "", "0.5"),
                expect("b", Type.DELTA_BETWEEN, "-amount - 10", "-amount", ""),
                expect("c", Type.AFTER_AT_LEAST, "0", "", ""),
                expect("d", Type.DELTA_BETWEEN, "", "0", ""));
        WatchSnapshot before = snaps(Map.of("a", "1000", "b", "1000", "c", "50", "d", "5"));
        WatchSnapshot after = snaps(Map.of("a", "900.40", "b", "895", "c", "0", "d", "4"));
        WatchResult result = engine.evaluate(watch, vars, before, after, true, 0, List.of());
        assertEquals(Status.PASS, result.status(), result.toString());
        assertTrue(row(result, "a").explanation().contains("(+/- 0.5)"));
        assertEquals("delta -105 >= min -100 - 10 = -110, <= max -100 = -100", row(result, "b").explanation());

        WatchSnapshot overdrawn = snaps(Map.of("a", "899", "b", "880", "c", "-1", "d", "6"));
        WatchResult failed = engine.evaluate(watch, vars, before, overdrawn, true, 0, List.of());
        assertEquals(Status.FAIL, failed.status());
        assertEquals(Verdict.FAIL, row(failed, "a").verdict());
        assertEquals(Verdict.FAIL, row(failed, "b").verdict());
        assertEquals("after -1 < minimum 0 = 0", row(failed, "c").explanation());
        assertEquals(Verdict.FAIL, row(failed, "d").verdict());
    }

    @Test
    void globAppliesToEveryAccountAndChangedFailsWhenStatic() {
        WatchDefinition watch = bankWatch(noSettle(), expect("balance[*]", Type.CHANGED, "", "", ""));
        WatchResult result = engine.evaluate(watch, Map.of(),
                snaps(Map.of("balance[0123]", "10", "balance[0456]", "20")),
                snaps(Map.of("balance[0123]", "9", "balance[0456]", "20")), true, 0, List.of());
        assertEquals(Status.FAIL, result.status());
        assertEquals(Verdict.OK, row(result, "balance[0123]").verdict());
        assertEquals(Verdict.FAIL, row(result, "balance[0456]").verdict());
    }

    @Test
    void unmatchedExpectationIsNotedAndFails() {
        WatchDefinition watch = bankWatch(noSettle(),
                expect("balance[0123]", Type.CHANGED, "", "", ""),
                expect("balance[9999]", Type.UNCHANGED, "", "", ""),
                new WatchExpectation("off", false, "nothing", Type.CHANGED, "", "", ""));
        WatchResult result = engine.evaluate(watch, Map.of(), snap("balance[0123]", "1"), snap("balance[0123]", "2"),
                true, 0, List.of());
        assertEquals(Status.FAIL, result.status());
        assertEquals(List.of("Expectation UNCHANGED 'balance[9999]' matched no observed key"), result.notes());
    }

    @Test
    void evaluationErrorsMarkRowAndOverallError() {
        WatchDefinition watch = bankWatch(noSettle(),
                expect("a", Type.DELTA_EQUALS, "-{{amount}}", "", ""),
                expect("t", Type.DELTA_EQUALS, "0", "", ""),
                expect("b", Type.DELTA_EQUALS, "0", "", "abc"),
                expect("c", Type.DELTA_BETWEEN, "", "", ""));
        Map<String, Value> before = new LinkedHashMap<>();
        before.put("a", num("1"));
        before.put("t", new Value("ACTIVE", null));
        before.put("b", num("1"));
        before.put("c", num("1"));
        WatchSnapshot snapshot = new WatchSnapshot(1, 200, before, List.of());
        WatchResult result = engine.evaluate(watch, Map.of(), snapshot, snapshot, true, 0, List.of());
        assertEquals(Status.ERROR, result.status());
        assertTrue(row(result, "a").explanation().contains("Unknown variable 'amount'"));
        assertTrue(row(result, "t").explanation().contains("not numeric ('ACTIVE')"));
        assertTrue(row(result, "b").explanation().contains("tolerance 'abc'"));
        assertTrue(row(result, "c").explanation().contains("no bounds"));
        result.rows().forEach(r -> assertEquals(Verdict.ERROR, r.verdict()));
    }

    @Test
    void textValuesAndPresenceChanges() {
        Map<String, Value> before = new LinkedHashMap<>();
        before.put("status", new Value("PENDING", null));
        before.put("gone", num("5"));
        Map<String, Value> after = new LinkedHashMap<>();
        after.put("status", new Value("DONE", null));
        after.put("new", num("7"));
        WatchResult result = engine.evaluate(bankWatch(noSettle()), Map.of(), new WatchSnapshot(1, 200, before, List.of()),
                new WatchSnapshot(2, 200, after, List.of()), true, 0, List.of());
        assertEquals(List.of("status", "gone", "new"), result.rows().stream().map(Row::key).toList());
        assertEquals(Status.CHANGED, result.status());
        Row gone = row(result, "gone");
        assertTrue(gone.changed());
        assertNull(gone.afterRaw());
        assertNull(gone.delta());
        assertEquals(Verdict.INFO, gone.verdict());
        assertEquals("", gone.explanation());
        assertTrue(row(result, "status").changed());
    }

    @Test
    void noExpectationsAndNoChange() {
        WatchSnapshot same = snap("x", "1,000");
        WatchResult result = engine.evaluate(bankWatch(noSettle()), Map.of(), same, snap("x", "1000"), true, 0, List.of());
        assertEquals(Status.NO_CHANGE, result.status());
        assertFalse(row(result, "x").changed(), "numerically equal values are unchanged");
        assertEquals(0, row(result, "x").delta().signum());
    }

    @Test
    void failedCaptureIsError() {
        WatchDefinition watch = bankWatch(noSettle(), expect("*", Type.CHANGED, "", "", ""));
        WatchResult result = engine.evaluate(watch, Map.of(), WatchSnapshot.failed(1, 0, "timed out"),
                snap("x", "1"), true, 0, List.of());
        assertEquals(Status.ERROR, result.status());
        assertEquals(List.of("BEFORE: timed out"), result.notes());
        assertEquals(Verdict.INFO, row(result, "x").verdict());

        WatchResult nulls = engine.evaluate(null, null, null, null, true, 0, null);
        assertEquals(Status.ERROR, nulls.status());
    }

    @Test
    void partialSnapshotErrorsBecomeNotes() {
        WatchSnapshot partial = new WatchSnapshot(1, 200, Map.of("x", num("1")), List.of("y: regex did not match"));
        WatchResult result = engine.evaluate(bankWatch(noSettle()), Map.of(), partial, partial, true, 0, List.of());
        assertEquals(Status.NO_CHANGE, result.status());
        assertEquals(List.of("BEFORE: y: regex did not match", "AFTER: y: regex did not match"), result.notes());
    }

    @Test
    void settleWaitsUntilStableAfterOneChange() {
        WatchDefinition watch = bankWatch(new WatchTiming(5_000, 700, 2, 6_000, 60_000),
                expect("balance[0123]", Type.DELTA_EQUALS, FEE, "", ""));
        WatchSnapshot before = new WatchSnapshot(0, 200,
                Map.of("balance[0123]", num("15000000"), "balance[0456]", num("2000000")), List.of());
        scripted.add(ok(bankAccounts("15000000", "2000000")));    // first AFTER: not yet debited
        scripted.add(ok(bankAccounts("14494500", "2000000")));    // debited
        scripted.add(ok(bankAccounts("14494500", "2000000")));    // stable
        WatchResult result = engine.complete(watch, AMOUNT, List.of("Cookie: s=1"), before, List.of());

        assertEquals(Status.PASS, result.status(), result.toString());
        assertTrue(result.settled());
        assertEquals(List.of(700L, 700L), sleeps);
        assertEquals(3, timeouts.size());
        assertEquals(1_500, result.settleMillis(), "two intervals plus two probe round-trips");
        assertEquals(0, new BigDecimal("-505500").compareTo(row(result, "balance[0123]").delta()));
    }

    @Test
    void unsettledWhenStateKeepsChanging() {
        WatchDefinition watch = bankWatch(new WatchTiming(5_000, 1_000, 3, 2_500, 60_000));
        WatchSnapshot before = snap("balance[0123]", "100");
        for (int i = 0; i < 10; i++) scripted.add(ok(bankAccounts(String.valueOf(99 - i), "0")));
        WatchResult result = engine.complete(watch, Map.of(), List.of(), before, List.of());

        assertFalse(result.settled());
        assertEquals(List.of(1_000L, 1_000L, 1_000L), sleeps);
        assertTrue(result.notes().stream().anyMatch(n -> n.startsWith("AFTER still changing after 4 captures")),
                result.notes()::toString);
        assertEquals("96", row(result, "balance[0123]").afterRaw(), "the last capture is reported");
        assertEquals(Status.CHANGED, result.status());
    }

    @Test
    void failedRecaptureDoesNotReplaceGoodAfter() {
        WatchDefinition watch = bankWatch(new WatchTiming(5_000, 500, 2, 1_200, 60_000));
        scripted.add(ok(bankAccounts("5", "0")));
        scripted.add(ProbeResponse.failure("reset"));
        scripted.add(ProbeResponse.failure("reset"));
        WatchResult result = engine.complete(watch, Map.of(), List.of(), snap("balance[0123]", "5"), List.of());
        assertFalse(result.settled());
        assertEquals("5", row(result, "balance[0123]").afterRaw());
        assertFalse(result.after().failed());
    }

    @Test
    void stableCountOneTakesFirstCapture() {
        scripted.add(ok(bankAccounts("1", "2")));
        WatchResult result = engine.complete(bankWatch(new WatchTiming(5_000, 700, 1, 6_000, 60_000)), Map.of(),
                List.of(), snap("balance[0123]", "1"), null);
        assertTrue(result.settled());
        assertTrue(sleeps.isEmpty());
        assertEquals(1, timeouts.size());
    }

    @Test
    void interruptionStopsSettlingAndRestoresFlag() {
        WatchProbeClient changing = new WatchProbeClient() {
            private int n;

            @Override
            public ProbeResponse fetch(WatchDefinition d, Map<String, String> v, List<String> h, long t) {
                return ok(bankAccounts(String.valueOf(n++), "0"));
            }
        };
        DefaultWatchEngine interrupted = new DefaultWatchEngine(changing, clock::get, millis -> {
            throw new InterruptedException();
        });
        WatchResult result = interrupted.complete(bankWatch(WatchTiming.defaults()), Map.of(), List.of(),
                snap("balance[0123]", "0"), List.of());
        assertTrue(Thread.currentThread().isInterrupted());
        assertFalse(result.settled());
        assertTrue(result.notes().contains("Settling was interrupted after 1 captures"), result.notes()::toString);
    }

    @Test
    void completeWithFailedAfterIsError() {
        scripted.add(ProbeResponse.failure("connection refused"));
        WatchResult result = engine.complete(bankWatch(noSettle(), expect("*", Type.CHANGED, "", "", "")), Map.of(),
                List.of(), snap("balance[0123]", "1"), List.of());
        assertEquals(Status.ERROR, result.status());
        assertTrue(result.notes().contains("AFTER: Probe failed: connection refused"), result.notes()::toString);
    }

    @Test
    void vndHtmlEndToEnd() {
        WatchNumberFormat vnd = new WatchNumberFormat(false, ",", ".", 0, "[^0-9.,\\-]");
        WatchDefinition watch = bankWatch(noSettle(), expect("balance[0123]", Type.DELTA_EQUALS, FEE, "", ""),
                expect("balance[0456]", Type.DELTA_EQUALS, "{{amount}}", "", ""))
                .withObservations(List.of(regexCollection("balance",
                        "<td class=\"no\">(?<key>\\d+)</td><td class=\"bal\">(?<value>[^<]+)</td>", vnd)));
        scripted.add(ok(vndHtml("15.000.000", "2.000.000")));
        WatchSnapshot before = engine.capture(watch, AMOUNT, List.of());
        scripted.clear();
        scripted.add(ok(vndHtml("14.494.500", "2.500.000")));
        WatchResult result = engine.complete(watch, AMOUNT, List.of(), before, List.of());
        assertEquals(Status.PASS, result.status(), result.toString());
        assertEquals("15.000.000 VND", row(result, "balance[0123]").beforeRaw());
        assertEquals("delta 500000 == expected 500000 = 500000", row(result, "balance[0456]").explanation());
    }

    @Test
    void convenienceConstructorWorks() {
        scripted.add(ok(bankAccounts("1", "2")));
        assertFalse(new DefaultWatchEngine(client).capture(bankWatch(noSettle()), Map.of(), List.of()).failed());
    }

    private static Value num(String raw) {
        return new Value(raw, new WatchNumberParser().parse(raw, null));
    }

    private static WatchSnapshot snap(String key, String raw) {
        return new WatchSnapshot(1, 200, Map.of(key, num(raw)), List.of());
    }

    private static WatchSnapshot snaps(Map<String, String> raw) {
        Map<String, Value> values = new LinkedHashMap<>();
        raw.keySet().stream().sorted().forEach(k -> values.put(k, num(raw.get(k))));
        return new WatchSnapshot(1, 200, values, List.of());
    }
}
