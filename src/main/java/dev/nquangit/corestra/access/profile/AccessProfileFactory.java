package dev.nquangit.corestra.access.profile;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.id.AccessIdGenerator;
import dev.nquangit.corestra.access.id.UuidAccessIdGenerator;

import java.util.Map;
import java.util.Objects;

/**
 * Creates access-testing auth and data profiles for future UI workflows.
 */
public final class AccessProfileFactory {
    private static final String AUTH_PREFIX = "auth";
    private static final String DATA_PREFIX = "data";

    private final AccessIdGenerator idGenerator;

    /**
     * Creates a profile factory with UUID-backed IDs.
     */
    public AccessProfileFactory() {
        this(new UuidAccessIdGenerator());
    }

    /**
     * Creates a profile factory.
     *
     * @param idGenerator identifier generator
     */
    public AccessProfileFactory(AccessIdGenerator idGenerator) {
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    /**
     * Creates an enabled manual auth profile.
     *
     * @param name display name
     * @param role optional role label
     * @param variables auth variables
     * @param headers manual headers
     * @param cookies manual cookies
     * @return enabled manual auth profile
     */
    public AuthProfile manualAuthProfile(
            String name,
            String role,
            Map<String, String> variables,
            Map<String, String> headers,
            Map<String, String> cookies) {
        AuthState state = new AuthState(
                emptyIfNull(variables),
                emptyIfNull(headers),
                emptyIfNull(cookies),
                0L,
                0L);
        return new AuthProfile(idGenerator.newId(AUTH_PREFIX), name, role, "manual", Map.of(), state, true);
    }

    /**
     * Creates an enabled data profile.
     *
     * @param name display name
     * @param ownerAuthProfileId owner auth profile identifier
     * @param values data values
     * @return enabled data profile
     */
    public DataProfile dataProfile(
            String name,
            String ownerAuthProfileId,
            Map<String, String> values) {
        return new DataProfile(idGenerator.newId(DATA_PREFIX), name, ownerAuthProfileId, emptyIfNull(values), true);
    }

    private static Map<String, String> emptyIfNull(Map<String, String> values) {
        return Objects.requireNonNullElse(values, Map.of());
    }
}
