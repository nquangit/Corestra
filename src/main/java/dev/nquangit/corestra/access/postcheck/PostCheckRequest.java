package dev.nquangit.corestra.access.postcheck;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Describes a request to run after the target request to verify side effects.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class PostCheckRequest {
    private final String id;
    private final String name;
    private final boolean enabled;
    private final String templateId;
    private final String authProfileId;
    private final PostCheckAuthBinding authBinding;
    private final String dataProfileId;
    private final String scenarioInstanceId;
    private final List<PostCheckAssertion> assertions;

    /**
     * Creates a post-check request.
     *
     * @param id stable post-check id
     * @param name display name
     * @param enabled whether this post-check is enabled
     * @param templateId access request template id to render and send
     * @param authProfileId fixed auth profile id when auth binding is FIXED_PROFILE
     * @param authBinding auth profile binding strategy
     * @param dataProfileId optional data profile override
     * @param scenarioInstanceId optional scenario instance id
     * @param assertions assertions to evaluate against the response
     */
    @JsonCreator
    public PostCheckRequest(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("templateId") String templateId,
            @JsonProperty("authProfileId") String authProfileId,
            @JsonProperty("authBinding") PostCheckAuthBinding authBinding,
            @JsonProperty("dataProfileId") String dataProfileId,
            @JsonProperty("scenarioInstanceId") String scenarioInstanceId,
            @JsonProperty("assertions") List<PostCheckAssertion> assertions) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.enabled = enabled;
        this.templateId = normalizeRequiredText(templateId, "templateId");
        this.authProfileId = normalizeOptionalText(authProfileId);
        this.authBinding = Objects.requireNonNullElse(authBinding, PostCheckAuthBinding.TARGET_ACTOR);
        this.dataProfileId = normalizeOptionalText(dataProfileId);
        this.scenarioInstanceId = normalizeOptionalText(scenarioInstanceId);
        this.assertions = copyAssertions(assertions);
    }

    /**
     * Returns the stable post-check id.
     *
     * @return post-check id
     */
    public String id() {
        return id;
    }

    /**
     * Returns the display name.
     *
     * @return display name
     */
    public String name() {
        return name;
    }

    /**
     * Returns whether this post-check is enabled.
     *
     * @return true when enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the template id.
     *
     * @return template id
     */
    public String templateId() {
        return templateId;
    }

    /**
     * Returns the fixed auth profile id.
     *
     * @return auth profile id, or an empty string
     */
    public String authProfileId() {
        return authProfileId;
    }

    /**
     * Returns the auth binding.
     *
     * @return auth binding
     */
    public PostCheckAuthBinding authBinding() {
        return authBinding;
    }

    /**
     * Returns the data profile override id.
     *
     * @return data profile id, or an empty string
     */
    public String dataProfileId() {
        return dataProfileId;
    }

    /**
     * Returns the scenario instance id.
     *
     * @return scenario instance id, or an empty string
     */
    public String scenarioInstanceId() {
        return scenarioInstanceId;
    }

    /**
     * Returns configured assertions.
     *
     * @return immutable assertions
     */
    public List<PostCheckAssertion> assertions() {
        return assertions;
    }

    /**
     * Compares this request to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PostCheckRequest that)) {
            return false;
        }
        return enabled == that.enabled
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && Objects.equals(templateId, that.templateId)
                && Objects.equals(authProfileId, that.authProfileId)
                && authBinding == that.authBinding
                && Objects.equals(dataProfileId, that.dataProfileId)
                && Objects.equals(scenarioInstanceId, that.scenarioInstanceId)
                && Objects.equals(assertions, that.assertions);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, enabled, templateId, authProfileId, authBinding,
                dataProfileId, scenarioInstanceId, assertions);
    }

    /**
     * Returns a diagnostic string.
     *
     * @return diagnostic string
     */
    @Override
    public String toString() {
        return "PostCheckRequest{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", enabled=" + enabled +
                ", templateId='" + templateId + '\'' +
                ", authProfileId='" + authProfileId + '\'' +
                ", authBinding=" + authBinding +
                ", dataProfileId='" + dataProfileId + '\'' +
                ", scenarioInstanceId='" + scenarioInstanceId + '\'' +
                ", assertions=" + assertions +
                '}';
    }

    private static List<PostCheckAssertion> copyAssertions(List<PostCheckAssertion> assertions) {
        if (assertions == null || assertions.isEmpty()) {
            return List.of();
        }
        ArrayList<PostCheckAssertion> copied = new ArrayList<>();
        assertions.forEach(assertion -> copied.add(Objects.requireNonNull(assertion, "assertion")));
        return List.copyOf(copied);
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }

    private static String normalizeOptionalText(String value) {
        return value == null ? "" : value.strip();
    }
}
