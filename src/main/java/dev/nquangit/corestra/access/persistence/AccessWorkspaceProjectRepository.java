package dev.nquangit.corestra.access.persistence;

import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.access.security.ExportMode;
import dev.nquangit.corestra.config.CorestraSettingsKeys;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Persists Access Testing project state in Burp project-scoped extension data.
 */
public final class AccessWorkspaceProjectRepository {
    private final PersistedObject extensionData;
    private final AccessWorkspaceSerializer serializer;
    private final ObjectMapper objectMapper;
    private AccessWorkspaceDocument lastSaved;
    private String revision;

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
    public synchronized AccessWorkspaceDocument load() {
        String serializedDocument = extensionData.getString(CorestraSettingsKeys.ACCESS_WORKSPACE_DOCUMENT);
        if (serializedDocument == null || serializedDocument.isBlank()) {
            revision = null;
            return lastSaved = AccessWorkspaceDocument.empty();
        }

        try {
            JsonNode node = objectMapper.readTree(serializedDocument);
            AccessWorkspaceDocument document = serializer.deserialize(node);
            revision = node.path("persistenceRevision").asText(null);
            AccessWorkspaceDocument base = AccessWorkspaceConfiguration.projectState(document);
            return lastSaved = loadCheckpoint(base);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            quarantineInvalidDocument(serializedDocument);
            revision = null;
            return lastSaved = AccessWorkspaceDocument.empty();
        }
    }

    private AccessWorkspaceDocument loadCheckpoint(AccessWorkspaceDocument base) {
        String stored = extensionData.getString(CorestraSettingsKeys.ACCESS_AUTH_PROFILES_CHECKPOINT);
        if (stored == null || stored.isBlank()) return base;
        try {
            JsonNode node = objectMapper.readTree(stored);
            if (revision == null || !revision.equals(node.path("persistenceRevision").asText())) return base;
            if (!node.path("authProfiles").isObject()) {
                throw new IllegalArgumentException("Checkpoint authProfiles must be an object");
            }
            var profiles = serializer.deserialize(node).workspace().authProfiles();
            AccessWorkspace updated = base.workspace().toBuilder().authProfiles(profiles).build();
            if (!sameProfileConfiguration(base.workspace(), updated)) {
                throw new IllegalArgumentException("Checkpoint must preserve auth profile configuration");
            }
            return new AccessWorkspaceDocument(updated,
                    base.evidence(), base.latestSummary().orElse(null), base.reportDrafts());
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            try {
                extensionData.setString(CorestraSettingsKeys.ACCESS_AUTH_PROFILES_CHECKPOINT_QUARANTINE, stored);
            } catch (RuntimeException ignored) {
                // Keep the valid base document usable even if quarantine cannot be written.
            } finally {
                extensionData.deleteString(CorestraSettingsKeys.ACCESS_AUTH_PROFILES_CHECKPOINT);
            }
            return base;
        }
    }

    /**
     * Persists Access Testing project state and omits runtime scenarios.
     *
     * @param document source access project document
     */
    public synchronized void save(AccessWorkspaceDocument document) {
        AccessWorkspaceDocument projectState = AccessWorkspaceConfiguration.projectState(document);
        if (!AccessWorkspaceConfiguration.hasProjectState(projectState)) {
            clear();
            return;
        }

        if (lastSaved != null && sameNonProfileState(lastSaved, projectState)) {
            if (lastSaved.workspace().authProfiles().equals(projectState.workspace().authProfiles())) return;
            if (revision != null && sameProfileConfiguration(lastSaved.workspace(), projectState.workspace())) {
                AccessWorkspace profiles = AccessWorkspace.builder()
                        .authProfiles(projectState.workspace().authProfiles()).build();
                ObjectNode checkpoint = serializer.serialize(new AccessWorkspaceDocument(profiles, List.of()), ExportMode.FULL_EXPORT);
                checkpoint.put("persistenceRevision", revision);
                extensionData.setString(CorestraSettingsKeys.ACCESS_AUTH_PROFILES_CHECKPOINT, serializer.writeJson(checkpoint));
                lastSaved = projectState;
                return;
            }
        }
        // Revision fencing makes an old checkpoint harmless after a new base write,
        // including when cleanup fails or Burp exits between the two writes.
        String nextRevision = UUID.randomUUID().toString();
        ObjectNode base = serializer.serialize(projectState, ExportMode.FULL_EXPORT);
        base.put("persistenceRevision", nextRevision);
        extensionData.setString(CorestraSettingsKeys.ACCESS_WORKSPACE_DOCUMENT, serializer.writeJson(base));
        revision = nextRevision;
        lastSaved = projectState;
        deleteCheckpointIfPresent();
    }

    private static boolean sameProfileConfiguration(AccessWorkspace first, AccessWorkspace second) {
        if (!first.authProfiles().keySet().equals(second.authProfiles().keySet())) return false;
        for (AuthProfile a : first.authProfiles().values()) {
            AuthProfile b = second.authProfiles().get(a.id());
            if (b == null || !a.name().equals(b.name()) || !a.role().equals(b.role())
                    || !a.providerId().equals(b.providerId()) || !a.credentials().equals(b.credentials())
                    || a.enabled() != b.enabled()) return false;
        }
        return true;
    }

    private static boolean sameNonProfileState(AccessWorkspaceDocument first, AccessWorkspaceDocument second) {
        AccessWorkspace a = first.workspace(), b = second.workspace();
        return a.authProviders().equals(b.authProviders()) && a.dataProfiles().equals(b.dataProfiles())
                && a.templates().equals(b.templates()) && a.flowTargets().equals(b.flowTargets())
                && a.testCases().equals(b.testCases()) && a.results().equals(b.results())
                && a.evaluationProfiles().equals(b.evaluationProfiles())
                && first.evidence().equals(second.evidence())
                && first.latestSummary().equals(second.latestSummary())
                && first.reportDrafts().equals(second.reportDrafts());
    }

    private void deleteCheckpointIfPresent() {
        try {
            if (extensionData.getString(CorestraSettingsKeys.ACCESS_AUTH_PROFILES_CHECKPOINT) != null) {
                extensionData.deleteString(CorestraSettingsKeys.ACCESS_AUTH_PROFILES_CHECKPOINT);
            }
        } catch (RuntimeException ignored) {
            // Old checkpoints cannot match the newly committed revision (or an absent base).
        }
    }

    /**
     * Removes persisted Access Testing configuration.
     */
    public synchronized void clear() {
        extensionData.deleteString(CorestraSettingsKeys.ACCESS_WORKSPACE_DOCUMENT);
        revision = null;
        lastSaved = null;
        deleteCheckpointIfPresent();
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
