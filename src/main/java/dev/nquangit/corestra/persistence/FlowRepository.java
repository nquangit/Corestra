package dev.nquangit.corestra.persistence;

import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import dev.nquangit.corestra.config.CorestraSettingsKeys;
import dev.nquangit.corestra.flow.FlowDefinition;

import java.util.List;
import java.util.Objects;

/**
 * Provides project-scoped storage for persisted flow definitions.
 */
public final class FlowRepository {
    private final PersistedObject extensionData;
    private final FlowSerializer serializer;
    private final PersistenceMigrationService migrationService;

    /**
     * Creates a flow repository backed by Burp project extension data.
     *
     * @param persistence the Montoya persistence facade
     * @param serializer the flow serializer
     * @param migrationService the migration service for legacy payloads
     */
    public FlowRepository(
            Persistence persistence,
            FlowSerializer serializer,
            PersistenceMigrationService migrationService) {
        this.extensionData = Objects.requireNonNull(persistence, "persistence").extensionData();
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.migrationService = Objects.requireNonNull(migrationService, "migrationService");
    }

    /**
     * Loads all persisted flow definitions.
     *
     * @return the stored flow definitions, or an empty list when none are present
     */
    public List<FlowDefinition> loadAll() {
        String serializedDocument = extensionData.getString(CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT);
        if (serializedDocument == null || serializedDocument.isBlank()) {
            return List.of();
        }

        try {
            String migratedDocument = migrationService.migrateToCurrent(serializedDocument, serializer);
            List<FlowDefinition> flowDefinitions = serializer.deserialize(migratedDocument);
            if (!serializedDocument.equals(migratedDocument)) {
                extensionData.setString(CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT, migratedDocument);
            }
            return flowDefinitions;
        } catch (IllegalArgumentException exception) {
            quarantineInvalidDocument(serializedDocument);
            return List.of();
        }
    }

    /**
     * Persists the supplied flow definitions.
     *
     * @param flowDefinitions the flow definitions to persist
     */
    public void saveAll(List<FlowDefinition> flowDefinitions) {
        Objects.requireNonNull(flowDefinitions, "flowDefinitions");
        if (flowDefinitions.isEmpty()) {
            extensionData.deleteString(CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT);
            return;
        }

        extensionData.setString(
                CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT,
                serializer.serialize(flowDefinitions));
    }

    /**
     * Removes all persisted flow definitions.
     */
    public void clear() {
        extensionData.deleteString(CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT);
    }

    private void quarantineInvalidDocument(String serializedDocument) {
        try {
            extensionData.setString(CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT_QUARANTINE, serializedDocument);
        } catch (RuntimeException ignored) {
            // Recovery should still clear the primary document even when quarantine storage fails.
        } finally {
            extensionData.deleteString(CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT);
        }
    }
}
