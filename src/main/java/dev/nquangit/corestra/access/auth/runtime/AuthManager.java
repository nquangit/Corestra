package dev.nquangit.corestra.access.auth.runtime;

import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.access.variable.RenderMode;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Ensures configured AuthProfiles have usable authentication state before access-test execution.
 */
public final class AuthManager {
    private final InMemoryAccessRepository accessRepository;
    private final AuthFlowRunner authFlowRunner;
    private final AuthRuleEvaluator authRuleEvaluator;
    private final Clock clock;
    private final ConcurrentHashMap<String, ReentrantLock> locksByProfileId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, KeepAliveBackoff> keepAliveBackoffs = new ConcurrentHashMap<>();
    private final AuthKeepAliveEvaluator keepAliveEvaluator;

    /**
     * Creates an auth manager.
     *
     * @param accessRepository access repository storing auth profiles/providers
     * @param authFlowRunner auth flow runner
     * @param authRuleEvaluator auth rule evaluator
     * @param clock clock used for freshness checks and state timestamps
     */
    public AuthManager(
            InMemoryAccessRepository accessRepository,
            AuthFlowRunner authFlowRunner,
            AuthRuleEvaluator authRuleEvaluator,
            Clock clock) {
        this.accessRepository = Objects.requireNonNull(accessRepository, "accessRepository");
        this.authFlowRunner = Objects.requireNonNull(authFlowRunner, "authFlowRunner");
        this.authRuleEvaluator = Objects.requireNonNull(authRuleEvaluator, "authRuleEvaluator");
        this.keepAliveEvaluator = new AuthKeepAliveEvaluator(authRuleEvaluator);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Ensures an auth profile has usable auth state.
     *
     * @param authProfileId auth profile id
     * @param environmentId environment id
     * @param env environment variables
     * @param renderMode render mode for auth flow variable rendering
     * @return ensure result
     */
    public AuthEnsureResult ensureAuth(
            String authProfileId,
            String environmentId,
            Map<String, String> env,
            RenderMode renderMode) {
        String normalizedId = normalizeRequiredText(authProfileId, "authProfileId");
        AuthProfile initialProfile = accessRepository.authProfile(normalizedId).orElse(null);
        if (initialProfile == null) {
            return AuthEnsureResult.failed(normalizedId, "Auth profile not found: " + normalizedId,
                    Optional.empty(), List.of());
        }
        if (isManual(initialProfile)) {
            return AuthEnsureResult.success(normalizedId, AuthEnsureStatus.MANUAL,
                    "Manual auth profile", initialProfile, List.of());
        }
        AuthProvider initialProvider = accessRepository.authProvider(initialProfile.providerId()).orElse(null);
        if (initialProvider == null) {
            return AuthEnsureResult.failed(normalizedId,
                    "Auth provider not found: " + initialProfile.providerId(),
                    Optional.of(initialProfile),
                    List.of());
        }
        if (!initialProvider.enabled()) {
            return AuthEnsureResult.failed(normalizedId,
                    "Auth provider is disabled: " + initialProvider.id(),
                    Optional.of(initialProfile),
                    List.of());
        }
        if (stateFresh(initialProfile.state()) && !initialProvider.keepAlive().enabled()) {
            return AuthEnsureResult.success(normalizedId, AuthEnsureStatus.VALID,
                    "Auth state is still valid", initialProfile, List.of());
        }

        ReentrantLock lock = locksByProfileId.computeIfAbsent(normalizedId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            AuthProfile profile = accessRepository.authProfile(normalizedId).orElse(null);
            if (profile == null) {
                return AuthEnsureResult.failed(normalizedId, "Auth profile not found: " + normalizedId,
                        Optional.empty(), List.of());
            }
            if (isManual(profile)) {
                return AuthEnsureResult.success(normalizedId, AuthEnsureStatus.MANUAL,
                        "Manual auth profile", profile, List.of());
            }
            AuthProvider provider = accessRepository.authProvider(profile.providerId()).orElse(null);
            if (provider == null) {
                return AuthEnsureResult.failed(normalizedId,
                        "Auth provider not found: " + profile.providerId(),
                        Optional.of(profile),
                        List.of());
            }
            if (!provider.enabled()) {
                return AuthEnsureResult.failed(normalizedId,
                        "Auth provider is disabled: " + provider.id(),
                        Optional.of(profile),
                        List.of());
            }
            if (provider.keepAlive().enabled()) {
                KeepAliveBackoff backoff = currentBackoff(profile, provider);
                if (backoff != null && backoff.authenticationFailed()) {
                    return maintainAuth(normalizedId, environmentId, env, renderMode);
                }
            }
            if (stateFresh(profile.state())) {
                return AuthEnsureResult.success(normalizedId, AuthEnsureStatus.VALID,
                        "Auth state was refreshed by another thread", profile, List.of());
            }
            if (provider.keepAlive().enabled()) {
                return maintainAuth(normalizedId, environmentId, env, renderMode);
            }
            return ensureWithProvider(
                    profile,
                    provider,
                    environmentVariables(environmentId, env),
                    Objects.requireNonNullElse(renderMode, RenderMode.STRICT));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Maintains an actor's existing session on a scheduler tick, regardless of local TTL.
     * Providers without keep-alive retain their previous forced-authentication behavior.
     * Empty state uses the original auth policy; transient keep-alive failures never re-login.
     */
    public AuthEnsureResult maintainAuth(String authProfileId, String environmentId,
                                         Map<String, String> env, RenderMode renderMode) {
        String id = normalizeRequiredText(authProfileId, "authProfileId");
        ReentrantLock lock = locksByProfileId.computeIfAbsent(id, ignored -> new ReentrantLock());
        lock.lock();
        try {
            AuthProfile profile = accessRepository.authProfile(id).orElse(null);
            if (profile == null || isManual(profile)) {
                return forceAuth(id, environmentId, env, renderMode);
            }
            AuthProvider provider = accessRepository.authProvider(profile.providerId()).orElse(null);
            if (provider == null || !provider.enabled() || !provider.keepAlive().enabled()) {
                return forceAuth(id, environmentId, env, renderMode);
            }
            RenderMode mode = Objects.requireNonNullElse(renderMode, RenderMode.STRICT);
            Map<String, String> variables = environmentVariables(environmentId, env);
            KeepAliveBackoff backoff = currentBackoff(profile, provider);
            if (backoff != null && clock.millis() < backoff.nextAttemptAt()) {
                return deferred(profile, backoff, "Authentication maintenance is backing off", List.of());
            }
            if (!hasAcquiredState(profile.state())) {
                return authenticateForMaintenance(profile, provider, variables, mode);
            }
            return maintainKeepAlive(profile, provider, variables, mode);
        } finally {
            lock.unlock();
        }
    }

    private AuthEnsureResult maintainKeepAlive(AuthProfile profile, AuthProvider provider,
                                               Map<String, String> env, RenderMode mode) {
        KeepAliveBackoff backoff = currentBackoff(profile, provider);
        if (backoff != null && clock.millis() < backoff.nextAttemptAt()) {
            return deferred(profile, backoff, "Keep-alive is backing off", List.of());
        }
        AuthFlowRunResult run;
        try {
            run = authFlowRunner.runAuthFlow(provider.keepAlive().flowId(), profile, provider,
                    env, AuthFlowPurpose.KEEP_ALIVE, mode);
        } catch (RuntimeException exception) {
            return deferred(profile, recordBackoff(profile, provider),
                    "Keep-alive execution failed: " + exception.getMessage(), List.of());
        }
        AuthKeepAliveEvaluator.Result evaluation = keepAliveEvaluator.evaluate(provider.keepAlive(), run);
        if (evaluation.outcome() == AuthKeepAliveEvaluator.Outcome.ALIVE) {
            AuthProfile updated = updateAuthState(profile, provider, run);
            accessRepository.saveAuthProfile(updated);
            keepAliveBackoffs.remove(profile.id());
            return AuthEnsureResult.success(profile.id(), AuthEnsureStatus.KEPT_ALIVE,
                    evaluation.reason(), updated, evaluation.warnings());
        }
        if (evaluation.outcome() == AuthKeepAliveEvaluator.Outcome.EXPIRED) {
            return authenticateForMaintenance(profile, provider, env, mode);
        }
        return deferred(profile, recordBackoff(profile, provider), evaluation.reason(), evaluation.warnings());
    }

    private AuthEnsureResult authenticateForMaintenance(AuthProfile profile, AuthProvider provider,
                                                         Map<String, String> env, RenderMode mode) {
        AuthEnsureResult result;
        try {
            result = ensureWithProvider(profile, provider, env, mode);
        } catch (RuntimeException exception) {
            result = AuthEnsureResult.failed(profile.id(), "Authentication failed: " + exception.getMessage(),
                    Optional.of(profile), List.of());
        }
        if (result.failed()) recordBackoff(profile, provider, true);
        else keepAliveBackoffs.remove(profile.id());
        return result;
    }

    private static boolean hasAcquiredState(AuthState state) {
        return state.lastUpdatedAtEpochMillis() > 0 || !state.variables().isEmpty()
                || !state.headers().isEmpty() || !state.cookies().isEmpty();
    }

    private KeepAliveBackoff currentBackoff(AuthProfile profile, AuthProvider provider) {
        KeepAliveBackoff backoff = keepAliveBackoffs.get(profile.id());
        if (backoff != null && (!backoff.flowId().equals(provider.keepAlive().flowId())
                || !backoff.state().equals(profile.state()))) {
            keepAliveBackoffs.remove(profile.id(), backoff);
            return null;
        }
        return backoff;
    }

    private KeepAliveBackoff recordBackoff(AuthProfile profile, AuthProvider provider) {
        return recordBackoff(profile, provider, false);
    }

    private KeepAliveBackoff recordBackoff(AuthProfile profile, AuthProvider provider, boolean authenticationFailed) {
        KeepAliveBackoff previous = currentBackoff(profile, provider);
        int failures = previous == null ? 1 : Math.min(5, previous.failures() + 1);
        long delay = Math.min(300_000L, 30_000L << (failures - 1));
        KeepAliveBackoff next = new KeepAliveBackoff(provider.keepAlive().flowId(), profile.state(),
                failures, clock.millis() + delay, authenticationFailed);
        keepAliveBackoffs.put(profile.id(), next);
        return next;
    }

    private AuthEnsureResult deferred(AuthProfile profile, KeepAliveBackoff backoff,
                                       String reason, List<String> warnings) {
        long seconds = Math.max(0, (backoff.nextAttemptAt() - clock.millis() + 999) / 1_000);
        if (backoff.authenticationFailed() || !hasAcquiredState(profile.state())) {
            return AuthEnsureResult.failed(profile.id(), reason + "; retry in " + seconds
                    + "s. Authentication has not been established.", Optional.of(profile), warnings);
        }
        return AuthEnsureResult.success(profile.id(), AuthEnsureStatus.DEFERRED,
                reason + "; retry in " + seconds + "s. Existing auth state is unchanged.", profile, warnings);
    }

    private record KeepAliveBackoff(String flowId, AuthState state, int failures,
                                    long nextAttemptAt, boolean authenticationFailed) { }

    /**
     * Runs the configured provider policy even when the current state is still fresh.
     * Used by explicit auth actions and legacy scheduler maintenance without keep-alive.
     *
     * @param authProfileId auth profile id
     * @param environmentId environment id
     * @param env environment variables
     * @param renderMode render mode for auth flow variables
     * @return ensure result
     */
    public AuthEnsureResult forceAuth(
            String authProfileId,
            String environmentId,
            Map<String, String> env,
            RenderMode renderMode) {
        String normalizedId = normalizeRequiredText(authProfileId, "authProfileId");
        ReentrantLock lock = locksByProfileId.computeIfAbsent(normalizedId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            AuthProfile profile = accessRepository.authProfile(normalizedId).orElse(null);
            if (profile == null) {
                return AuthEnsureResult.failed(normalizedId, "Auth profile not found: " + normalizedId,
                        Optional.empty(), List.of());
            }
            if (isManual(profile)) {
                return AuthEnsureResult.success(normalizedId, AuthEnsureStatus.MANUAL,
                        "Manual auth profile", profile, List.of());
            }
            AuthProvider provider = accessRepository.authProvider(profile.providerId()).orElse(null);
            if (provider == null) {
                return AuthEnsureResult.failed(normalizedId,
                        "Auth provider not found: " + profile.providerId(), Optional.of(profile), List.of());
            }
            if (!provider.enabled()) {
                return AuthEnsureResult.failed(normalizedId,
                        "Auth provider is disabled: " + provider.id(), Optional.of(profile), List.of());
            }
            return ensureWithProvider(profile, provider, environmentVariables(environmentId, env),
                    Objects.requireNonNullElse(renderMode, RenderMode.STRICT));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Forces the auth action requested by a target retry policy.
     *
     * <p>Unlike {@link #ensureAuth(String, String, Map, RenderMode)}, this method intentionally does not skip
     * execution when the current state appears fresh because the target response already indicated auth failure.</p>
     *
     * @param authProfileId auth profile id
     * @param environmentId environment id
     * @param env environment variables
     * @param renderMode render mode for auth flow variable rendering
     * @param retryPolicy target retry policy to execute
     * @return retry auth result
     */
    public AuthEnsureResult retryAuth(
            String authProfileId,
            String environmentId,
            Map<String, String> env,
            RenderMode renderMode,
            AuthRetryPolicy retryPolicy) {
        String normalizedId = normalizeRequiredText(authProfileId, "authProfileId");
        AuthRetryPolicy policy = Objects.requireNonNullElse(retryPolicy, AuthRetryPolicy.NO_RETRY);
        AuthProfile initialProfile = accessRepository.authProfile(normalizedId).orElse(null);
        if (initialProfile == null) {
            return AuthEnsureResult.failed(normalizedId, "Auth profile not found: " + normalizedId,
                    Optional.empty(), List.of());
        }
        if (policy == AuthRetryPolicy.NO_RETRY) {
            return AuthEnsureResult.success(normalizedId, AuthEnsureStatus.SKIPPED,
                    "Target auth retry is disabled", initialProfile, List.of());
        }
        if (isManual(initialProfile)) {
            return AuthEnsureResult.success(normalizedId, AuthEnsureStatus.SKIPPED,
                    "Manual auth profile is not retried automatically", initialProfile, List.of());
        }

        ReentrantLock lock = locksByProfileId.computeIfAbsent(normalizedId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            AuthProfile profile = accessRepository.authProfile(normalizedId).orElse(null);
            if (profile == null) {
                return AuthEnsureResult.failed(normalizedId, "Auth profile not found: " + normalizedId,
                        Optional.empty(), List.of());
            }
            if (isManual(profile)) {
                return AuthEnsureResult.success(normalizedId, AuthEnsureStatus.SKIPPED,
                        "Manual auth profile is not retried automatically", profile, List.of());
            }
            AuthProvider provider = accessRepository.authProvider(profile.providerId()).orElse(null);
            if (provider == null) {
                return AuthEnsureResult.failed(normalizedId,
                        "Auth provider not found: " + profile.providerId(),
                        Optional.of(profile),
                        List.of());
            }
            if (!provider.enabled()) {
                return AuthEnsureResult.failed(normalizedId,
                        "Auth provider is disabled: " + provider.id(),
                        Optional.of(profile),
                        List.of());
            }

            Map<String, String> retryEnv = environmentVariables(environmentId, env);
            RenderMode mode = Objects.requireNonNullElse(renderMode, RenderMode.STRICT);
            return switch (policy) {
                case NO_RETRY -> AuthEnsureResult.success(profile.id(), AuthEnsureStatus.SKIPPED,
                        "Target auth retry is disabled", profile, List.of());
                case REFRESH_AND_RETRY_ONCE -> refreshOnly(profile, provider, retryEnv, mode, List.of());
                case LOGIN_AND_RETRY_ONCE -> login(profile, provider, retryEnv, mode, List.of());
                case REFRESH_THEN_LOGIN_AND_RETRY_ONCE -> refreshThenLogin(profile, provider, retryEnv, mode, List.of());
            };
        } finally {
            lock.unlock();
        }
    }

    private AuthEnsureResult ensureWithProvider(
            AuthProfile profile,
            AuthProvider provider,
            Map<String, String> env,
            RenderMode renderMode) {
        AuthFallbackPolicy policy = provider.fallbackPolicy();
        return switch (policy) {
            case MANUAL_ONLY -> AuthEnsureResult.success(profile.id(), AuthEnsureStatus.MANUAL,
                    "Auth provider is configured for manual-only state", profile, List.of());
            case LOGIN_ONLY -> login(profile, provider, env, renderMode, List.of());
            case REFRESH_THEN_LOGIN -> refreshThenLogin(profile, provider, env, renderMode, List.of());
            case VALIDATE_THEN_REFRESH_THEN_LOGIN -> validateThenRefreshThenLogin(profile, provider, env, renderMode);
        };
    }

    private AuthEnsureResult validateThenRefreshThenLogin(
            AuthProfile profile,
            AuthProvider provider,
            Map<String, String> env,
            RenderMode renderMode) {
        FlowAttempt validate = attempt(provider.validateFlowId(), profile, provider, env, AuthFlowPurpose.VALIDATE, renderMode);
        if (validate.success()) {
            AuthProfile updated = updateAuthState(profile, provider, validate.runResult());
            accessRepository.saveAuthProfile(updated);
            return AuthEnsureResult.success(profile.id(), AuthEnsureStatus.VALID,
                    validate.reason(), updated, validate.warnings());
        }
        return refreshThenLogin(profile, provider, env, renderMode, validate.allMessages());
    }

    private AuthEnsureResult refreshThenLogin(
            AuthProfile profile,
            AuthProvider provider,
            Map<String, String> env,
            RenderMode renderMode,
            List<String> priorMessages) {
        FlowAttempt refresh = attempt(provider.refreshFlowId(), profile, provider, env, AuthFlowPurpose.REFRESH, renderMode);
        List<String> messages = combined(priorMessages, refresh.allMessages());
        if (refresh.success()) {
            AuthProfile updated = updateAuthState(profile, provider, refresh.runResult());
            accessRepository.saveAuthProfile(updated);
            return AuthEnsureResult.success(profile.id(), AuthEnsureStatus.REFRESHED,
                    refresh.reason(), updated, refresh.warnings());
        }
        return login(profile, provider, env, renderMode, messages);
    }

    private AuthEnsureResult refreshOnly(
            AuthProfile profile,
            AuthProvider provider,
            Map<String, String> env,
            RenderMode renderMode,
            List<String> priorMessages) {
        FlowAttempt refresh = attempt(provider.refreshFlowId(), profile, provider, env, AuthFlowPurpose.REFRESH, renderMode);
        List<String> messages = combined(priorMessages, refresh.allMessages());
        if (refresh.success()) {
            AuthProfile updated = updateAuthState(profile, provider, refresh.runResult());
            accessRepository.saveAuthProfile(updated);
            return AuthEnsureResult.success(profile.id(), AuthEnsureStatus.REFRESHED,
                    refresh.reason(), updated, refresh.warnings());
        }
        return AuthEnsureResult.failed(profile.id(), String.join("; ", messages),
                Optional.of(profile), combined(priorMessages, refresh.warnings()));
    }

    private AuthEnsureResult login(
            AuthProfile profile,
            AuthProvider provider,
            Map<String, String> env,
            RenderMode renderMode,
            List<String> priorMessages) {
        FlowAttempt login = attempt(provider.loginFlowId(), profile, provider, env, AuthFlowPurpose.LOGIN, renderMode);
        List<String> messages = combined(priorMessages, login.allMessages());
        if (login.success()) {
            AuthProfile updated = updateAuthState(profile, provider, login.runResult());
            accessRepository.saveAuthProfile(updated);
            return AuthEnsureResult.success(profile.id(), AuthEnsureStatus.LOGGED_IN,
                    login.reason(), updated, login.warnings());
        }
        return AuthEnsureResult.failed(profile.id(), String.join("; ", messages),
                Optional.of(profile), combined(priorMessages, login.warnings()));
    }

    private FlowAttempt attempt(
            String flowId,
            AuthProfile profile,
            AuthProvider provider,
            Map<String, String> env,
            AuthFlowPurpose purpose,
            RenderMode renderMode) {
        AuthFlowRunResult runResult = authFlowRunner.runAuthFlow(flowId, profile, provider, env, purpose, renderMode);
        AuthRuleEvaluationResult evaluation = authRuleEvaluator.evaluate(provider, purpose, runResult);
        return new FlowAttempt(runResult, evaluation);
    }

    private AuthProfile updateAuthState(
            AuthProfile profile,
            AuthProvider provider,
            AuthFlowRunResult runResult) {
        AuthState oldState = profile.state();
        LinkedHashMap<String, String> variables = new LinkedHashMap<>(oldState.variables());
        Map<String, String> extracted = AuthState.normalizeVariables(runResult.extractedVariables());
        variables.putAll(extracted);
        long expiresAt = resolvedExpiresAt(extracted, provider.stateTtlSeconds());
        AuthState updatedState = new AuthState(
                variables,
                oldState.headers(),
                oldState.cookies(),
                expiresAt,
                clock.millis());
        return new AuthProfile(
                profile.id(),
                profile.name(),
                profile.role(),
                profile.providerId(),
                profile.credentials(),
                updatedState,
                profile.enabled());
    }

    private Optional<Long> parseExpiresAt(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            long parsed = Long.parseLong(value.strip());
            return parsed >= 0L ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private long resolvedExpiresAt(Map<String, String> extracted, long fallbackTtlSeconds) {
        Optional<Long> absolute = parseExpiresAt(extracted.get("expiresAt"));
        if (absolute.isPresent()) return absolute.get();
        String relative = extracted.containsKey("expiresIn")
                ? extracted.get("expiresIn") : extracted.get("expires_in");
        if (relative != null && !relative.isBlank()) {
            try {
                long seconds = Long.parseLong(relative.strip());
                if (seconds >= 0L) return Math.addExact(clock.millis(), Math.multiplyExact(seconds, 1_000L));
            } catch (NumberFormatException | ArithmeticException ignored) {
                // Fall through to the provider TTL.
            }
        }
        try {
            return fallbackTtlSeconds <= 0L ? 0L
                    : Math.addExact(clock.millis(), Math.multiplyExact(fallbackTtlSeconds, 1_000L));
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private boolean stateFresh(AuthState state) {
        return state != null
                && state.expiresAtEpochMillis() > 0L
                && state.expiresAtEpochMillis() > clock.millis();
    }

    private boolean isManual(AuthProfile profile) {
        return profile.providerId().isBlank() || AuthProfile.MANUAL_PROVIDER_ID.equals(profile.providerId());
    }

    private Map<String, String> environmentVariables(String environmentId, Map<String, String> env) {
        LinkedHashMap<String, String> variables = new LinkedHashMap<>();
        if (env != null) {
            env.forEach((key, value) -> {
                if (key != null && !key.isBlank()) {
                    variables.put(key.strip(), Objects.requireNonNullElse(value, ""));
                }
            });
        }
        if (environmentId != null && !environmentId.isBlank()) {
            variables.put("environmentId", environmentId.strip());
        }
        return Map.copyOf(variables);
    }

    private List<String> combined(List<String> first, List<String> second) {
        ArrayList<String> combined = new ArrayList<>();
        if (first != null) {
            first.stream().filter(message -> message != null && !message.isBlank()).forEach(combined::add);
        }
        if (second != null) {
            second.stream().filter(message -> message != null && !message.isBlank()).forEach(combined::add);
        }
        return List.copyOf(combined);
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }

    private record FlowAttempt(AuthFlowRunResult runResult, AuthRuleEvaluationResult evaluation) {
        private boolean success() {
            return evaluation.success();
        }

        private String reason() {
            return evaluation.reason().isBlank() ? runResult.reason() : evaluation.reason();
        }

        private List<String> warnings() {
            return evaluation.warnings();
        }

        private List<String> allMessages() {
            List<String> messages = new ArrayList<>();
            if (!runResult.reason().isBlank()) {
                messages.add(runResult.reason());
            }
            if (!evaluation.reason().isBlank() && !evaluation.reason().equals(runResult.reason())) {
                messages.add(evaluation.reason());
            }
            messages.addAll(evaluation.warnings());
            return List.copyOf(messages);
        }
    }
}
