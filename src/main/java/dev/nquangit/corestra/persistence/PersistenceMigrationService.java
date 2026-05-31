package dev.nquangit.corestra.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Upgrades persisted flow documents from older formats to the current schema version.
 */
public final class PersistenceMigrationService {
    /**
     * Migrates the supplied persisted payload to the current serializer schema when necessary.
     *
     * @param serializedDocument the stored persisted payload
     * @param serializer the serializer that defines the current schema shape
     * @return the migrated payload, or the original payload when no migration is required
     */
    public String migrateToCurrent(String serializedDocument, FlowSerializer serializer) {
        if (serializedDocument == null || serializedDocument.isBlank()) {
            return serializedDocument;
        }

        JsonNode root = serializer.readJson(serializedDocument);
        if (root.isArray()) {
            return migrateLegacyArrayDocument(root, serializer);
        }

        if (!root.isObject()) {
            throw new IllegalArgumentException("Persisted flow document must be a JSON object or array");
        }

        return migrateObjectDocument((ObjectNode) root, serializedDocument, serializer);
    }

    private String migrateLegacyArrayDocument(JsonNode root, FlowSerializer serializer) {
        return serializer.writeJson(serializer.wrapWithCurrentVersion(root));
    }

    private String migrateObjectDocument(
            ObjectNode objectNode,
            String serializedDocument,
            FlowSerializer serializer) {
        JsonNode schemaVersionNode = objectNode.get("schemaVersion");
        if (schemaVersionNode == null) {
            return migrateLegacyObjectWithoutSchemaVersion(objectNode, serializer);
        }

        validateSchemaVersion(schemaVersionNode, serializer);
        return serializedDocument;
    }

    private String migrateLegacyObjectWithoutSchemaVersion(ObjectNode objectNode, FlowSerializer serializer) {
        JsonNode flowsNode = objectNode.path("flows");
        if (!flowsNode.isArray()) {
            throw new IllegalArgumentException("Legacy persisted flow document must contain a flows array");
        }

        ObjectNode migrated = objectNode.deepCopy();
        migrated.put("schemaVersion", serializer.currentSchemaVersion());
        return serializer.writeJson(migrated);
    }

    private void validateSchemaVersion(JsonNode schemaVersionNode, FlowSerializer serializer) {
        if (!schemaVersionNode.canConvertToInt()) {
            throw new IllegalArgumentException("Persisted flow schemaVersion must be an integer");
        }

        int schemaVersion = schemaVersionNode.intValue();
        if (schemaVersion != serializer.currentSchemaVersion()) {
            throw new IllegalArgumentException("Unsupported persisted flow schema version: " + schemaVersion);
        }
    }
}
