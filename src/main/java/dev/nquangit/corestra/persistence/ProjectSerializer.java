package dev.nquangit.corestra.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.nquangit.corestra.access.persistence.AccessWorkspaceConfiguration;
import dev.nquangit.corestra.access.persistence.AccessWorkspaceDocument;
import dev.nquangit.corestra.access.persistence.AccessWorkspaceSerializer;
import dev.nquangit.corestra.access.security.ExportMode;
import dev.nquangit.corestra.access.security.RedactionService;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.session.SessionInjectionRule;
import dev.nquangit.corestra.session.SessionProfile;
import dev.nquangit.corestra.session.SessionProfilesDocument;
import dev.nquangit.corestra.session.SessionState;

import dev.nquangit.corestra.watch.WatchDefinition;
import dev.nquangit.corestra.watch.WatchProbe;
import dev.nquangit.corestra.watch.WatchRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Serializes and deserializes Corestra project import/export documents.
 */
public final class ProjectSerializer {
    private static final int CURRENT_SCHEMA_VERSION = 1;

    private final FlowSerializer flowSerializer;
    private final AccessWorkspaceSerializer accessWorkspaceSerializer;
    private final FlowExportSanitizer flowExportSanitizer;
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
        this.flowExportSanitizer = new FlowExportSanitizer(new RedactionService());
        this.objectMapper = JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();
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
        root.set("settings", objectMapper.valueToTree(doc.settings()));
        root.set("flows", flowSerializer.objectMapper().valueToTree(flowsForMode(doc.flows(), exportMode)));
        root.set("manualFlows", flowSerializer.objectMapper().valueToTree(flowsForMode(doc.manualFlows(), exportMode)));
        root.set("access", accessWorkspaceSerializer.serialize(
                AccessWorkspaceConfiguration.projectState(doc.accessDocument()), exportMode));
        root.set("sessions", objectMapper.valueToTree(
                new SessionProfilesDocument(sessionProfilesForMode(doc.sessionProfiles(), exportMode))));
        root.set("watches", objectMapper.valueToTree(watchesForMode(doc.watches(), exportMode)));
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
            ProjectSettings settings = deserializeSettings(root.get("settings"));
            List<FlowDefinition> flows = deserializeFlowsNode(flowsNode);
            AccessWorkspaceDocument accessDocument = accessWorkspaceSerializer.deserialize(root.get("access"));
            AccessWorkspaceDocument accessProjectState = AccessWorkspaceConfiguration.projectState(accessDocument);
            List<SessionProfile> sessionProfiles = deserializeSessionProfiles(root.get("sessions"));
            if (root.has("manualFlows") && !root.get("manualFlows").isArray()) throw new IllegalArgumentException("manualFlows must be an array");
            List<FlowDefinition> manualFlows = root.has("manualFlows") ? deserializeFlowsNode(root.get("manualFlows")) : List.of();
            if (manualFlows.stream().map(FlowDefinition::id).distinct().count() != manualFlows.size()) throw new IllegalArgumentException("Duplicate manual draft IDs");
            return new ProjectDocument(CURRENT_SCHEMA_VERSION, metadata, settings, flows,
                    accessProjectState.workspace(),
                    accessProjectState.evidence(),
                    accessProjectState.latestSummary().orElse(null),
                    accessProjectState.reportDrafts(),
                    sessionProfiles).withManualFlows(manualFlows).withWatches(deserializeWatches(root.get("watches")));
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

    private ProjectSettings deserializeSettings(JsonNode settingsNode) {
        if (settingsNode == null || settingsNode.isMissingNode() || settingsNode.isNull()) {
            return ProjectSettings.defaults();
        }
        if (!settingsNode.isObject()) {
            return ProjectSettings.defaults();
        }
        try {
            return objectMapper.treeToValue(settingsNode, ProjectSettings.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return ProjectSettings.defaults();
        }
    }

    private List<SessionProfile> deserializeSessionProfiles(JsonNode sessionsNode) {
        if (sessionsNode == null || sessionsNode.isMissingNode() || sessionsNode.isNull()) {
            return SessionProfilesDocument.defaults().profiles();
        }
        if (!sessionsNode.isObject()) {
            return SessionProfilesDocument.defaults().profiles();
        }
        try {
            return objectMapper.treeToValue(sessionsNode, SessionProfilesDocument.class).profiles();
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return SessionProfilesDocument.defaults().profiles();
        }
    }

    private List<SessionProfile> sessionProfilesForMode(List<SessionProfile> profiles, ExportMode exportMode) {
        return switch (exportMode) {
            case FULL_EXPORT -> SessionProfilesDocument.ensureGeneral(profiles);
            case SANITIZED_EXPORT -> transformSessionProfiles(profiles, false);
            case EXCLUDE_SECRETS -> transformSessionProfiles(profiles, true);
            case ENCRYPTED_EXPORT -> throw new IllegalArgumentException("Encrypted access export is not implemented");
        };
    }

    private List<SessionProfile> transformSessionProfiles(List<SessionProfile> profiles, boolean excludeSecrets) {
        return SessionProfilesDocument.ensureGeneral(profiles).stream()
                .map(profile -> profile.withConfig(
                        profile.name(), profile.scope(), profile.authConfig(), profile.scheduler(),
                        profile.injectionRules().stream()
                                .map(rule -> transformSessionInjectionRule(rule, excludeSecrets)).toList(),
                        profile.extractionRules())
                        .withState(transformSessionState(profile.state(), excludeSecrets)))
                .toList();
    }

    private List<WatchDefinition> deserializeWatches(JsonNode watchesNode) {
        if (watchesNode == null || watchesNode.isMissingNode() || watchesNode.isNull()) {
            return List.of();
        }
        if (!watchesNode.isArray()) {
            throw new IllegalArgumentException("watches must be an array");
        }
        List<String> problems = new ArrayList<>();
        List<WatchDefinition> watches = WatchRepository.readWatches(watchesNode, problems);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Invalid watches: " + String.join(" ", problems));
        }
        return watches;
    }

    private List<WatchDefinition> watchesForMode(List<WatchDefinition> watches, ExportMode exportMode) {
        return switch (exportMode) {
            case FULL_EXPORT -> List.copyOf(watches);
            case SANITIZED_EXPORT -> watches.stream().map(watch -> sanitizeWatch(watch, false)).toList();
            case EXCLUDE_SECRETS -> watches.stream().map(watch -> sanitizeWatch(watch, true)).toList();
            case ENCRYPTED_EXPORT -> throw new IllegalArgumentException("Encrypted access export is not implemented");
        };
    }

    private WatchDefinition sanitizeWatch(WatchDefinition watch, boolean excludeSecrets) {
        WatchProbe probe = watch.probe();
        if (!probe.configured()) {
            return watch;
        }
        return watch.withProbe(new WatchProbe(
                flowExportSanitizer.sanitizeRequest(probe.request(), excludeSecrets), probe.inheritHeaders()));
    }

    private List<FlowDefinition> flowsForMode(List<FlowDefinition> flows, ExportMode exportMode) {
        return switch (exportMode) {
            case FULL_EXPORT -> List.copyOf(flows);
            case SANITIZED_EXPORT -> flows.stream()
                    .map(flow -> flowExportSanitizer.sanitizeFlow(flow, false)).toList();
            case EXCLUDE_SECRETS -> flows.stream()
                    .map(flow -> flowExportSanitizer.sanitizeFlow(flow, true)).toList();
            case ENCRYPTED_EXPORT -> throw new IllegalArgumentException("Encrypted access export is not implemented");
        };
    }

    private SessionInjectionRule transformSessionInjectionRule(
            SessionInjectionRule rule, boolean excludeSecrets) {
        return new SessionInjectionRule(
                rule.id(), rule.enabled(), rule.target(), rule.targetSelector(),
                flowExportSanitizer.sanitizeConfiguredValue(
                        rule.targetSelector(), rule.valueTemplate(), excludeSecrets, true),
                rule.writeStrategy(), rule.allowedToolTypes(),
                rule.transformChain());
    }

    private SessionState transformSessionState(SessionState state, boolean excludeSecrets) {
        Map<String, String> transformed = excludeSecrets ? Map.of() : redactAllSessionValues(state.values());
        return new SessionState(
                transformed,
                state.lastAuthenticatedAtEpochMillis(),
                state.expiresAtEpochMillis(),
                state.lastUpdatedAtEpochMillis());
    }

    private Map<String, String> redactAllSessionValues(Map<String, String> values) {
        if (values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> redacted = new LinkedHashMap<>();
        values.forEach((key, value) -> redacted.put(key, "<redacted>"));
        return Map.copyOf(redacted);
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
