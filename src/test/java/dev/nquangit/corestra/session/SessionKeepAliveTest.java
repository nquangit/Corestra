package dev.nquangit.corestra.session;

import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.execution.TestFlowEngineFactory;
import dev.nquangit.corestra.http.extract.ExtractionService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionKeepAliveTest {
    private final MutableClock clock = new MutableClock();
    private final AtomicInteger sends = new AtomicInteger();
    private final SessionProfileRepository repository = new SessionProfileRepository();
    private final SessionTrafficContext traffic = new SessionTrafficContext(
            "SCANNER", true, "GET", "example.com", "/api", List.of(), "");

    @Test
    void successfulLoginWithoutExtractionIsReusedUntilTtlExpiresEvenWithStaleProfile() {
        SessionManager manager = manager(180, false);
        SessionProfile stale = repository.profile("keep-alive").orElseThrow();
        SessionProfile authenticated = manager.ensureAuthenticated(stale, traffic);
        assertEquals(1, sends.get());
        assertTrue(authenticated.state().values().isEmpty());
        assertEquals(1000, authenticated.state().lastAuthenticatedAtEpochMillis());
        assertEquals(181000, authenticated.state().expiresAtEpochMillis());
        for (int i = 0; i < 20; i++) {
            manager.ensureAuthenticated(stale, traffic);
        }
        clock.now = 180999;
        manager.ensureAuthenticated(repository.profile("keep-alive").orElseThrow(), traffic);
        assertEquals(1, sends.get());
        clock.now = 181000;
        manager.ensureAuthenticated(repository.profile("keep-alive").orElseThrow(), traffic);
        assertEquals(2, sends.get());
    }

    @Test
    void scheduledLoginWithoutExtractionWaitsThreeMinutesDespiteTrafficAndSchedulerTicks() {
        SessionManager manager = manager(0, false);
        RecordingExecutor executor = new RecordingExecutor();
        try (SessionSchedulerService scheduler = new SessionSchedulerService(manager, clock, executor, new DirectSessionExecutor())) {
            scheduler.start();
            executor.tick.run();
            assertEquals(1, sends.get());
            for (long now = 31000; now < 181000; now += 30000) {
                clock.now = now;
                executor.tick.run();
                manager.ensureAuthenticated(repository.profile("keep-alive").orElseThrow(), traffic);
            }
            assertEquals(1, sends.get());
            clock.now = 181000;
            executor.tick.run();
            assertEquals(2, sends.get());
            manager.ensureAuthenticated(repository.profile("keep-alive").orElseThrow(), traffic);
            assertEquals(2, sends.get());
        }
    }

    @Test
    void failedLoginDoesNotRefreshAuthenticationEvenWhenExistingValuesArePresent() {
        SessionManager manager = manager(180, true);
        SessionState expired = new SessionState(Map.of("token", "old"), 100, 500, 100);
        repository.saveProfile(repository.profile("keep-alive").orElseThrow().withState(expired));
        SessionProfile result = manager.forceAuthenticate("keep-alive").orElseThrow();
        assertEquals(1, sends.get());
        assertEquals(expired, result.state());
        assertTrue(result.state().missingOrExpired(clock.millis()));
        assertFalse(SessionGuard.isActive());
    }

    @Test
    void clearingSuccessfulValuelessSessionAllowsAuthenticationAgain() {
        SessionManager manager = manager(0, false);
        manager.forceAuthenticate("keep-alive");
        manager.clearState("keep-alive");
        manager.ensureAuthenticated(repository.profile("keep-alive").orElseThrow(), traffic);
        assertEquals(2, sends.get());
        assertFalse(repository.profile("keep-alive").orElseThrow().state().missingOrExpired(clock.millis()));
    }

    @Test
    void extractedValuesWithoutAuthTimestampRemainUsable() {
        assertFalse(SessionState.empty().withExtractedValues(Map.of("token", "abc"), 1000)
                .missingOrExpired(2000));
        assertTrue(SessionState.empty().missingOrExpired(2000));
    }

    @Test
    void emptyLoginFlowDoesNotMarkSessionAuthenticated() {
        manager(0, false);
        FlowDefinition emptyFlow = new FlowDefinition("login", "Login", "", true, List.of(), null, null, null);
        SessionManager manager = new SessionManager(repository, () -> List.of(emptyFlow),
                TestFlowEngineFactory.withHttpClient(request -> {
                    throw new AssertionError("Empty flow must not send HTTP");
                }), new ExtractionService(), clock);

        assertEquals(SessionState.empty(), manager.forceAuthenticate("keep-alive").orElseThrow().state());
    }

    @Test
    void successfulValuelessStateSurvivesSerialization() throws Exception {
        SessionManager manager = manager(180, false);
        SessionState state = manager.forceAuthenticate("keep-alive").orElseThrow().state();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        SessionState restored = mapper.readValue(mapper.writeValueAsString(state), SessionState.class);

        assertEquals(state, restored);
        assertFalse(restored.missingOrExpired(180999));
        assertTrue(restored.missingOrExpired(181000));
    }

    @Test
    void disabledLoginFlowIsNotExecutedBySessionAuthentication() {
        manager(180, false);
        FlowStep step = new FlowStep("step", "Step", true, "",
                new StoredRequestTemplate("GET", "https", "example.com", 443, "/login", List.of(), ""),
                List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
        FlowDefinition disabled = new FlowDefinition("login", "Login", "", false, List.of(step), null, null, null);
        SessionManager manager = new SessionManager(repository, () -> List.of(disabled),
                TestFlowEngineFactory.withHttpClient(request -> {
                    sends.incrementAndGet();
                    throw new IllegalStateException("Disabled flow must not send");
                }), new ExtractionService(), clock);

        manager.forceAuthenticate("keep-alive");
        assertEquals(0, sends.get());
        assertTrue(repository.profile("keep-alive").orElseThrow().state().missingOrExpired(clock.millis()));
    }

    @Test
    void schedulerRechecksDueTimeAfterWaitingForConcurrentAuthentication() throws Exception {
        SessionManager manager = manager(0, false);
        manager.forceAuthenticate("keep-alive");
        manager.clearState("keep-alive");
        var field = SessionManager.class.getDeclaredField("locks");
        field.setAccessible(true);
        var locks = (java.util.Map<?, ?>) field.get(manager);
        var lock = (java.util.concurrent.locks.ReentrantLock) locks.get("keep-alive");
        RecordingExecutor executor = new RecordingExecutor();
        try (SessionSchedulerService scheduler = new SessionSchedulerService(manager, clock, executor, new DirectSessionExecutor())) {
            scheduler.start();
            Thread tick = new Thread(executor.tick, "scheduler-race-test");
            lock.lock();
            try {
                tick.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!lock.hasQueuedThread(tick) && tick.isAlive() && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertTrue(lock.hasQueuedThread(tick), "Scheduler should be waiting for authentication lock");
                // Simulate the in-flight traffic login completing while scheduler is queued.
                manager.forceAuthenticate("keep-alive");
            } finally {
                lock.unlock();
                tick.join(3000);
                if (tick.isAlive()) tick.interrupt();
            }
            assertFalse(tick.isAlive());
            assertEquals(2, sends.get(), "Scheduler must not repeat the just-completed login");
        }
    }

    private SessionManager manager(long ttl, boolean fail) {
        repository.saveProfile(new SessionProfile("keep-alive", "Keep alive", true, false,
                SessionScope.custom(List.of()), new SessionAuthConfig("login", ttl, List.of()),
                new SessionSchedulerConfig(true, 180, false), SessionState.empty(), List.of(), List.of()));
        FlowStep step = new FlowStep("login-step", "Login", true, "",
                new StoredRequestTemplate("POST", "https", "example.com", 443, "/login",
                        List.of("Host: example.com"), "deviceid=fixed&hash=fixed"),
                List.of(), List.of(), List.of(), FlowStep.StepErrorStrategy.FAIL_FLOW);
        FlowDefinition flow = new FlowDefinition("login", "Login", "", true, List.of(step), null, null, null);
        return new SessionManager(repository, () -> List.of(flow),
                TestFlowEngineFactory.withHttpClient(request -> {
                    sends.incrementAndGet();
                    if (fail) {
                        throw new IllegalStateException("Transport failed");
                    }
                    return (HttpResponse) Proxy.newProxyInstance(HttpResponse.class.getClassLoader(),
                            new Class<?>[]{HttpResponse.class}, (proxy, method, args) -> switch (method.getName()) {
                                case "statusCode" -> (short) 200;
                                case "reasonPhrase" -> "OK";
                                case "httpVersion" -> "HTTP/1.1";
                                case "headers" -> List.of();
                                case "bodyToString" -> "";
                                case "toString" -> "HTTP/1.1 200 OK\r\n\r\n";
                                default -> throw new UnsupportedOperationException(method.getName());
                            });
                }), new ExtractionService(), clock);
    }

    private static final class MutableClock extends Clock {
        private long now = 1000;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }

    private static final class RecordingExecutor extends ScheduledThreadPoolExecutor {
        private Runnable tick;
        private RecordingExecutor() { super(1); }
        @Override public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) { return null; }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable task, long initialDelay, long delay, TimeUnit unit) {
            tick = task;
            return null;
        }
    }
}
