package dev.nquangit.corestra.persistence;

import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.config.CorestraSettingsKeys;

import java.util.Objects;

public final class ProjectMetadataRepository {
    private final PersistedObject extensionData;
    private final ObjectMapper objectMapper;

    public ProjectMetadataRepository(Persistence persistence) {
        this.extensionData = Objects.requireNonNull(persistence, "persistence").extensionData();
        this.objectMapper = new ObjectMapper();
    }

    public ProjectMetadata load() {
        String json = extensionData.getString(CorestraSettingsKeys.PROJECT_METADATA);
        if (json == null || json.isBlank()) {
            return ProjectMetadata.createNew();
        }
        try {
            return objectMapper.readValue(json, ProjectMetadata.class);
        } catch (JsonProcessingException e) {
            return ProjectMetadata.createNew();
        }
    }

    public void save(ProjectMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        try {
            extensionData.setString(CorestraSettingsKeys.PROJECT_METADATA,
                    objectMapper.writeValueAsString(metadata));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize project metadata", e);
        }
    }

    /** Removes project metadata stored by the extension. */
    public void clear() {
        extensionData.deleteString(CorestraSettingsKeys.PROJECT_METADATA);
    }
}
