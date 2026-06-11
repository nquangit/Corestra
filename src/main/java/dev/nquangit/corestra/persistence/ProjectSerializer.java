package dev.nquangit.corestra.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nquangit.corestra.access.persistence.AccessWorkspaceConfiguration;
import dev.nquangit.corestra.access.persistence.AccessWorkspaceDocument;
import dev.nquangit.corestra.access.persistence.AccessWorkspaceSerializer;
import dev.nquangit.corestra.access.security.ExportMode;
import dev.nquangit.corestra.flow.FlowDefinition;

import java.util.List;
import java.util.Objects;

/**
 * Serializes and deserializes Corestra project import/export documents.
 */
public final class ProjectSerializer {
    private static final int CURRENT_SCHEMA_VERSION = 1;

    private final FlowSerializer flowSerializer;
    private final AccessWorkspaceSerializer accessWorkspaceSerializer;
    private final ObjectMapper objectMapper;

    /**
     * Creates a project serializer.
     *
     * @param flowSerializer flow serializer used for flow payloads
     */
    public ProjectSerializer(FlowSerializer flowSerializer) {
        this(flowSerializer, new AccessWorkspaceSerializer());
    }

    /**
     * Creates a project serializer.
     *
     * @param flowSerializer flow serializer used for flow payloads
     * @param accessWorkspaceSerializer access workspace serializer used for access payloads
     */
    public ProjectSerializer(
            FlowSerializer flowSerializer,
            AccessWorkspaceSerializer accessWorkspaceSerializer) {
        this.flowSerializer = Objects.requireNonNull(flowSerializer, "flowSerializer");
        this.accessWorkspaceSerializer = Objects.requireNonNull(accessWorkspaceSerializer, "accessWorkspaceSerializer");
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Serializes a project using the default safe file export mode.
     *
     * @param doc project document
     * @return serialized project JSON
     */
    public String serializeProject(ProjectDocument doc) {
        return serializeProject(doc, ExportMode.SANITIZED_EXPORT);
    }

    /**
     * Serializes a project using the supplied access export mode.
     *
     * @param doc project document
     * @param exportMode access export mode
     * @return serialized project JSON
     */
    public String serializeProject(ProjectDocument doc, ExportMode exportMode) {
        Objects.requireNonNull(doc, "doc");
        Objects.requireNonNull(exportMode, "exportMode");
        ObjectNode root = objectMapper.createObjectNode();
        root.put("schemaVersion", CURRENT_SCHEMA_VERSION);
        root.set("metadata", objectMapper.valueToTree(doc.metadata()));
        root.set("flows", flowSerializer.objectMapper().valueToTree(doc.flows()));
        root.set("access", accessWorkspaceSerializer.serialize(
                AccessWorkspaceConfiguration.projectState(doc.accessDocument()), exportMode));
        return writeJson(root);
    }

    /**
     * Deserializes a project import/export document.
     *
     * @param json serialized project JSON
     * @return deserialized project document
     */
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
            AccessWorkspaceDocument accessDocument = accessWorkspaceSerializer.deserialize(root.get("access"));
            return new ProjectDocument(CURRENT_SCHEMA_VERSION, metadata, flows,
                    AccessWorkspaceConfiguration.projectState(accessDocument));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse project document: " + e.getMessage(), e);
        }
    }

    /**
     * Serializes selected flows as a flow-only document.
     *
     * @param flows flow definitions
     * @return serialized flow JSON
     */
    public String serializeFlows(List<FlowDefinition> flows) {
        Objects.requireNonNull(flows, "flows");
        return flowSerializer.serialize(flows);
    }

    /**
     * Deserializes flows from a flow-only or project document.
     *
     * @param json serialized flow or project JSON
     * @return flow definitions
     */
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
