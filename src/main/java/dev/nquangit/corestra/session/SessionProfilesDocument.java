package dev.nquangit.corestra.session;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Project-persisted session profile document.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class SessionProfilesDocument {
    /**
     * Current session document schema version.
     */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private final int schemaVersion;
    private final List<SessionProfile> profiles;

    /**
     * Creates a session profiles document.
     *
     * @param schemaVersion schema version
     * @param profiles session profiles
     */
    @JsonCreator
    public SessionProfilesDocument(
            @JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("profiles") List<SessionProfile> profiles) {
        this.schemaVersion = schemaVersion == 0 ? CURRENT_SCHEMA_VERSION : schemaVersion;
        this.profiles = ensureGeneral(profiles);
    }

    /**
     * Creates a current-version document from profiles.
     *
     * @param profiles session profiles
     */
    public SessionProfilesDocument(List<SessionProfile> profiles) {
        this(CURRENT_SCHEMA_VERSION, profiles);
    }

    /**
     * Returns an empty document containing the General profile.
     *
     * @return default document
     */
    public static SessionProfilesDocument defaults() {
        return new SessionProfilesDocument(List.of(SessionProfile.general()));
    }

    /**
     * Returns the schema version.
     *
     * @return schema version
     */
    public int schemaVersion() {
        return schemaVersion;
    }

    /**
     * Returns immutable profiles with General included.
     *
     * @return profiles
     */
    public List<SessionProfile> profiles() {
        return profiles;
    }

    /**
     * Ensures the General profile exists exactly once.
     *
     * @param source source profiles
     * @return immutable profile list
     */
    public static List<SessionProfile> ensureGeneral(List<SessionProfile> source) {
        ArrayList<SessionProfile> result = new ArrayList<>();
        SessionProfile general = null;
        if (source != null) {
            for (SessionProfile profile : source) {
                SessionProfile checked = Objects.requireNonNull(profile, "profile");
                if (checked.isGeneral()) {
                    if (general == null) {
                        general = new SessionProfile(
                                SessionProfile.GENERAL_ID,
                                "General",
                                checked.enabled(),
                                true,
                                checked.scope(),
                                checked.authConfig(),
                                checked.scheduler(),
                                checked.state(),
                                checked.injectionRules(),
                                checked.extractionRules());
                    }
                } else {
                    result.add(checked);
                }
            }
        }
        result.add(0, general == null ? SessionProfile.general() : general);
        return List.copyOf(result);
    }
}
