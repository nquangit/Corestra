package dev.nquangit.corestra.session;

import dev.nquangit.corestra.access.auth.*;
import dev.nquangit.corestra.access.auth.runtime.*;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.flow.execution.TestFlowEngineFactory;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LinkedSessionKeepAliveTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(61_000), ZoneOffset.UTC);

    @Test
    void schedulerSnapshotShowsActorBackoffAndKeepAliveFlowWithoutSendingRequests() {
        Fixture f = new Fixture(CLOCK, 10);
        f.status = 500;
        ManualTimer timer = new ManualTimer();
        try (SessionSchedulerService service = new SessionSchedulerService(f.manager, CLOCK, timer, new DirectSessionExecutor())) {
            service.start();
            timer.tick.run();
            SessionScheduledJob job = service.snapshot().jobs().stream()
                    .filter(row -> row.profileId().equals("first")).findFirst().orElseThrow();
            assertEquals("Alice", job.actorName());
            assertEquals("Keep-alive", job.mode());
            assertEquals("keep", job.flowId());
            assertEquals(SessionScheduledJob.Status.BACKOFF, job.status());
            assertEquals(91_000, job.nextRunAtEpochMillis(), "Auth backoff overrides the shorter scheduler interval");
            assertEquals("SKIPPED", job.lastOutcome());
            assertFalse(service.snapshot().toString().contains("old-token"));
            assertEquals(1, f.runs.size());
        }
    }

    @Test
    void tenSecondSchedulerMaintainsSharedActorOncePerInterval() {
        MutableClock clock = new MutableClock();
        Fixture f = new Fixture(clock, 10L);
        ManualTimer timer = new ManualTimer();
        try (SessionSchedulerService scheduler = new SessionSchedulerService(
                f.manager, clock, timer, new DirectSessionExecutor())) {
            scheduler.start();
            for (long now = 2_000; now <= 41_000; now += 1_000) {
                clock.now = now;
                timer.tick.run();
                int expectedRuns = (int) ((now - 1_000) / 10_000);
                assertEquals(expectedRuns, f.runs.size(), "Keep-alive cadence at " + now);
            }
            assertEquals(List.of(AuthFlowPurpose.KEEP_ALIVE, AuthFlowPurpose.KEEP_ALIVE,
                    AuthFlowPurpose.KEEP_ALIVE, AuthFlowPurpose.KEEP_ALIVE), f.runs);
        }
    }

    @Test
    void queuedBindingsForSameActorDoNotSendDuplicateKeepAlive() {
        Fixture f = new Fixture();
        ManualTimer timer = new ManualTimer();
        List<Runnable> queue = new ArrayList<>();
        var worker = new java.util.concurrent.AbstractExecutorService() {
            boolean closed;
            @Override public void shutdown() { closed = true; }
            @Override public List<Runnable> shutdownNow() { shutdown(); return List.copyOf(queue); }
            @Override public boolean isShutdown() { return closed; }
            @Override public boolean isTerminated() { return closed; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return closed; }
            @Override public void execute(Runnable task) { queue.add(task); }
        };
        try (SessionSchedulerService scheduler = new SessionSchedulerService(f.manager, CLOCK, timer, worker)) {
            scheduler.start();
            timer.tick.run();
            timer.tick.run();
            assertEquals(1, queue.size(), "Bindings for one actor share scheduler admission");
            queue.removeFirst().run();
            timer.tick.run();
            while (!queue.isEmpty()) queue.removeFirst().run();
            assertEquals(List.of(AuthFlowPurpose.KEEP_ALIVE), f.runs);
        }
    }

    @Test
    void successfulKeepAliveDoesNotInvalidatePendingResponseOrRunLogin() {
        Fixture f = new Fixture();
        SessionProfile binding = f.repository.profile("first").orElseThrow();
        SessionInjectionPlan before = f.manager.prepareInjectionRules(binding, context());
        f.manager.refreshScheduledProfile(binding.id());
        SessionInjectionPlan after = f.manager.prepareInjectionRules(binding, context());
        assertEquals(before.authenticationGeneration(), after.authenticationGeneration());
        assertEquals(List.of(AuthFlowPurpose.KEEP_ALIVE), f.runs);
        assertEquals(61_000, f.repository.profile(binding.id()).orElseThrow().state().lastAuthenticatedAtEpochMillis());

        f.manager.applyExtractionRulesIfCurrent(binding.id(), before.authenticationGeneration(), context(),
                new HttpMessageSnapshot("GET /api HTTP/1.1", List.of(), ""),
                new HttpMessageSnapshot("HTTP/1.1 200 OK", List.of("X-Token: rotated"), ""));
        assertEquals("rotated", f.access.authProfile("alice").orElseThrow().state().variables().get("token"));
    }

    @Test
    void bindingsForSameActorDoNotDuplicateScheduledKeepAlive() {
        Fixture f = new Fixture();
        f.manager.refreshScheduledProfile("first");
        f.manager.refreshScheduledProfile("second");
        assertEquals(List.of(AuthFlowPurpose.KEEP_ALIVE), f.runs);
        assertEquals(61_000, f.repository.profile("second").orElseThrow().state().lastAuthenticatedAtEpochMillis());
    }

    @Test
    void transientKeepAliveDoesNotPublishFreshTtlOrInvalidatePendingResponses() {
        Fixture f = new Fixture();
        f.status = 500;
        SessionProfile binding = f.repository.profile("first").orElseThrow();
        SessionInjectionPlan before = f.manager.prepareInjectionRules(binding, context());
        f.manager.refreshScheduledProfile(binding.id());
        assertEquals(1_000, f.repository.profile(binding.id()).orElseThrow().state().lastAuthenticatedAtEpochMillis());
        assertEquals(before.authenticationGeneration(), f.manager.prepareInjectionRules(binding, context()).authenticationGeneration());
        f.manager.refreshScheduledProfile(binding.id());
        assertEquals(1, f.runs.size(), "Repeated ticks must respect actor backoff");
    }

    @Test
    void confirmedExpiryRunsLoginAndInvalidatesOldResponses() {
        Fixture f = new Fixture();
        f.status = 401;
        SessionProfile binding = f.repository.profile("first").orElseThrow();
        SessionInjectionPlan before = f.manager.prepareInjectionRules(binding, context());
        f.manager.refreshScheduledProfile(binding.id());
        assertEquals(List.of(AuthFlowPurpose.KEEP_ALIVE, AuthFlowPurpose.LOGIN), f.runs);
        assertTrue(f.manager.prepareInjectionRules(binding, context()).authenticationGeneration() > before.authenticationGeneration());
        f.manager.applyExtractionRulesIfCurrent(binding.id(), before.authenticationGeneration(), context(),
                new HttpMessageSnapshot("GET /api HTTP/1.1", List.of(), ""),
                new HttpMessageSnapshot("HTTP/1.1 200 OK", List.of("X-Token: stale"), ""));
        assertEquals("new-token", f.access.authProfile("alice").orElseThrow().state().variables().get("token"));
    }

    private static SessionTrafficContext context() {
        return new SessionTrafficContext("PROXY", true, "GET", "example.test", "/api", List.of(), "");
    }

    private static final class Fixture {
        final SessionProfileRepository repository = new SessionProfileRepository();
        final InMemoryAccessRepository access = new InMemoryAccessRepository();
        final List<AuthFlowPurpose> runs = new ArrayList<>();
        final SessionManager manager;
        int status = 200;

        Fixture() {
            this(CLOCK, 60L);
        }

        Fixture(Clock clock, long interval) {
            AuthKeepAliveConfig config = new AuthKeepAliveConfig("keep", List.of(
                    new AuthSuccessRule("success", "Alive", true, AuthRuleType.BODY_CONTAINS, "alive", "")), List.of(
                    new AuthFailureRule("expired", "Expired", true, AuthRuleType.STATUS_CODE, "401", "")));
            access.saveAuthProvider(new AuthProvider("provider", "Provider", "", true, "login", "", "",
                    List.of(), List.of(), List.of(), AuthFallbackPolicy.LOGIN_ONLY, AuthRetryPolicy.NO_RETRY, 180L, config));
            access.saveAuthProfile(new AuthProfile("alice", "Alice", "USER", "provider", Map.of(),
                    new AuthState(Map.of("token", "old-token"), Map.of(), Map.of(), 1_000_000, 1_000), true));
            for (String id : List.of("first", "second")) {
                repository.saveProfile(new SessionProfile(id, id, true, false, SessionScope.custom(List.of()),
                        "alice", SessionAuthConfig.defaults(), new SessionSchedulerConfig(true, interval, false),
                        new SessionState(Map.of(), 1_000, 1_000_000, 1_000), List.of(), List.of(
                        new SessionExtractionRule("extract", true, ExtractRule.SourceSide.RESPONSE,
                                ExtractRule.ExtractorType.HEADER, "X-Token", "token", List.of("PROXY")))));
            }
            AuthManager auth = new AuthManager(access, (flowId, actor, provider, env, purpose, mode) -> {
                runs.add(purpose);
                return purpose == AuthFlowPurpose.LOGIN
                        ? AuthFlowRunResult.executed(true, "ok", 200, Map.of("token", "new-token"), "", List.of())
                        : AuthFlowRunResult.executed(true, "response", status, Map.of(),
                        "HTTP/1.1 " + status + "\r\n\r\nalive", List.of());
            }, new AuthRuleEvaluator(), clock);
            manager = new SessionManager(repository, List::of,
                    TestFlowEngineFactory.withHttpClient(req -> { throw new AssertionError("No standalone login"); }),
                    new ExtractionService(), clock, access, auth, new AuthInjectionRuleRenderer());
        }
    }

    private static final class MutableClock extends Clock {
        long now = 1_000;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }

    private static final class ManualTimer extends ScheduledThreadPoolExecutor {
        Runnable tick;
        ManualTimer() { super(1); }
        @Override public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) { return null; }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable task, long initialDelay, long period, TimeUnit unit) {
            tick = task;
            return null;
        }
    }
}
