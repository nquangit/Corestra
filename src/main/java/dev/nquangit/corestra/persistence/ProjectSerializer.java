package dev.nquangit.corestra.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nquangit.corestra.flow.FlowDefinition;

import java.util.List;
import java.util.Objects;

public final class ProjectSerializer {
    private static final int CURRENT_SCHEMA_VERSION = 1;

    private final FlowSerializer flowSerializer;
    private final ObjectMapper objectMapper;

    public ProjectSerializer(FlowSerializer flowSerializer) {
        this.flowSerializer = Objects.requireNonNull(flowSerializer, "flowSerializer");
        this.objectMapper = new ObjectMapper();
    }

    public String serializeProject(ProjectDocument doc) {
        Objects.requireNonNull(doc, "doc");
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", CURRENT_SCHEMA_VERSION);
        root.set("metadata", objectMapper.valueToTree(doc.metadata()));
        root.set("flows", flowSerializer.objectMapper().valueToTree(doc.flows()));
        return writeJson(root);
    }

    public ProjectDocument deserializeProject(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Project document must not be blank");
        }
        JsonNode root = readJson(json);
        validateSchemaVersion(root);

        JsonNode metaNode = root.get("metadata");
        if (metaNode == null || metaNode.isNull()) {
            throw new IllegalArgumentException("Project document is missing metadata");
        }
        JsonNode flowsNode = root.path("flows");
        if (!(flowsNode instanceof ArrayNode)) {
            throw new IllegalArgumentException("Project document is missing flows array");
        }

        try {
            ProjectMetadata metadata = objectMapper.treeToValue(metaNode, ProjectMetadata.class);
            List<FlowDefinition> flows = deserializeFlowsNode(flowsNode);
            return new ProjectDocument(CURRENT_SCHEMA_VERSION, metadata, flows);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse project document: " + e.getMessage(), e);
        }
    }

    public String serializeFlows(List<FlowDefinition> flows) {
        Objects.requireNonNull(flows, "flows");
        return flowSerializer.serialize(flows);
    }

    public List<FlowDefinition> deserializeFlows(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Flows document must not be blank");
        }
        JsonNode root = readJson(json);
        JsonNode flowsNode = root.path("flows");
        if (!(flowsNode instanceof ArrayNode)) {
            throw new IllegalArgumentException("Document is missing flows array");
        }
        return deserializeFlowsNode(flowsNode);
    }

    private List<FlowDefinition> deserializeFlowsNode(JsonNode flowsNode) {
        ObjectNode wrapped = flowSerializer.wrapWithCurrentVersion(flowsNode);
        return flowSerializer.deserialize(flowSerializer.writeJson(wrapped));
    }

    private void validateSchemaVersion(JsonNode root) {
        JsonNode versionNode = root.get("schemaVersion");
        if (versionNode == null || !versionNode.canConvertToInt()) {
            throw new IllegalArgumentException("Document is missing a valid schemaVersion");
        }
        if (versionNode.intValue() != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported schema version: " + versionNode.intValue());
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize document", e);
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse document: " + e.getMessage(), e);
        }
    }
}
