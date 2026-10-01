package dev.nquangit.corestra.session;

import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.nquangit.corestra.config.CorestraSettingsKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Stores session profiles in Burp project persistence or an in-memory test store.
 */
public final class SessionProfileRepository {
    private final PersistedObject extensionData;
    private final ObjectMapper objectMapper;
    private List<SessionProfile> inMemoryProfiles;

    /**
     * Creates an in-memory repository.
     */
    public SessionProfileRepository() {
        this.extensionData = null;
        this.objectMapper = mapper();
        this.inMemoryProfiles = SessionProfilesDocument.defaults().profiles();
    }

    /**
     * Creates a Burp project-backed repository.
     *
     * @param persistence Montoya persistence facade
     */
    public SessionProfileRepository(Persistence persistence) {
        this.extensionData = Objects.requireNonNull(persistence, "persistence").extensionData();
        this.objectMapper = mapper();
        this.inMemoryProfiles = SessionProfilesDocument.defaults().profiles();
    }

    /**
     * Loads profiles, always including the General profile.
     *
     * @return immutable profiles
     */
    public synchronized List<SessionProfile> loadProfiles() {
        if (extensionData == null) {
            return inMemoryProfiles;
        }
        String json = extensionData.getString(CorestraSettingsKeys.SESSION_PROFILES_DOCUMENT);
        if (json == null || json.isBlank()) {
            return SessionProfilesDocument.defaults().profiles();
        }
        try {
            SessionProfilesDocument document = objectMapper.readValue(json, SessionProfilesDocument.class);
            return document.profiles();
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            quarantineInvalidDocument(json);
            return SessionProfilesDocument.defaults().profiles();
        }
    }

    /**
     * Finds one profile by id.
     *
     * @param id profile id
     * @return matching profile
     */
    public synchronized Optional<SessionProfile> profile(String id) {
        String normalizedId = normalizeRequiredText(id, "id");
        return loadProfiles().stream().filter(profile -> profile.id().equals(normalizedId)).findFirst();
    }

    /**
     * Saves all profiles.
     *
     * @param profiles profiles to save
     */
    public synchronized void saveProfiles(List<SessionProfile> profiles) {
        List<SessionProfile> normalized = SessionProfilesDocument.ensureGeneral(profiles);
        if (extensionData == null) {
            inMemoryProfiles = normalized;
            return;
        }
        try {
            extensionData.setString(CorestraSettingsKeys.SESSION_PROFILES_DOCUMENT,
                    objectMapper.writeValueAsString(new SessionProfilesDocument(normalized)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize session profiles", exception);
        }
    }

    /**
     * Saves one profile.
     *
     * @param profile profile to save
     * @return saved profile
     */
    public synchronized SessionProfile saveProfile(SessionProfile profile) {
        SessionProfile checked = Objects.requireNonNull(profile, "profile");
        ArrayList<SessionProfile> updated = new ArrayList<>();
        boolean replaced = false;
        for (SessionProfile existing : loadProfiles()) {
            if (existing.id().equals(checked.id())) {
                updated.add(checked);
                replaced = true;
            } else {
                updated.add(existing);
            }
        }
        if (!replaced) {
            updated.add(checked);
        }
        saveProfiles(updated);
        return checked;
    }

    /**
     * Deletes a non-system profile.
     *
     * @param id profile id
     * @return true when deleted
     */
    public synchronized boolean deleteProfile(String id) {
        String normalizedId = normalizeRequiredText(id, "id");
        if (SessionProfile.GENERAL_ID.equals(normalizedId)) {
            return false;
        }
        ArrayList<SessionProfile> updated = new ArrayList<>();
        boolean deleted = false;
        for (SessionProfile profile : loadProfiles()) {
            if (profile.id().equals(normalizedId)) {
                deleted = true;
            } else {
                updated.add(profile);
            }
        }
        if (deleted) {
            saveProfiles(updated);
        }
        return deleted;
    }

    /**
     * Atomically updates only the state of an existing profile, preserving its latest configuration.
     *
     * @param id profile id
     * @param update state transformation
     * @return updated profile, or empty if the profile was deleted
     */
    public synchronized Optional<SessionProfile> updateState(String id, UnaryOperator<SessionState> update) {
        Objects.requireNonNull(update, "update");
        return profile(id).map(current -> saveProfile(current.withState(update.apply(current.state()))));
    }

    /** Removes all persisted profiles and restores the in-memory default General profile. */
    public synchronized void clear() {
        inMemoryProfiles = SessionProfilesDocument.defaults().profiles();
        if (extensionData != null) {
            extensionData.deleteString(CorestraSettingsKeys.SESSION_PROFILES_DOCUMENT);
            extensionData.deleteString(CorestraSettingsKeys.SESSION_PROFILES_DOCUMENT_QUARANTINE);
        }
    }

    private void quarantineInvalidDocument(String serializedDocument) {
        try {
            extensionData.setString(CorestraSettingsKeys.SESSION_PROFILES_DOCUMENT_QUARANTINE, serializedDocument);
        } catch (RuntimeException ignored) {
            // Recovery still clears the primary document.
        } finally {
            extensionData.deleteString(CorestraSettingsKeys.SESSION_PROFILES_DOCUMENT);
        }
    }

    private static ObjectMapper mapper() {
        return JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}
