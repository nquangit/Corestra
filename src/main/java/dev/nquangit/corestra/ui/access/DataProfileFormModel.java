package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.access.data.DataProfile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class DataProfileFormModel {
    private final String name;
    private final String ownerAuthProfileId;
    private final boolean enabled;
    private final Map<String, String> values;

    DataProfileFormModel(String name, String ownerAuthProfileId, boolean enabled, Map<String, String> values) {
        this.name = normalizeRequiredText(name, "name");
        this.ownerAuthProfileId = normalizeOptionalText(ownerAuthProfileId);
        this.enabled = enabled;
        this.values = copyMap(values);
    }

    static DataProfileFormModel from(DataProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return new DataProfileFormModel(
                profile.name(),
                profile.ownerAuthProfileId(),
                profile.enabled(),
                profile.values());
    }

    DataProfile toDataProfile(String id) {
        return new DataProfile(id, name, ownerAuthProfileId, values, enabled);
    }

    String name() {
        return name;
    }

    String ownerAuthProfileId() {
        return ownerAuthProfileId;
    }

    boolean enabled() {
        return enabled;
    }

    Map<String, String> values() {
        return values;
    }

    private static Map<String, String> copyMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> copied.put(normalizeRequiredText(key, "value key"),
                Objects.requireNonNullElse(value, "")));
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
}
