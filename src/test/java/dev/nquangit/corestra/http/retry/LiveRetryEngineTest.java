package dev.nquangit.corestra.http.retry;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static dev.nquangit.corestra.flow.control.StepConditionOperator.CONTAINS;
import static dev.nquangit.corestra.flow.control.StepConditionOperator.EQUALS;
import static dev.nquangit.corestra.flow.control.StepConditionOperator.IN;
import static org.junit.jupiter.api.Assertions.*;

class LiveRetryEngineTest {
    private final MutableClock clock = new MutableClock(1_000_000L);
    private final List<Long> waits = new ArrayList<>();
    private final HostCooldownGate gate = new HostCooldownGate(clock);
    private final RetryWaiter waiter = millis -> { waits.add(millis); clock.advance(millis); return true; };
    private final LiveRetryEngine engine = new LiveRetryEngine(clock, waiter, gate);

    /** Responses in tests are raw HTTP text. */
    private static final Function<String, RetryMessage> VIEW = raw -> RetryMessage.parse(raw, "GET", "/");

    private static final RetryRule RATE_LIMIT = RetryRule.of("rate", RetryRule.Action.RETRY,
            List.of(RetryCondition.of(RetryConditionSource.STATUS, "", IN, "429, 503")));
    private static final RetryRule WAF_STOP = RetryRule.of("waf", RetryRule.Action.STOP,
            List.of(RetryCondition.of(RetryConditionSource.RESPONSE_BODY, "", CONTAINS, "blocked by waf")))
            .withHighlight(RetryRule.Highlight.RED);
    private static final RetryRule GATEWAY = new RetryRule(null, "gateway", true, RetryRule.Action.RETRY,
            List.of(RetryCondition.of(RetryConditionSource.STATUS, "", EQUALS, "502")), 500L, 1, null);

    private static RetryProfile profile(int maxRetries, boolean retryAfter, int budgetSeconds, boolean cooldown) {
        return new RetryProfile(null, "p", true, List.of("PROXY"), true, "", "", List.of(),
                List.of(WAF_STOP, RATE_LIMIT, GATEWAY), maxRetries, 1_000, 3_000, 2.0, retryAfter, budgetSeconds, cooldown);
    }

    private static String raw(int status, String retryAfter, String body) {
        return "HTTP/1.1 " + status + " X\n" + (retryAfter == null ? "" : "Retry-After: " + retryAfter + "\n") + "\n" + body;
    }

    private static Supplier<String> responses(String... values) {
        Deque<String> queue = new ArrayDeque<>(List.of(values));
        return () -> queue.isEmpty() ? null : queue.poll();
    }

    @Test
    void noMatchingRuleLeavesTheResponseAlone() {
        AtomicInteger sends = new AtomicInteger();
        var result = engine.execute(profile(3, true, 60, true), "h:443", raw(200, null, "ok"), VIEW,
                () -> { sends.incrementAndGet(); return raw(200, null, ""); });
        assertEquals(LiveRetryEngine.Stop.NO_MATCH, result.stop());
        assertNull(result.rule());
        assertEquals(0, sends.get());
    }

    @Test
    void stopRuleOnTheOriginalResponseIsNeverRetried() {
        var result = engine.execute(profile(3, true, 60, true), "h:443", raw(429, null, "Blocked by WAF"), VIEW,
                responses(raw(200, null, "")));
        assertEquals(LiveRetryEngine.Stop.STOPPED_BY_RULE, result.stop(), "rule order: WAF stop beats rate-limit retry");
        assertEquals("waf", result.rule().name());
        assertFalse(result.replaced());
        assertTrue(waits.isEmpty());
    }

    @Test
    void retriesWithExponentialBackoffUntilRecovered() {
        var result = engine.execute(profile(5, true, 60, true), "h:443", raw(429, null, ""), VIEW,
                responses(raw(503, null, ""), raw(429, null, ""), raw(200, null, "fine")));
        assertEquals(LiveRetryEngine.Stop.RECOVERED, result.stop());
        assertEquals(3, result.resends());
        assertEquals("rate", result.triggerRule().name());
        assertNull(result.rule());
        assertEquals(List.of(1_000L, 2_000L, 3_000L), waits, "1s, 2s, then capped at 3s");
        assertEquals(0L, gate.remainingMillis("h:443"), "success releases the cooldown");
    }

    @Test
    void retryThatRevealsAStopRuleEndsWithThatRule() {
        var result = engine.execute(profile(5, true, 60, false), "h:443", raw(429, null, ""), VIEW,
                responses(raw(403, null, "blocked by WAF")));
        assertEquals(LiveRetryEngine.Stop.STOPPED_BY_RULE, result.stop());
        assertEquals("waf", result.rule().name());
        assertEquals(1, result.resends());
    }

    @Test
    void ruleDelayAndRuleRetryLimitOverrideTheProfile() {
        var result = engine.execute(profile(5, true, 60, false), "h:443", raw(502, "30", ""), VIEW,
                responses(raw(502, null, "again"), raw(200, null, "")));
        assertEquals(List.of(500L), waits, "fixed rule delay beats Retry-After and backoff");
        assertEquals(LiveRetryEngine.Stop.RETRIES_EXHAUSTED, result.stop(), "rule allows 1 retry");
        assertEquals("gateway", result.rule().name());
    }

    @Test
    void honorsRetryAfterAndCoolsTheHostDown() {
        List<Long> seenByOthers = new ArrayList<>();
        RetryWaiter observing = millis -> {
            seenByOthers.add(gate.remainingMillis("h:443"));
            waits.add(millis); clock.advance(millis); return true;
        };
        new LiveRetryEngine(clock, observing, gate).execute(profile(3, true, 60, true), "h:443", raw(429, "5", ""),
                VIEW, responses(raw(200, null, "")));
        assertEquals(List.of(5_000L), waits);
        assertEquals(List.of(5_000L), seenByOthers);

        waits.clear();
        engine.execute(profile(3, false, 60, false), "h:443", raw(429, "30", ""), VIEW, responses(raw(200, null, "")));
        assertEquals(List.of(1_000L), waits, "Retry-After ignored when disabled");
    }

    @Test
    void budgetExhaustedMaxRetriesSendFailureAndAbort() {
        var budget = engine.execute(profile(3, true, 10, true), "h:443", raw(429, "120", ""), VIEW, responses(raw(200, null, "")));
        assertEquals(LiveRetryEngine.Stop.BUDGET_EXCEEDED, budget.stop());
        assertEquals(120_000L, gate.remainingMillis("h:443"));

        var exhausted = engine.execute(profile(2, false, 60, false), "h:443", raw(429, null, "a"), VIEW,
                responses(raw(429, null, "b"), raw(429, null, "c"), raw(200, null, "")));
        assertEquals(LiveRetryEngine.Stop.RETRIES_EXHAUSTED, exhausted.stop());
        assertTrue(exhausted.response().endsWith("c"));

        var failed = engine.execute(profile(3, false, 60, false), "h:443", raw(429, null, "orig"), VIEW,
                () -> { throw new IllegalStateException("connection reset"); });
        assertEquals(LiveRetryEngine.Stop.SEND_FAILED, failed.stop());
        assertEquals("connection reset", failed.detail());
        assertTrue(failed.response().endsWith("orig"));

        var aborted = new LiveRetryEngine(clock, millis -> false, gate).execute(profile(3, false, 60, false), "h:443",
                raw(429, null, ""), VIEW, responses(raw(200, null, "")));
        assertEquals(LiveRetryEngine.Stop.ABORTED, aborted.stop());
    }

    @Test
    void gateKeepsTheLatestDeadlineAndExpires() {
        gate.block("a:80", clock.millis() + 5_000);
        gate.block("a:80", clock.millis() + 2_000);
        assertEquals(5_000L, gate.remainingMillis("a:80"));
        clock.advance(5_000);
        assertEquals(0L, gate.remainingMillis("a:80"));
    }

    @Test
    void closableWaiterReleasesWaitersOnClose() throws Exception {
        ClosableRetryWaiter real = new ClosableRetryWaiter();
        assertTrue(real.await(1));
        Thread closer = new Thread(() -> { try { Thread.sleep(50); } catch (InterruptedException ignored) { } real.close(); });
        closer.start();
        long started = System.nanoTime();
        assertFalse(real.await(10_000));
        assertTrue(System.nanoTime() - started < 5_000_000_000L);
        closer.join();
        assertFalse(real.await(0));
    }

    private static final class MutableClock extends Clock {
        private long millis;
        MutableClock(long millis) { this.millis = millis; }
        void advance(long delta) { millis += delta; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
}
