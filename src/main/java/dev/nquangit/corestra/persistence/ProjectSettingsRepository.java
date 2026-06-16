package dev.nquangit.corestra.persistence;

import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.config.CorestraSettingsKeys;

import java.util.Objects;

/**
 * Stores project-scoped Corestra settings in Burp extension data.
 */
public final class ProjectSettingsRepository {
    private final PersistedObject extensionData;
    private final ObjectMapper objectMapper;
    private ProjectSettings inMemorySettings;

    /**
     * Creates a test-friendly in-memory repository.
     */
    public ProjectSettingsRepository() {
        this.extensionData = null;
        this.objectMapper = new ObjectMapper();
        this.inMemorySettings = ProjectSettings.defaults();
    }

    /**
     * Creates a repository backed by Burp project persistence.
     *
     * @param persistence Montoya persistence facade
     */
    public ProjectSettingsRepository(Persistence persistence) {
        this.extensionData = Objects.requireNonNull(persistence, "persistence").extensionData();
        this.objectMapper = new ObjectMapper();
        this.inMemorySettings = ProjectSettings.defaults();
    }

    /**
     * Loads settings from project storage.
     *
     * @return persisted settings, or defaults when absent or invalid
     */
    public ProjectSettings load() {
        if (extensionData == null) {
            return inMemorySettings;
        }
        String json = extensionData.getString(CorestraSettingsKeys.PROJECT_SETTINGS);
        if (json == null || json.isBlank()) {
            return ProjectSettings.defaults();
        }
        try {
            return objectMapper.readValue(json, ProjectSettings.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return ProjectSettings.defaults();
        }
    }

    /**
     * Persists settings to project storage.
     *
     * @param settings settings to save
     */
    public void save(ProjectSettings settings) {
        Objects.requireNonNull(settings, "settings");
        if (extensionData == null) {
            inMemorySettings = settings;
            return;
        }
        try {
            extensionData.setString(CorestraSettingsKeys.PROJECT_SETTINGS,
                    objectMapper.writeValueAsString(settings));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize project settings", e);
        }
    }
}
