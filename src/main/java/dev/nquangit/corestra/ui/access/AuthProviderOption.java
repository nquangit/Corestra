package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;

import java.util.Objects;

final class AuthProviderOption {
    private final String id;
    private final String label;

    AuthProviderOption(String id, String label) {
        this.id = normalizeRequiredText(id, "id");
        this.label = normalizeRequiredText(label, "label");
    }

    static AuthProviderOption manual() {
        return new AuthProviderOption(AuthProfile.MANUAL_PROVIDER_ID, AuthProfile.MANUAL_PROVIDER_ID);
    }

    static AuthProviderOption from(AuthProvider provider) {
        Objects.requireNonNull(provider, "provider");
        return new AuthProviderOption(provider.id(), provider.name() + " (" + provider.id() + ")");
    }

    String id() {
        return id;
    }

    String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
