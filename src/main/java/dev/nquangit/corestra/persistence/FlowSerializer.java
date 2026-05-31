package dev.nquangit.corestra.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nquangit.corestra.flow.FlowDefinition;

import java.util.List;
import java.util.Objects;

/**
 * Serializes and deserializes persisted flow definitions using a versioned JSON document.
 */
public final class FlowSerializer {
    private static final int CURRENT_SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;
    private final JavaType flowListType;

    /**
     * Creates a serializer using the default Jackson object mapper.
     */
    public FlowSerializer() {
        this.objectMapper = new ObjectMapper();
        this.flowListType = objectMapper.getTypeFactory().constructCollectionType(List.class, FlowDefinition.class);
    }

    /**
     * Returns the current persisted flow schema version.
     *
     * @return the current persisted flow schema version
     */
    public int currentSchemaVersion() {
        return CURRENT_SCHEMA_VERSION;
    }

    /**
     * Serializes the supplied flow definitions into the current versioned document format.
     *
     * @param flowDefinitions the flow definitions to serialize
     * @return the serialized JSON document
     */
    public String serialize(List<FlowDefinition> flowDefinitions) {
        Objects.requireNonNull(flowDefinitions, "flowDefinitions");

        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", CURRENT_SCHEMA_VERSION);
        root.set("flows", objectMapper.valueToTree(List.copyOf(flowDefinitions)));
        return writeJson(root);
    }

    /**
     * Deserializes the supplied versioned JSON document into flow definitions.
     *
     * @param serializedDocument the versioned JSON document
     * @return the deserialized flow definitions
     */
    public List<FlowDefinition> deserialize(String serializedDocument) {
        if (serializedDocument == null || serializedDocument.isBlank()) {
            return List.of();
        }

        JsonNode root = readJson(serializedDocument);
        if (!root.isObject()) {
            throw new IllegalArgumentException("Persisted flow document must be a JSON object");
        }

        JsonNode schemaVersionNode = root.get("schemaVersion");
        if (schemaVersionNode == null || !schemaVersionNode.canConvertToInt()) {
            throw new IllegalArgumentException("Persisted flow document is missing a valid schemaVersion");
        }

        int schemaVersion = schemaVersionNode.intValue();
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported persisted flow schema version: " + schemaVersion);
        }

        JsonNode flowsNode = root.path("flows");
        if (!(flowsNode instanceof ArrayNode)) {
            throw new IllegalArgumentException("Persisted flow document must contain a flows array");
        }

        return List.copyOf(objectMapper.convertValue(flowsNode, flowListType));
    }

    ObjectNode wrapWithCurrentVersion(JsonNode flowsNode) {
        ArrayNode normalizedFlows = flowsNode instanceof ArrayNode arrayNode
                ? arrayNode.deepCopy()
                : objectMapper.createArrayNode();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", CURRENT_SCHEMA_VERSION);
        root.set("flows", normalizedFlows);
        return root;
    }

    ObjectMapper objectMapper() {
        return objectMapper;
    }

    String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize persisted flows", exception);
        }
    }

    JsonNode readJson(String serializedDocument) {
        try {
            return objectMapper.readTree(serializedDocument);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to parse persisted flows", exception);
        }
    }
}
