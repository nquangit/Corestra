package dev.nquangit.corestra.access.auth.runtime;

import dev.nquangit.corestra.access.auth.*;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.access.variable.RenderMode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AuthKeepAliveTest {
    private final MutableClock clock = new MutableClock();
    private final InMemoryAccessRepository repository = new InMemoryAccessRepository();
    private final List<String> runs = new ArrayList<>();
    private final AtomicReference<AuthFlowRunResult> response = new AtomicReference<>(result(200, "alive"));

    @Test
    void concurrentTargetWaitsWhenKeepAliveConfirmsExpiryAndAuthenticationIsRunning() throws Exception {
        setup(true);
        CountDownLatch loginStarted = new CountDownLatch(1);
        CountDownLatch finishLogin = new CountDownLatch(1);
        CountDownLatch targetStarted = new CountDownLatch(1);
        AuthManager manager = new AuthManager(repository, (flowId, actor, provider, env, purpose, mode) -> {
            if (purpose == AuthFlowPurpose.KEEP_ALIVE) return result(401, "expired");
            loginStarted.countDown();
            try { assertTrue(finishLogin.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return AuthFlowRunResult.executed(true, "ok", 200, Map.of("token", "new-token"), "", List.of());
        }, new AuthRuleEvaluator(), clock);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var maintenance = executor.submit(() -> maintain(manager, "alice"));
            assertTrue(loginStarted.await(5, TimeUnit.SECONDS));
            var target = executor.submit(() -> {
                targetStarted.countDown();
                return manager.ensureAuth("alice", "", Map.of(), RenderMode.STRICT);
            });
            assertTrue(targetStarted.await(5, TimeUnit.SECONDS));
            var field = AuthManager.class.getDeclaredField("locksByProfileId");
            field.setAccessible(true);
            var lock = (java.util.concurrent.locks.ReentrantLock) ((Map<?, ?>) field.get(manager)).get("alice");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!lock.hasQueuedThreads() && System.nanoTime() < deadline) Thread.onSpinWait();
            assertTrue(lock.hasQueuedThreads());
            assertFalse(target.isDone(), "Fresh local TTL must not bypass in-flight re-authentication");
            finishLogin.countDown();
            assertEquals(AuthEnsureStatus.LOGGED_IN, maintenance.get(5, TimeUnit.SECONDS).status());
            assertEquals("new-token", target.get(5, TimeUnit.SECONDS).updatedProfile().orElseThrow()
                    .state().variables().get("token"));
        } finally {
            finishLogin.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedInitialAuthenticationCannotBecomeSuccessfulDuringBackoff() {
        setup(false);
        AuthManager manager = new AuthManager(repository, (flowId, actor, p, env, purpose, mode) -> {
            runs.add(actor.id() + ":" + purpose);
            return AuthFlowRunResult.executed(false, "timeout", 0, Map.of(), "", List.of());
        }, new AuthRuleEvaluator(), clock);
        assertTrue(maintain(manager, "alice").failed());
        assertTrue(manager.ensureAuth("alice", "", Map.of(), RenderMode.STRICT).failed());
        assertEquals(1, runs.size());
        assertEquals(AuthState.empty(), repository.authProfile("alice").orElseThrow().state());
    }

    @Test
    void failedReauthenticationCannotAdvertiseOldStateAsValidDuringBackoff() {
        setup(true);
        AuthManager manager = new AuthManager(repository, (flowId, actor, p, env, purpose, mode) -> {
            runs.add(actor.id() + ":" + purpose);
            return purpose == AuthFlowPurpose.KEEP_ALIVE ? result(401, "expired")
                    : AuthFlowRunResult.executed(false, "login timeout", 0, Map.of(), "", List.of());
        }, new AuthRuleEvaluator(), clock);
        assertTrue(maintain(manager, "alice").failed());
        assertTrue(manager.ensureAuth("alice", "", Map.of(), RenderMode.STRICT).failed(),
                "Fresh local TTL cannot override confirmed expiry and failed authentication");
        assertEquals(List.of("alice:KEEP_ALIVE", "alice:LOGIN"), runs);
    }

    @Test
    void scheduledKeepAlivePreservesSessionAndExtendsTtlWithoutLogin() {
        AuthManager manager = setup(true);
        AuthState before = repository.authProfile("alice").orElseThrow().state();
        assertEquals(AuthEnsureStatus.KEPT_ALIVE, maintain(manager, "alice").status());
        AuthState after = repository.authProfile("alice").orElseThrow().state();
        assertEquals(before.variables(), after.variables());
        assertEquals(before.cookies(), after.cookies());
        assertEquals(181_000, after.expiresAtEpochMillis());
        assertEquals(List.of("alice:KEEP_ALIVE"), runs);
    }

    @Test
    void initialAuthenticationUsesExistingLoginPolicyWithoutKeepAlive() {
        AuthManager manager = setup(false);
        assertEquals(AuthEnsureStatus.LOGGED_IN, maintain(manager, "alice").status());
        assertEquals(List.of("alice:LOGIN"), runs);
    }

    @Test
    void localTtlExpiryChecksExistingSessionBeforeLoggingIn() {
        AuthManager manager = setup(true);
        clock.now = 50_000;
        assertEquals(AuthEnsureStatus.KEPT_ALIVE,
                manager.ensureAuth("alice", "", Map.of(), RenderMode.STRICT).status());
        assertEquals(List.of("alice:KEEP_ALIVE"), runs);
    }

    @Test
    void onlyExplicitExpiredResponseFallsBackToAuthentication() {
        AuthManager manager = setup(true);
        response.set(result(401, "expired"));
        assertEquals(AuthEnsureStatus.LOGGED_IN, maintain(manager, "alice").status());
        assertEquals(List.of("alice:KEEP_ALIVE", "alice:LOGIN"), runs);
    }

    @Test
    void transientFailureRetainsStateAndRetriesWithBackoff() {
        AuthManager manager = setup(true);
        AuthState before = repository.authProfile("alice").orElseThrow().state();
        response.set(AuthFlowRunResult.executed(false, "timeout", 0, Map.of(), "", List.of()));
        assertEquals(AuthEnsureStatus.DEFERRED, maintain(manager, "alice").status());
        assertEquals(AuthEnsureStatus.DEFERRED, maintain(manager, "alice").status());
        assertEquals(before, repository.authProfile("alice").orElseThrow().state());
        assertEquals(1, runs.size());
        clock.now += 30_000;
        maintain(manager, "alice");
        assertEquals(2, runs.size());
        clock.now += 30_000;
        maintain(manager, "alice");
        assertEquals(2, runs.size(), "Second failure backs off for 60 seconds");
        clock.now += 30_000;
        response.set(result(200, "alive"));
        assertEquals(AuthEnsureStatus.KEPT_ALIVE, maintain(manager, "alice").status());
        assertEquals(3, runs.size());
    }

    @Test
    void unknownRedirectOrServerErrorDoesNotTriggerLogin() {
        for (int code : List.of(302, 429, 500)) {
            AuthManager manager = setup(true);
            response.set(result(code, "unknown"));
            assertEquals(AuthEnsureStatus.DEFERRED, maintain(manager, "alice").status());
        }
        assertTrue(runs.stream().allMatch(s -> s.endsWith("KEEP_ALIVE")));
    }

    @Test
    void actorsShareFlowButHaveSeparateStateAndBackoff() {
        AuthManager manager = setup(true);
        saveActor("bob", true);
        response.set(result(500, "unavailable"));
        maintain(manager, "alice");
        response.set(result(200, "alive"));
        assertEquals(AuthEnsureStatus.KEPT_ALIVE, maintain(manager, "bob").status());
        assertEquals("bob-id", repository.authProfile("bob").orElseThrow().state().variables().get("JSESSIONID"));
        assertEquals(AuthEnsureStatus.DEFERRED, maintain(manager, "alice").status());
        assertEquals(List.of("alice:KEEP_ALIVE", "bob:KEEP_ALIVE"), runs);
    }

    @Test
    void manualForceStillUsesOriginalAuthenticationPolicy() {
        AuthManager manager = setup(true);
        assertEquals(AuthEnsureStatus.LOGGED_IN,
                manager.forceAuth("alice", "", Map.of(), RenderMode.STRICT).status());
        assertEquals(List.of("alice:LOGIN"), runs);
    }

    @Test
    void missingSuccessEvidenceDoesNotExtendTtl() {
        AuthManager manager = setup(true);
        AuthState before = repository.authProfile("alice").orElseThrow().state();
        response.set(result(200, "login page"));
        assertEquals(AuthEnsureStatus.DEFERRED, maintain(manager, "alice").status());
        assertEquals(before, repository.authProfile("alice").orElseThrow().state());
    }

    private AuthManager setup(boolean authenticated) {
        AuthKeepAliveConfig keepAlive = new AuthKeepAliveConfig("keep-alive", List.of(
                new AuthSuccessRule("alive", "Alive", true, AuthRuleType.BODY_CONTAINS, "alive", "")), List.of(
                new AuthFailureRule("expired", "Expired", true, AuthRuleType.STATUS_CODE, "401", "")));
        repository.saveAuthProvider(new AuthProvider("provider", "Provider", "", true, "login", "", "",
                List.of(), List.of(), List.of(), AuthFallbackPolicy.LOGIN_ONLY, AuthRetryPolicy.NO_RETRY, 180L, keepAlive));
        saveActor("alice", authenticated);
        return new AuthManager(repository, (flowId, actor, provider, env, purpose, mode) -> {
            runs.add(actor.id() + ":" + purpose);
            if (purpose == AuthFlowPurpose.LOGIN) {
                return AuthFlowRunResult.executed(true, "ok", 200,
                        Map.of("JSESSIONID", actor.id() + "-new-id", "token", actor.id() + "-new-token"),
                        "HTTP/1.1 200 OK\r\n\r\nalive", List.of());
            }
            assertEquals(actor.id() + "-id", actor.state().variables().get("JSESSIONID"));
            return response.get();
        }, new AuthRuleEvaluator(), clock);
    }

    private void saveActor(String id, boolean authenticated) {
        repository.saveAuthProfile(new AuthProfile(id, id, "USER", "provider", Map.of("username", id),
                authenticated ? new AuthState(Map.of("JSESSIONID", id + "-id", "token", id + "-token"),
                        Map.of(), Map.of(), 5_000, 500) : AuthState.empty(), true));
    }

    private static AuthEnsureResult maintain(AuthManager manager, String actor) {
        return manager.maintainAuth(actor, "", Map.of(), RenderMode.STRICT);
    }

    private static AuthFlowRunResult result(int status, String body) {
        return AuthFlowRunResult.executed(true, "response", status, Map.of(),
                "HTTP/1.1 " + status + "\r\n\r\n" + body, List.of());
    }

    private static final class MutableClock extends Clock {
        long now = 1_000;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }
}
