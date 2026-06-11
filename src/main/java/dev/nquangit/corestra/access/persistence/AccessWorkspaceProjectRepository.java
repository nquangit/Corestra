package dev.nquangit.corestra.access.persistence;

import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.access.security.ExportMode;
import dev.nquangit.corestra.config.CorestraSettingsKeys;

import java.util.Objects;

/**
 * Persists Access Testing project state in Burp project-scoped extension data.
 */
public final class AccessWorkspaceProjectRepository {
    private final PersistedObject extensionData;
    private final AccessWorkspaceSerializer serializer;
    private final ObjectMapper objectMapper;

    /**
     * Creates an access workspace repository backed by Burp persistence.
     *
     * @param persistence Montoya persistence facade
     */
    public AccessWorkspaceProjectRepository(Persistence persistence) {
        this(persistence, new AccessWorkspaceSerializer());
    }

    /**
     * Creates an access workspace repository backed by Burp persistence.
     *
     * @param persistence Montoya persistence facade
     * @param serializer access workspace serializer
     */
    public AccessWorkspaceProjectRepository(Persistence persistence, AccessWorkspaceSerializer serializer) {
        this.extensionData = Objects.requireNonNull(persistence, "persistence").extensionData();
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Loads persisted Access Testing project state.
     *
     * @return access project document, or empty when missing or invalid
     */
    public AccessWorkspaceDocument load() {
        String serializedDocument = extensionData.getString(CorestraSettingsKeys.ACCESS_WORKSPACE_DOCUMENT);
        if (serializedDocument == null || serializedDocument.isBlank()) {
            return AccessWorkspaceDocument.empty();
        }

        try {
            JsonNode node = objectMapper.readTree(serializedDocument);
            AccessWorkspaceDocument document = serializer.deserialize(node);
            return AccessWorkspaceConfiguration.projectState(document);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            quarantineInvalidDocument(serializedDocument);
            return AccessWorkspaceDocument.empty();
        }
    }

    /**
     * Persists Access Testing project state and omits runtime scenarios.
     *
     * @param document source access project document
     */
    public void save(AccessWorkspaceDocument document) {
        AccessWorkspaceDocument projectState = AccessWorkspaceConfiguration.projectState(document);
        if (!AccessWorkspaceConfiguration.hasProjectState(projectState)) {
            clear();
            return;
        }

        extensionData.setString(
                CorestraSettingsKeys.ACCESS_WORKSPACE_DOCUMENT,
                serializer.writeJson(serializer.serialize(projectState, ExportMode.FULL_EXPORT)));
    }

    /**
     * Removes persisted Access Testing configuration.
     */
    public void clear() {
        extensionData.deleteString(CorestraSettingsKeys.ACCESS_WORKSPACE_DOCUMENT);
    }

    private void quarantineInvalidDocument(String serializedDocument) {
        try {
            extensionData.setString(CorestraSettingsKeys.ACCESS_WORKSPACE_DOCUMENT_QUARANTINE, serializedDocument);
        } catch (RuntimeException ignored) {
            // Recovery should still clear the primary document even when quarantine storage fails.
        } finally {
            extensionData.deleteString(CorestraSettingsKeys.ACCESS_WORKSPACE_DOCUMENT);
        }
    }
}
