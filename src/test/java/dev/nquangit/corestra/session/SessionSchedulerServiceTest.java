package dev.nquangit.corestra.session;

import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionSchedulerServiceTest {

    @Test
    void startSchedulesStartupAndPeriodicRefreshOnlyOnce() {
        RecordingScheduledExecutorService executor = new RecordingScheduledExecutorService();
        SessionSchedulerService service = new SessionSchedulerService(manager(new SessionProfileRepository()), clock(), executor);

        service.start();
        service.start();

        assertEquals(1, executor.scheduledTasks.size());
        assertEquals(5L, executor.scheduledDelaysSeconds.getFirst());
        assertEquals(1, executor.periodicTasks.size());
        assertEquals(30L, executor.periodicInitialDelaysSeconds.getFirst());
        assertEquals(30L, executor.periodicDelaysSeconds.getFirst());
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
        SessionSchedulerService service = new SessionSchedulerService(manager, clock(), executor);
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
        SessionSchedulerService service = new SessionSchedulerService(manager, clock(), executor);
        service.start();

        executor.periodicTasks.getFirst().run();

        assertEquals(1, flowLookups.get());
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
        public ScheduledFuture<?> scheduleWithFixedDelay(
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
