package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class AuthProfileFormModel {
    private final String name;
    private final String role;
    private final String providerId;
    private final boolean enabled;
    private final Map<String, String> credentials;
    private final Map<String, String> variables;
    private final Map<String, String> headers;
    private final Map<String, String> cookies;
    private final long expiresAtEpochMillis;
    private final long lastUpdatedAtEpochMillis;

    AuthProfileFormModel(
            String name,
            String role,
            boolean enabled,
            Map<String, String> variables,
            Map<String, String> headers,
            Map<String, String> cookies) {
        this(name, role, AuthProfile.MANUAL_PROVIDER_ID, enabled, Map.of(), variables, headers, cookies, 0L, 0L);
    }

    AuthProfileFormModel(
            String name,
            String role,
            String providerId,
            boolean enabled,
            Map<String, String> variables,
            Map<String, String> headers,
            Map<String, String> cookies) {
        this(name, role, providerId, enabled, Map.of(), variables, headers, cookies, 0L, 0L);
    }

    AuthProfileFormModel(
            String name,
            String role,
            String providerId,
            boolean enabled,
            Map<String, String> credentials,
            Map<String, String> variables,
            Map<String, String> headers,
            Map<String, String> cookies,
            long expiresAtEpochMillis,
            long lastUpdatedAtEpochMillis) {
        this.name = normalizeRequiredText(name, "name");
        this.role = normalizeOptionalText(role);
        this.providerId = normalizeProviderId(providerId);
        this.enabled = enabled;
        this.credentials = copyMap(credentials, "credentials");
        this.variables = copyMap(variables, "variables");
        this.headers = copyMap(headers, "headers");
        this.cookies = copyMap(cookies, "cookies");
        this.expiresAtEpochMillis = Math.max(0L, expiresAtEpochMillis);
        this.lastUpdatedAtEpochMillis = Math.max(0L, lastUpdatedAtEpochMillis);
    }

    static AuthProfileFormModel from(AuthProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return new AuthProfileFormModel(
                profile.name(),
                profile.role(),
                profile.providerId(),
                profile.enabled(),
                profile.credentials(),
                profile.state().variables(),
                profile.state().headers(),
                profile.state().cookies(),
                profile.state().expiresAtEpochMillis(),
                profile.state().lastUpdatedAtEpochMillis());
    }

    AuthProfile toAuthProfile(String id) {
        return new AuthProfile(
                id,
                name,
                role,
                providerId,
                credentials,
                new AuthState(variables, headers, cookies, expiresAtEpochMillis,
                        lastUpdatedAtEpochMillis == 0L ? System.currentTimeMillis() : lastUpdatedAtEpochMillis),
                enabled);
    }

    String name() {
        return name;
    }

    String role() {
        return role;
    }

    String providerId() {
        return providerId;
    }

    boolean enabled() {
        return enabled;
    }

    Map<String, String> credentials() {
        return credentials;
    }

    Map<String, String> variables() {
        return variables;
    }

    Map<String, String> headers() {
        return headers;
    }

    Map<String, String> cookies() {
        return cookies;
    }

    long expiresAtEpochMillis() {
        return expiresAtEpochMillis;
    }

    long lastUpdatedAtEpochMillis() {
        return lastUpdatedAtEpochMillis;
    }

    private static Map<String, String> copyMap(Map<String, String> values, String fieldName) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            String normalizedKey = normalizeRequiredText(key, fieldName + " key");
            copied.put(normalizedKey, Objects.requireNonNullElse(value, ""));
        });
        return Map.copyOf(copied);
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

    private static String normalizeProviderId(String value) {
        return value == null || value.isBlank() ? AuthProfile.MANUAL_PROVIDER_ID : value.strip();
    }
}
