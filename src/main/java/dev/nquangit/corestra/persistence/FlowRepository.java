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
    private List<FlowDefinition> cachedFlows;
    private final String storageKey;
    private final String quarantineKey;

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
        this(persistence, serializer, migrationService, CorestraSettingsKeys.FLOW_STORAGE_DOCUMENT);
    }

    /** Creates an isolated project workspace using the same versioned flow persistence and recovery. */
    public FlowRepository(Persistence persistence, FlowSerializer serializer, PersistenceMigrationService migrationService, String storageKey) {
        this.storageKey = Objects.requireNonNull(storageKey, "storageKey");
        this.quarantineKey = storageKey + ".quarantine";
        this.extensionData = Objects.requireNonNull(persistence, "persistence").extensionData();
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.migrationService = Objects.requireNonNull(migrationService, "migrationService");
    }

    /**
     * Loads all persisted flow definitions.
     *
     * @return the stored flow definitions, or an empty list when none are present
     */
    public synchronized List<FlowDefinition> loadAll() {
        if (cachedFlows != null) return cachedFlows;
        String serializedDocument = extensionData.getString(storageKey);
        if (serializedDocument == null || serializedDocument.isBlank()) {
            return cachedFlows = List.of();
        }

        try {
            String migratedDocument = migrationService.migrateToCurrent(serializedDocument, serializer);
            List<FlowDefinition> flowDefinitions = serializer.deserialize(migratedDocument);
            if (!serializedDocument.equals(migratedDocument)) {
                extensionData.setString(storageKey, migratedDocument);
            }
            return cachedFlows = List.copyOf(flowDefinitions);
        } catch (IllegalArgumentException exception) {
            quarantineInvalidDocument(serializedDocument);
            return cachedFlows = List.of();
        }
    }

    /**
     * Persists the supplied flow definitions.
     *
     * @param flowDefinitions the flow definitions to persist
     */
    public synchronized void saveAll(List<FlowDefinition> flowDefinitions) {
        Objects.requireNonNull(flowDefinitions, "flowDefinitions");
        List<FlowDefinition> snapshot = List.copyOf(flowDefinitions);
        if (snapshot.equals(cachedFlows)) return;
        if (snapshot.isEmpty()) {
            clear();
            return;
        }

        extensionData.setString(
                storageKey,
                serializer.serialize(snapshot));
        cachedFlows = snapshot;
    }

    /**
     * Removes all persisted flow definitions.
     */
    public synchronized void clear() {
        extensionData.deleteString(storageKey);
        cachedFlows = List.of();
    }

    private void quarantineInvalidDocument(String serializedDocument) {
        try {
            extensionData.setString(quarantineKey, serializedDocument);
        } catch (RuntimeException ignored) {
            // Recovery should still clear the primary document even when quarantine storage fails.
        } finally {
            extensionData.deleteString(storageKey);
        }
    }
}
