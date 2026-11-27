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

import static org.junit.jupiter.api.Assertions.*;

class LiveRetryEngineTest {
    private final MutableClock clock = new MutableClock(1_000_000L);
    private final List<Long> waits = new ArrayList<>();
    private final HostCooldownGate gate = new HostCooldownGate(clock);
    private final RetryWaiter waiter = millis -> { waits.add(millis); clock.advance(millis); return true; };
    private final LiveRetryEngine engine = new LiveRetryEngine(clock, waiter, gate);

    /** Responses in tests are "status|retryAfter|body". */
    private static final Function<String, RetryObservation> OBSERVE = response -> {
        String[] parts = response.split("\\|", -1);
        return new RetryObservation(Integer.parseInt(parts[0]), "HTTP/1.1 " + parts[0] + "\r\n\r\n" + parts[2],
                parts[1].isEmpty() ? null : parts[1]);
    };

    private static GlobalRetryPolicy policy(int maxRetries, boolean retryAfter, int budgetSeconds, boolean cooldown) {
        return new GlobalRetryPolicy(true, true, "", List.of("PROXY"), List.of(429, 503), "(?i)blocked by waf",
                List.of(), maxRetries, 1_000, 3_000, 2.0, retryAfter, budgetSeconds, cooldown);
    }

    private static Supplier<String> responses(String... values) {
        Deque<String> queue = new ArrayDeque<>(List.of(values));
        return () -> queue.isEmpty() ? null : queue.poll();
    }

    @Test
    void nonRetryableResponseIsUntouchedAndNothingIsSent() {
        AtomicInteger sends = new AtomicInteger();
        var result = engine.execute(policy(3, true, 60, true), "h:443", "200||ok", OBSERVE,
                () -> { sends.incrementAndGet(); return "200||x"; });
        assertEquals(LiveRetryEngine.Stop.NOT_RETRYABLE, result.stop());
        assertFalse(result.replaced());
        assertEquals("200||ok", result.response());
        assertEquals(0, sends.get());
        assertTrue(waits.isEmpty());
    }

    @Test
    void retriesWithExponentialBackoffUntilRecovered() {
        var result = engine.execute(policy(5, true, 60, true), "h:443", "429||", OBSERVE,
                responses("503||", "429||", "200||fine"));
        assertEquals(LiveRetryEngine.Stop.RECOVERED, result.stop());
        assertEquals("200||fine", result.response());
        assertEquals(3, result.resends());
        assertEquals(List.of(1_000L, 2_000L, 3_000L), waits, "1s, 2s, then capped at max delay 3s");
        assertEquals(6_000L, result.waitedMillis());
        assertEquals(0L, gate.remainingMillis("h:443"), "success releases the host cooldown");
    }

    @Test
    void responseRegexTriggersRetry() {
        var result = engine.execute(policy(3, true, 60, false), "h:443", "403||<h1>Blocked by WAF</h1>", OBSERVE,
                responses("200||ok"));
        assertEquals(LiveRetryEngine.Stop.RECOVERED, result.stop());
        assertEquals(1, result.resends());
    }

    @Test
    void stopsAfterMaxRetriesAndDeliversLastResponse() {
        var result = engine.execute(policy(2, true, 60, false), "h:443", "429||first", OBSERVE,
                responses("429||second", "429||third", "200||never"));
        assertEquals(LiveRetryEngine.Stop.RETRIES_EXHAUSTED, result.stop());
        assertEquals("429||third", result.response());
        assertEquals(2, result.resends());
    }

    @Test
    void honorsRetryAfterAndBlocksTheHost() {
        List<Long> cooldownSeenBySecondRequest = new ArrayList<>();
        RetryWaiter observing = millis -> {
            cooldownSeenBySecondRequest.add(gate.remainingMillis("h:443"));
            waits.add(millis); clock.advance(millis); return true;
        };
        var result = new LiveRetryEngine(clock, observing, gate).execute(policy(3, true, 60, true), "h:443",
                "429|5|", OBSERVE, responses("200||ok"));
        assertEquals(List.of(5_000L), waits);
        assertEquals(List.of(5_000L), cooldownSeenBySecondRequest);
        assertEquals(LiveRetryEngine.Stop.RECOVERED, result.stop());
    }

    @Test
    void ignoresRetryAfterWhenDisabled() {
        engine.execute(policy(3, false, 60, false), "h:443", "429|30|", OBSERVE, responses("200||ok"));
        assertEquals(List.of(1_000L), waits);
    }

    @Test
    void stopsWhenTheWaitWouldExceedTheBudgetButStillCoolsTheHostDown() {
        var result = engine.execute(policy(3, true, 10, true), "h:443", "429|120|", OBSERVE, responses("200||ok"));
        assertEquals(LiveRetryEngine.Stop.BUDGET_EXCEEDED, result.stop());
        assertFalse(result.replaced());
        assertTrue(waits.isEmpty());
        assertEquals(120_000L, gate.remainingMillis("h:443"));
    }

    @Test
    void keepsLastResponseWhenResendFailsOrIsAborted() {
        var failed = engine.execute(policy(3, true, 60, false), "h:443", "429||", OBSERVE,
                () -> { throw new IllegalStateException("connection reset"); });
        assertEquals(LiveRetryEngine.Stop.SEND_FAILED, failed.stop());
        assertEquals("connection reset", failed.detail());
        assertEquals("429||", failed.response());

        var empty = engine.execute(policy(3, true, 60, false), "h:443", "429||", OBSERVE, responses());
        assertEquals(LiveRetryEngine.Stop.SEND_FAILED, empty.stop());

        var aborted = new LiveRetryEngine(clock, millis -> false, gate).execute(policy(3, true, 60, false), "h:443",
                "429||", OBSERVE, responses("200||ok"));
        assertEquals(LiveRetryEngine.Stop.ABORTED, aborted.stop());
        assertEquals("429||", aborted.response());
    }

    @Test
    void gateKeepsTheLatestDeadlineAndExpires() {
        gate.block("a:80", clock.millis() + 5_000);
        gate.block("a:80", clock.millis() + 2_000);
        assertEquals(5_000L, gate.remainingMillis("a:80"));
        clock.advance(5_000);
        assertEquals(0L, gate.remainingMillis("a:80"));
        assertEquals(0L, gate.remainingMillis("unknown:80"));
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
