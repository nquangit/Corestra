package dev.nquangit.corestra.session;

import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Delayed;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SessionSchedulerServiceTest {

    @Test
    void snapshotsDoNotRunAuthAndShowQueueFailureRetryAndDeletion() {
        SessionProfileRepository repository = new SessionProfileRepository();
        repository.saveProfile(scheduledProfile("p1"));
        AtomicInteger lookups = new AtomicInteger();
        RecordingScheduledExecutorService timer = new RecordingScheduledExecutorService();
        QueuedExecutor workers = new QueuedExecutor();
        SessionManager manager = manager(repository, () -> {
            lookups.incrementAndGet();
            throw new IllegalStateException("token=do-not-expose");
        });
        try (SessionSchedulerService service = new SessionSchedulerService(manager, clock(), timer, workers)) {
            assertEquals(SessionScheduledJob.Status.NOT_STARTED, job(service, "p1").status());
            service.start();
            assertEquals(6000, job(service, "p1").nextRunAtEpochMillis());
            assertEquals(0, lookups.get());
            timer.scheduledTasks.getFirst().run();
            assertEquals(SessionScheduledJob.Status.QUEUED, job(service, "p1").status());
            assertEquals(1, service.snapshot().queuedJobs());
            workers.tasks.removeFirst().run();
            SessionScheduledJob after = job(service, "p1");
            assertEquals(SessionScheduledJob.Status.BACKOFF, after.status());
            assertEquals(2000, after.nextRunAtEpochMillis());
            assertEquals(1000, after.lastStartedAtEpochMillis());
            assertEquals(1000, after.lastCompletedAtEpochMillis());
            assertEquals("FAILED", after.lastOutcome());
            assertFalse(service.snapshot().toString().contains("do-not-expose"));
            assertEquals(0, service.snapshot().queuedJobs());
            assertEquals(1, lookups.get());
            repository.deleteProfile("p1");
            assertTrue(service.snapshot().jobs().stream().noneMatch(row -> row.profileId().equals("p1")));
        }
    }

    @Test
    void snapshotUsesCurrentStateTtlAndConfigurationWithoutAuthOrPersistenceWrites() {
        SessionProfileRepository repository = new SessionProfileRepository();
        RecordingScheduledExecutorService timer = new RecordingScheduledExecutorService();
        try (SessionSchedulerService service = new SessionSchedulerService(
                manager(repository), clock(), timer, new DirectSessionExecutor())) {
            service.start();
            timer.scheduledTasks.getFirst().run();
            SessionProfile profile = new SessionProfile("p1", "First", true, false, SessionScope.burpTargetScope(),
                    new SessionAuthConfig("login", 0L, List.of()), new SessionSchedulerConfig(true, 10, false),
                    new SessionState(Map.of("cookie", "private"), 1000, 8000, 1000), List.of(), List.of());
            repository.saveProfile(profile);
            assertEquals(8000, job(service, "p1").nextRunAtEpochMillis(), "Expiry can make auth due before interval");
            assertFalse(service.snapshot().toString().contains("private"));
            repository.updateState("p1", state -> state.withAuthenticatedValues(Map.of(), 5000, 0));
            assertEquals(15000, job(service, "p1").nextRunAtEpochMillis());
            repository.saveProfile(repository.profile("p1").orElseThrow().withEnabled(false));
            assertEquals(SessionScheduledJob.Status.DISABLED, job(service, "p1").status());
            assertEquals(0, job(service, "p1").nextRunAtEpochMillis());
        }
    }

    private static SessionScheduledJob job(SessionSchedulerService service, String id) {
        return service.snapshot().jobs().stream().filter(row -> row.profileId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void startSchedulesStartupAndPeriodicRefreshOnlyOnce() {
        RecordingScheduledExecutorService executor = new RecordingScheduledExecutorService();
        SessionSchedulerService service = new SessionSchedulerService(manager(new SessionProfileRepository()), clock(), executor, new DirectSessionExecutor());

        service.start();
        service.start();

        assertEquals(1, executor.scheduledTasks.size());
        assertEquals(5L, executor.scheduledDelaysSeconds.getFirst());
        assertEquals(1, executor.periodicTasks.size());
        assertEquals(5L, executor.periodicInitialDelaysSeconds.getFirst());
        assertEquals(1L, executor.periodicDelaysSeconds.getFirst());
        service.close();
    }

    @Test
    void closeIsIdempotentAndPreventsQueuedTasksFromRunning() {
        SessionProfileRepository repository = new SessionProfileRepository();
        repository.saveProfile(scheduledProfile("p1"));
        AtomicInteger flowLookups = new AtomicInteger();
        SessionManager manager = manager(repository, () -> {
            flowLookups.incrementAndGet();
            throw new IllegalStateException("closed scheduler should not refresh");
        });
        RecordingScheduledExecutorService executor = new RecordingScheduledExecutorService();
        SessionSchedulerService service = new SessionSchedulerService(manager, clock(), executor, new DirectSessionExecutor());
        service.start();

        service.close();
        service.close();
        executor.scheduledTasks.getFirst().run();
        executor.periodicTasks.getFirst().run();

        assertEquals(1, executor.shutdownNowCalls);
        assertEquals(0, flowLookups.get());
    }

    @Test
    void schedulerCatchesProfileRefreshFailures() {
        SessionProfileRepository repository = new SessionProfileRepository();
        repository.saveProfile(scheduledProfile("p1"));
        AtomicInteger flowLookups = new AtomicInteger();
        SessionManager manager = manager(repository, () -> {
            flowLookups.incrementAndGet();
            throw new IllegalStateException("flow lookup failed");
        });
        RecordingScheduledExecutorService executor = new RecordingScheduledExecutorService();
        SessionSchedulerService service = new SessionSchedulerService(manager, clock(), executor, new DirectSessionExecutor());
        service.start();

        executor.periodicTasks.getFirst().run();

        assertEquals(1, flowLookups.get());
        executor.periodicTasks.getFirst().run();
        assertEquals(1, flowLookups.get(), "A failed attempt must not retry on every timer tick");
        service.close();
    }

    @Test
    void slowMaintenanceDoesNotBlockTimerOrAnotherProfile() throws Exception {
        SessionProfileRepository repository = new SessionProfileRepository();
        repository.saveProfile(scheduledProfile("slow"));
        repository.saveProfile(scheduledProfile("other"));
        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch otherEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger lookups = new AtomicInteger();
        SessionManager manager = manager(repository, () -> {
            if (lookups.incrementAndGet() == 1) {
                slowEntered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            } else {
                otherEntered.countDown();
            }
            throw new IllegalStateException("simulated failure");
        });
        RecordingScheduledExecutorService timer = new RecordingScheduledExecutorService();
        var workers = Executors.newFixedThreadPool(2);
        try (SessionSchedulerService service = new SessionSchedulerService(manager, clock(), timer, workers)) {
            service.start();
            assertTimeoutPreemptively(Duration.ofSeconds(2), timer.periodicTasks.getFirst()::run);
            assertTrue(slowEntered.await(2, TimeUnit.SECONDS));
            assertTrue(otherEntered.await(2, TimeUnit.SECONDS), "Other profiles must not wait for the slow flow");
            SessionSchedulerSnapshot snapshot = assertTimeoutPreemptively(Duration.ofSeconds(2), service::snapshot);
            assertTrue(snapshot.activeWorkers() >= 1, "A slow running flow must remain visible");
            assertTrue(snapshot.jobs().stream().anyMatch(row -> row.status() == SessionScheduledJob.Status.RUNNING));
            for (int tick = 0; tick < 20; tick++) timer.periodicTasks.getFirst().run();
            assertEquals(2, lookups.get(), "An active profile must not queue duplicate maintenance");
        } finally {
            release.countDown();
        }
        assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    void queuedTaskRechecksEnabledProfileAndCloseStopsBothExecutors() {
        SessionProfileRepository repository = new SessionProfileRepository();
        repository.saveProfile(scheduledProfile("p1"));
        AtomicInteger lookups = new AtomicInteger();
        SessionManager manager = manager(repository, () -> {
            lookups.incrementAndGet();
            return List.of();
        });
        RecordingScheduledExecutorService timer = new RecordingScheduledExecutorService();
        QueuedExecutor workers = new QueuedExecutor();
        SessionSchedulerService service = new SessionSchedulerService(manager, clock(), timer, workers);
        service.start();
        timer.periodicTasks.getFirst().run();
        timer.periodicTasks.getFirst().run();
        assertEquals(1, workers.tasks.size());
        repository.saveProfile(scheduledProfile("p1").withEnabled(false));
        workers.tasks.removeFirst().run();
        assertEquals(0, lookups.get());
        repository.saveProfile(scheduledProfile("p2"));
        timer.periodicTasks.getFirst().run();
        assertEquals(1, workers.tasks.size());
        Runnable queuedBeforeClose = workers.tasks.getFirst();
        service.close();
        queuedBeforeClose.run();
        assertEquals(0, lookups.get());
        assertTrue(workers.isShutdown());
        assertTrue(timer.isShutdown());
    }

    @Test
    void rejectedWorkIsRetriedOnLaterTick() {
        SessionProfileRepository repository = new SessionProfileRepository();
        repository.saveProfile(scheduledProfile("p1"));
        RecordingScheduledExecutorService timer = new RecordingScheduledExecutorService();
        QueuedExecutor workers = new QueuedExecutor();
        workers.reject = true;
        try (SessionSchedulerService service = new SessionSchedulerService(manager(repository), clock(), timer, workers)) {
            service.start();
            timer.periodicTasks.getFirst().run();
            assertTrue(workers.tasks.isEmpty());
            workers.reject = false;
            timer.periodicTasks.getFirst().run();
            assertEquals(1, workers.tasks.size());
        }
    }

    private static SessionManager manager(SessionProfileRepository repository) {
        return manager(repository, List::of);
    }

    private static SessionManager manager(SessionProfileRepository repository, FlowSupplier flowSupplier) {
        ExtractionService extractionService = new ExtractionService();
        return new SessionManager(
                repository,
                flowSupplier::get,
                new FlowEngine(extractionService, new InjectionService(), request -> {
                    throw new IllegalStateException("HTTP should not be reached");
                }),
                extractionService,
                clock());
    }

    private static SessionProfile scheduledProfile(String id) {
        return new SessionProfile(
                id,
                "Scheduled",
                true,
                false,
                SessionScope.burpTargetScope(),
                new SessionAuthConfig("login-flow", 0L, List.of()),
                new SessionSchedulerConfig(true, 1L, true),
                SessionState.empty(),
                List.of(),
                List.of());
    }

    private static Clock clock() {
        return Clock.fixed(Instant.ofEpochMilli(1000L), ZoneOffset.UTC);
    }

    @FunctionalInterface
    private interface FlowSupplier {
        List<dev.nquangit.corestra.flow.FlowDefinition> get();
    }

    private static final class QueuedExecutor extends AbstractExecutorService {
        final List<Runnable> tasks = new ArrayList<>();
        boolean reject;
        boolean shutdown;
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown(); return List.copyOf(tasks); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
        @Override public void execute(Runnable task) {
            if (reject || shutdown) throw new RejectedExecutionException("queue full or closed");
            tasks.add(task);
        }
    }

    private static final class RecordingScheduledExecutorService extends ScheduledThreadPoolExecutor {
        private final ArrayList<Runnable> scheduledTasks = new ArrayList<>();
        private final ArrayList<Long> scheduledDelaysSeconds = new ArrayList<>();
        private final ArrayList<Runnable> periodicTasks = new ArrayList<>();
        private final ArrayList<Long> periodicInitialDelaysSeconds = new ArrayList<>();
        private final ArrayList<Long> periodicDelaysSeconds = new ArrayList<>();
        private int shutdownNowCalls;

        private RecordingScheduledExecutorService() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            scheduledTasks.add(command);
            scheduledDelaysSeconds.add(unit.toSeconds(delay));
            return new CompletedScheduledFuture();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command,
                long initialDelay,
                long delay,
                TimeUnit unit) {
            periodicTasks.add(command);
            periodicInitialDelaysSeconds.add(unit.toSeconds(initialDelay));
            periodicDelaysSeconds.add(unit.toSeconds(delay));
            return new CompletedScheduledFuture();
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdownNowCalls++;
            return super.shutdownNow();
        }
    }

    private static final class CompletedScheduledFuture implements ScheduledFuture<Object> {
        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Object get() throws InterruptedException, ExecutionException {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return null;
        }
    }
}
