package dev.nquangit.corestra.access.auth.runtime;

import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.access.variable.RenderMode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthManagerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1000L), ZoneOffset.UTC);

    @Test
    void manualProviderReturnsManualWithoutExecutingFlows() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        repository.saveAuthProfile(AuthProfile.manual("auth-1", "Manual", "tester"));
        RecordingRunner runner = new RecordingRunner();

        AuthEnsureResult result = manager(repository, runner).ensureAuth("auth-1", "", Map.of(), RenderMode.STRICT);

        assertEquals(AuthEnsureStatus.MANUAL, result.status());
        assertEquals(0, runner.totalCount());
    }

    @Test
    void loginOnlySuccessUpdatesAuthStateAndRepository() {
        InMemoryAccessRepository repository = repository(AuthFallbackPolicy.LOGIN_ONLY);
        RecordingRunner runner = new RecordingRunner()
                .then(AuthFlowPurpose.LOGIN, success("accessToken", "token-login", "expiresAt", "5000"));

        AuthEnsureResult result = manager(repository, runner).ensureAuth("auth-1", "env-dev", Map.of("baseUrl", "https://example.test"), RenderMode.STRICT);

        assertEquals(AuthEnsureStatus.LOGGED_IN, result.status());
        AuthProfile updated = result.updatedProfile().orElseThrow();
        assertEquals("token-login", updated.state().variables().get("accessToken"));
        assertEquals(5000L, updated.state().expiresAtEpochMillis());
        assertEquals(1000L, updated.state().lastUpdatedAtEpochMillis());
        assertEquals("token-login", repository.authProfile("auth-1").orElseThrow().state().variables().get("accessToken"));
        assertEquals(1, runner.count(AuthFlowPurpose.LOGIN));
    }

    @Test
    void relativeExpiryAndProviderFallbackTtlPreventPerTargetLogin() {
        InMemoryAccessRepository relativeRepository = repository(AuthFallbackPolicy.LOGIN_ONLY);
        RecordingRunner relativeRunner = new RecordingRunner()
                .then(AuthFlowPurpose.LOGIN, success("accessToken", "token", "expires_in", "60"));
        AuthEnsureResult relative = manager(relativeRepository, relativeRunner)
                .ensureAuth("auth-1", "", Map.of(), RenderMode.STRICT);
        assertEquals(61_000L, relative.updatedProfile().orElseThrow().state().expiresAtEpochMillis());

        InMemoryAccessRepository fallbackRepository = repository(AuthFallbackPolicy.LOGIN_ONLY);
        RecordingRunner fallbackRunner = new RecordingRunner()
                .then(AuthFlowPurpose.LOGIN, success("accessToken", "token"));
        AuthManager fallbackManager = manager(fallbackRepository, fallbackRunner);
        AuthEnsureResult first = fallbackManager.ensureAuth("auth-1", "", Map.of(), RenderMode.STRICT);
        AuthEnsureResult second = fallbackManager.ensureAuth("auth-1", "", Map.of(), RenderMode.STRICT);
        assertEquals(301_000L, first.updatedProfile().orElseThrow().state().expiresAtEpochMillis());
        assertEquals(AuthEnsureStatus.VALID, second.status());
        assertEquals(1, fallbackRunner.count(AuthFlowPurpose.LOGIN));
    }

    @Test
    void refreshThenLoginRefreshSuccessSkipsLogin() {
        InMemoryAccessRepository repository = repository(AuthFallbackPolicy.REFRESH_THEN_LOGIN);
        RecordingRunner runner = new RecordingRunner()
                .then(AuthFlowPurpose.REFRESH, success("accessToken", "token-refresh", "expiresAt", "5000"))
                .then(AuthFlowPurpose.LOGIN, success("accessToken", "token-login"));

        AuthEnsureResult result = manager(repository, runner).ensureAuth("auth-1", "", Map.of(), RenderMode.STRICT);

        assertEquals(AuthEnsureStatus.REFRESHED, result.status());
        assertEquals("token-refresh", result.updatedProfile().orElseThrow().state().variables().get("accessToken"));
        assertEquals(1, runner.count(AuthFlowPurpose.REFRESH));
        assertEquals(0, runner.count(AuthFlowPurpose.LOGIN));
    }

    @Test
    void refreshThenLoginRefreshFailureFallsBackToLogin() {
        InMemoryAccessRepository repository = repository(AuthFallbackPolicy.REFRESH_THEN_LOGIN);
        RecordingRunner runner = new RecordingRunner()
                .then(AuthFlowPurpose.REFRESH, failed())
                .then(AuthFlowPurpose.LOGIN, success("accessToken", "token-login"));

        AuthEnsureResult result = manager(repository, runner).ensureAuth("auth-1", "", Map.of(), RenderMode.STRICT);

        assertEquals(AuthEnsureStatus.LOGGED_IN, result.status());
        assertEquals("token-login", result.updatedProfile().orElseThrow().state().variables().get("accessToken"));
        assertEquals(1, runner.count(AuthFlowPurpose.REFRESH));
        assertEquals(1, runner.count(AuthFlowPurpose.LOGIN));
    }

    @Test
    void validateSuccessSkipsRefreshAndLogin() {
        InMemoryAccessRepository repository = repository(AuthFallbackPolicy.VALIDATE_THEN_REFRESH_THEN_LOGIN);
        RecordingRunner runner = new RecordingRunner()
                .then(AuthFlowPurpose.VALIDATE, AuthFlowRunResult.executed(true, "valid", 200, Map.of(), "HTTP/1.1 200 OK\n\n{}", List.of()))
                .then(AuthFlowPurpose.REFRESH, success("accessToken", "token-refresh"))
                .then(AuthFlowPurpose.LOGIN, success("accessToken", "token-login"));

        AuthEnsureResult result = manager(repository, runner).ensureAuth("auth-1", "", Map.of(), RenderMode.STRICT);

        assertEquals(AuthEnsureStatus.VALID, result.status());
        assertEquals(1, runner.count(AuthFlowPurpose.VALIDATE));
        assertEquals(0, runner.count(AuthFlowPurpose.REFRESH));
        assertEquals(0, runner.count(AuthFlowPurpose.LOGIN));
    }

    @Test
    void retryAuthRefreshPolicyForcesRefreshEvenWhenStateIsFresh() {
        InMemoryAccessRepository repository = repository(AuthFallbackPolicy.LOGIN_ONLY);
        repository.saveAuthProfile(profileWithState("auth-1", "provider-1",
                new AuthState(Map.of("accessToken", "token-old"), Map.of(), Map.of(), 5000L, 1000L)));
        RecordingRunner runner = new RecordingRunner()
                .then(AuthFlowPurpose.REFRESH, success("accessToken", "token-refresh", "expiresAt", "6000"));

        AuthEnsureResult result = manager(repository, runner).retryAuth(
                "auth-1",
                "",
                Map.of(),
                RenderMode.STRICT,
                AuthRetryPolicy.REFRESH_AND_RETRY_ONCE);

        assertEquals(AuthEnsureStatus.REFRESHED, result.status());
        assertEquals("token-refresh", result.updatedProfile().orElseThrow().state().variables().get("accessToken"));
        assertEquals(1, runner.count(AuthFlowPurpose.REFRESH));
        assertEquals(0, runner.count(AuthFlowPurpose.LOGIN));
    }

    @Test
    void retryAuthRefreshThenLoginFallsBackToLogin() {
        InMemoryAccessRepository repository = repository(AuthFallbackPolicy.LOGIN_ONLY);
        RecordingRunner runner = new RecordingRunner()
                .then(AuthFlowPurpose.REFRESH, failed())
                .then(AuthFlowPurpose.LOGIN, success("accessToken", "token-login"));

        AuthEnsureResult result = manager(repository, runner).retryAuth(
                "auth-1",
                "",
                Map.of(),
                RenderMode.STRICT,
                AuthRetryPolicy.REFRESH_THEN_LOGIN_AND_RETRY_ONCE);

        assertEquals(AuthEnsureStatus.LOGGED_IN, result.status());
        assertEquals("token-login", result.updatedProfile().orElseThrow().state().variables().get("accessToken"));
        assertEquals(1, runner.count(AuthFlowPurpose.REFRESH));
        assertEquals(1, runner.count(AuthFlowPurpose.LOGIN));
    }

    @Test
    void missingProviderReturnsFailed() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        repository.saveAuthProfile(profile("auth-1", "missing-provider"));

        AuthEnsureResult result = manager(repository, new RecordingRunner()).ensureAuth("auth-1", "", Map.of(), RenderMode.STRICT);

        assertEquals(AuthEnsureStatus.FAILED, result.status());
        assertTrue(result.reason().contains("Auth provider not found"));
    }

    @Test
    void missingLoginFlowReturnsMeaningfulFailure() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        repository.saveAuthProvider(provider(AuthFallbackPolicy.LOGIN_ONLY, "", "", ""));
        repository.saveAuthProfile(profile("auth-1", "provider-1"));

        AuthEnsureResult result = manager(repository, new RecordingRunner()).ensureAuth("auth-1", "", Map.of(), RenderMode.STRICT);

        assertEquals(AuthEnsureStatus.FAILED, result.status());
        assertTrue(result.reason().contains("No login flow configured"));
    }

    @Test
    void failedRefreshAndFailedLoginReturnsFailed() {
        InMemoryAccessRepository repository = repository(AuthFallbackPolicy.REFRESH_THEN_LOGIN);
        RecordingRunner runner = new RecordingRunner()
                .then(AuthFlowPurpose.REFRESH, failed())
                .then(AuthFlowPurpose.LOGIN, failed());

        AuthEnsureResult result = manager(repository, runner).ensureAuth("auth-1", "", Map.of(), RenderMode.STRICT);

        assertEquals(AuthEnsureStatus.FAILED, result.status());
        assertEquals(1, runner.count(AuthFlowPurpose.REFRESH));
        assertEquals(1, runner.count(AuthFlowPurpose.LOGIN));
    }

    @Test
    void concurrentEnsureForSameProfileExecutesLoginOnceWhenStateBecomesFresh() throws Exception {
        InMemoryAccessRepository repository = repository(AuthFallbackPolicy.LOGIN_ONLY);
        RecordingRunner runner = new RecordingRunner()
                .then(AuthFlowPurpose.LOGIN, success("accessToken", "token-login", "expiresAt", "5000"));
        AuthManager manager = manager(repository, runner);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AuthEnsureResult> first = executor.submit(() -> manager.ensureAuth("auth-1", "", Map.of(), RenderMode.STRICT));
            Future<AuthEnsureResult> second = executor.submit(() -> manager.ensureAuth("auth-1", "", Map.of(), RenderMode.STRICT));

            AuthEnsureResult firstResult = first.get(3, TimeUnit.SECONDS);
            AuthEnsureResult secondResult = second.get(3, TimeUnit.SECONDS);

            assertEquals(1, runner.count(AuthFlowPurpose.LOGIN));
            assertTrue(List.of(AuthEnsureStatus.LOGGED_IN, AuthEnsureStatus.VALID).contains(firstResult.status()));
            assertTrue(List.of(AuthEnsureStatus.LOGGED_IN, AuthEnsureStatus.VALID).contains(secondResult.status()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentEnsureForDifferentProfilesDoesNotShareLock() throws Exception {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        repository.saveAuthProvider(provider(AuthFallbackPolicy.LOGIN_ONLY, "login-flow", "", ""));
        repository.saveAuthProfile(profile("auth-1", "provider-1"));
        repository.saveAuthProfile(profile("auth-2", "provider-1"));
        BlockingRunner runner = new BlockingRunner(success("accessToken", "token-login", "expiresAt", "5000"));
        AuthManager manager = manager(repository, runner);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AuthEnsureResult> first = executor.submit(() -> manager.ensureAuth("auth-1", "", Map.of(), RenderMode.STRICT));
            Future<AuthEnsureResult> second = executor.submit(() -> manager.ensureAuth("auth-2", "", Map.of(), RenderMode.STRICT));

            assertTrue(runner.awaitBothEntered());
            runner.release();

            assertEquals(AuthEnsureStatus.LOGGED_IN, first.get(3, TimeUnit.SECONDS).status());
            assertEquals(AuthEnsureStatus.LOGGED_IN, second.get(3, TimeUnit.SECONDS).status());
            assertEquals(2, runner.count());
        } finally {
            executor.shutdownNow();
        }
    }

    private static AuthManager manager(InMemoryAccessRepository repository, AuthFlowRunner runner) {
        return new AuthManager(repository, runner, new AuthRuleEvaluator(), CLOCK);
    }

    private static InMemoryAccessRepository repository(AuthFallbackPolicy policy) {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        repository.saveAuthProvider(provider(policy, "login-flow", "refresh-flow", "validate-flow"));
        repository.saveAuthProfile(profile("auth-1", "provider-1"));
        return repository;
    }

    private static AuthProfile profile(String id, String providerId) {
        return new AuthProfile(id, "Profile " + id, "tester", providerId, Map.of("username", "alice"),
                AuthState.empty(), true);
    }

    private static AuthProfile profileWithState(String id, String providerId, AuthState state) {
        return new AuthProfile(id, "Profile " + id, "tester", providerId, Map.of("username", "alice"),
                state, true);
    }

    private static AuthProvider provider(AuthFallbackPolicy policy, String loginFlow, String refreshFlow, String validateFlow) {
        return new AuthProvider(
                "provider-1",
                "Provider",
                "",
                true,
                loginFlow,
                refreshFlow,
                validateFlow,
                List.of(),
                List.of(),
                List.of(),
                policy,
                AuthRetryPolicy.NO_RETRY);
    }

    private static AuthFlowRunResult success(String... keyValues) {
        java.util.LinkedHashMap<String, String> variables = new java.util.LinkedHashMap<>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            variables.put(keyValues[index], keyValues[index + 1]);
        }
        return AuthFlowRunResult.executed(true, "flow success", 200, variables, "HTTP/1.1 200 OK\n\n{}", List.of());
    }

    private static AuthFlowRunResult failed() {
        return AuthFlowRunResult.executed(false, "flow failed", 500, Map.of(), "HTTP/1.1 500 Error\n\n{}", List.of());
    }

    private static final class RecordingRunner implements AuthFlowRunner {
        private final Map<AuthFlowPurpose, Queue<AuthFlowRunResult>> results = new EnumMap<>(AuthFlowPurpose.class);
        private final Map<AuthFlowPurpose, AtomicInteger> counts = new EnumMap<>(AuthFlowPurpose.class);

        RecordingRunner then(AuthFlowPurpose purpose, AuthFlowRunResult result) {
            results.computeIfAbsent(purpose, ignored -> new ArrayDeque<>()).add(result);
            return this;
        }

        @Override
        public synchronized AuthFlowRunResult runAuthFlow(
                String flowId,
                AuthProfile profile,
                AuthProvider provider,
                Map<String, String> env,
                AuthFlowPurpose purpose,
                RenderMode renderMode) {
            counts.computeIfAbsent(purpose, ignored -> new AtomicInteger()).incrementAndGet();
            Queue<AuthFlowRunResult> queue = results.get(purpose);
            if (flowId == null || flowId.isBlank()) {
                return AuthFlowRunResult.notExecuted("No " + purpose.name().toLowerCase() + " flow configured");
            }
            if (queue == null || queue.isEmpty()) {
                return AuthFlowRunResult.notExecuted("No fake result configured for " + purpose);
            }
            return queue.remove();
        }

        int count(AuthFlowPurpose purpose) {
            return Optional.ofNullable(counts.get(purpose)).map(AtomicInteger::get).orElse(0);
        }

        int totalCount() {
            return counts.values().stream().mapToInt(AtomicInteger::get).sum();
        }
    }

    private static final class BlockingRunner implements AuthFlowRunner {
        private final CountDownLatch entered = new CountDownLatch(2);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AuthFlowRunResult result;
        private final AtomicInteger count = new AtomicInteger();

        BlockingRunner(AuthFlowRunResult result) {
            this.result = result;
        }

        @Override
        public AuthFlowRunResult runAuthFlow(
                String flowId,
                AuthProfile profile,
                AuthProvider provider,
                Map<String, String> env,
                AuthFlowPurpose purpose,
                RenderMode renderMode) {
            count.incrementAndGet();
            entered.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return result;
        }

        boolean awaitBothEntered() throws InterruptedException {
            return entered.await(1, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }

        int count() {
            return count.get();
        }
    }
}
