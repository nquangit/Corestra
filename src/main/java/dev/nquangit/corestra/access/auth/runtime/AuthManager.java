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
        if (stateFresh(initialProfile.state())) {
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
            if (stateFresh(profile.state())) {
                return AuthEnsureResult.success(normalizedId, AuthEnsureStatus.VALID,
                        "Auth state was refreshed by another thread", profile, List.of());
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
     * Runs the configured provider policy even when the current state is still fresh.
     * This is used by explicit and scheduled Session Handler refresh actions.
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
