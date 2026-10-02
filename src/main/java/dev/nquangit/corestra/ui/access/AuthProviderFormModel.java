package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.access.auth.AuthFailureRule;
import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthInjectionRule;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthKeepAliveConfig;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.auth.AuthSuccessRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class AuthProviderFormModel {
    private final String name;
    private final String description;
    private final boolean enabled;
    private final String loginFlowId;
    private final String refreshFlowId;
    private final String validateFlowId;
    private final AuthFallbackPolicy fallbackPolicy;
    private final AuthRetryPolicy targetRetryPolicy;
    private final long stateTtlSeconds;
    private final List<AuthInjectionRule> injectionRules;
    private final List<AuthSuccessRule> successRules;
    private final List<AuthFailureRule> failureRules;
    private final AuthKeepAliveConfig keepAlive;

    AuthProviderFormModel(
            String name,
            String description,
            boolean enabled,
            String loginFlowId,
            String refreshFlowId,
            String validateFlowId,
            AuthFallbackPolicy fallbackPolicy,
            AuthRetryPolicy targetRetryPolicy,
            List<AuthInjectionRule> injectionRules,
            List<AuthSuccessRule> successRules,
            List<AuthFailureRule> failureRules) {
        this(name, description, enabled, loginFlowId, refreshFlowId, validateFlowId,
                fallbackPolicy, targetRetryPolicy, AuthProvider.DEFAULT_STATE_TTL_SECONDS,
                injectionRules, successRules, failureRules);
    }

    AuthProviderFormModel(
            String name,
            String description,
            boolean enabled,
            String loginFlowId,
            String refreshFlowId,
            String validateFlowId,
            AuthFallbackPolicy fallbackPolicy,
            AuthRetryPolicy targetRetryPolicy,
            long stateTtlSeconds,
            List<AuthInjectionRule> injectionRules,
            List<AuthSuccessRule> successRules,
            List<AuthFailureRule> failureRules) {
        this(name, description, enabled, loginFlowId, refreshFlowId, validateFlowId,
                fallbackPolicy, targetRetryPolicy, stateTtlSeconds, injectionRules, successRules,
                failureRules, AuthKeepAliveConfig.disabled());
    }

    AuthProviderFormModel(String name, String description, boolean enabled, String loginFlowId,
                         String refreshFlowId, String validateFlowId, AuthFallbackPolicy fallbackPolicy,
                         AuthRetryPolicy targetRetryPolicy, long stateTtlSeconds,
                         List<AuthInjectionRule> injectionRules, List<AuthSuccessRule> successRules,
                         List<AuthFailureRule> failureRules, AuthKeepAliveConfig keepAlive) {
        this.name = normalizeRequiredText(name, "name");
        this.description = normalizeOptionalText(description);
        this.enabled = enabled;
        this.loginFlowId = normalizeOptionalText(loginFlowId);
        this.refreshFlowId = normalizeOptionalText(refreshFlowId);
        this.validateFlowId = normalizeOptionalText(validateFlowId);
        this.fallbackPolicy = Objects.requireNonNullElse(fallbackPolicy, AuthFallbackPolicy.MANUAL_ONLY);
        this.targetRetryPolicy = Objects.requireNonNullElse(targetRetryPolicy, AuthRetryPolicy.NO_RETRY);
        if (stateTtlSeconds < 0L || stateTtlSeconds > AuthProvider.MAX_STATE_TTL_SECONDS) {
            throw new IllegalArgumentException("stateTtlSeconds must be between 0 and "
                    + AuthProvider.MAX_STATE_TTL_SECONDS);
        }
        this.stateTtlSeconds = stateTtlSeconds;
        this.injectionRules = copyList(injectionRules, "injectionRules");
        this.successRules = copyList(successRules, "successRules");
        this.failureRules = copyList(failureRules, "failureRules");
        this.keepAlive = Objects.requireNonNullElseGet(keepAlive, AuthKeepAliveConfig::disabled);
    }

    static AuthProviderFormModel from(AuthProvider provider) {
        Objects.requireNonNull(provider, "provider");
        return new AuthProviderFormModel(
                provider.name(),
                provider.description(),
                provider.enabled(),
                provider.loginFlowId(),
                provider.refreshFlowId(),
                provider.validateFlowId(),
                provider.fallbackPolicy(),
                provider.targetRetryPolicy(),
                provider.stateTtlSeconds(),
                provider.injectionRules(),
                provider.successRules(),
                provider.failureRules(), provider.keepAlive());
    }

    AuthProvider toAuthProvider(String id) {
        return new AuthProvider(
                id,
                name,
                description,
                enabled,
                loginFlowId,
                refreshFlowId,
                validateFlowId,
                injectionRules,
                successRules,
                failureRules,
                fallbackPolicy,
                targetRetryPolicy,
                stateTtlSeconds, keepAlive);
    }

    String name() {
        return name;
    }

    String description() {
        return description;
    }

    boolean enabled() {
        return enabled;
    }

    String loginFlowId() {
        return loginFlowId;
    }

    String refreshFlowId() {
        return refreshFlowId;
    }

    String validateFlowId() {
        return validateFlowId;
    }

    AuthFallbackPolicy fallbackPolicy() {
        return fallbackPolicy;
    }

    AuthRetryPolicy targetRetryPolicy() {
        return targetRetryPolicy;
    }

    long stateTtlSeconds() {
        return stateTtlSeconds;
    }

    List<AuthInjectionRule> injectionRules() {
        return injectionRules;
    }

    List<AuthSuccessRule> successRules() {
        return successRules;
    }

    List<AuthFailureRule> failureRules() {
        return failureRules;
    }

    AuthKeepAliveConfig keepAlive() {
        return keepAlive;
    }

    private static <T> List<T> copyList(List<T> values, String fieldName) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<T> copied = new ArrayList<>();
        values.forEach(value -> copied.add(Objects.requireNonNull(value, fieldName + " value")));
        return List.copyOf(copied);
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }

    private static String normalizeOptionalText(String value) {
        return value == null ? "" : value.strip();
    }
}
