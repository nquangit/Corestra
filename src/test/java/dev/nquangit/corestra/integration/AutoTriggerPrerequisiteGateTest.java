package dev.nquangit.corestra.integration;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AutoTriggerPrerequisiteGateTest {
    @Test void failureReleasesLaneAndUnrelatedFlowsAreNotBlocked() throws Exception {
        try (var gate = new AutoTriggerPrerequisiteGate(); var workers = Executors.newFixedThreadPool(2)) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var holder = workers.submit(() -> gate.run("flow-a", () -> {
                entered.countDown();
                await(release);
                throw new IllegalStateException("network failure");
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals("independent", workers.submit(() -> gate.run("flow-b", () -> "independent")).get(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            assertThrows(java.util.concurrent.ExecutionException.class, () -> holder.get(5, TimeUnit.SECONDS));
            assertEquals("next", gate.run("flow-a", () -> "next"));
        }
    }

    @Test void closeWakesQueuedCallbacksWithoutExecutingTheirWork() throws Exception {
        var gate = new AutoTriggerPrerequisiteGate();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempted = new CountDownLatch(1);
        var ran = new AtomicBoolean();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var holder = workers.submit(() -> gate.run("flow", () -> { entered.countDown(); await(release); return "done"; }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var waiter = workers.submit(() -> {
                    attempted.countDown();
                    return assertThrows(TriggerInputException.class, () -> gate.run("flow", () -> { ran.set(true); return "unsafe"; }));
                });
                assertTrue(attempted.await(5, TimeUnit.SECONDS));
                gate.close();
                assertTrue(waiter.get(5, TimeUnit.SECONDS).getMessage().contains("unloaded"));
                assertFalse(ran.get());
                assertThrows(TriggerInputException.class, () -> gate.run("another-flow", () -> "unsafe"));
            } finally {
                gate.close();
                release.countDown();
            }
            assertEquals("done", holder.get(5, TimeUnit.SECONDS));
        }
    }

    @Test void interruptedWaiterKeepsItsInterruptFlagAndDoesNotBlockNextRun() throws Exception {
        try (var gate = new AutoTriggerPrerequisiteGate(); var workers = Executors.newSingleThreadExecutor()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var error = new AtomicReference<Throwable>();
            var interrupted = new AtomicBoolean();
            var holder = workers.submit(() -> gate.run("flow", () -> { entered.countDown(); await(release); return "done"; }));
            var waiter = new Thread(() -> {
                try { gate.run("flow", () -> { throw new AssertionError("Cancelled work ran"); }); }
                catch (Throwable failure) { error.set(failure); interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                waiter.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (waiter.isAlive() && waiter.getState() != Thread.State.WAITING && System.nanoTime() < deadline)
                    Thread.sleep(1);
                assertEquals(Thread.State.WAITING, waiter.getState());
                waiter.interrupt();
                waiter.join(5_000);
                assertFalse(waiter.isAlive());
                assertInstanceOf(TriggerInputException.class, error.get());
                assertTrue(interrupted.get());
            } finally {
                waiter.interrupt();
                release.countDown();
            }
            holder.get(5, TimeUnit.SECONDS);
            assertEquals("next", gate.run("flow", () -> "next"));
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
