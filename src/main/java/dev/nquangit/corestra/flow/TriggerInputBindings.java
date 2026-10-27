package dev.nquangit.corestra.flow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Profile references used only when an automatic trigger executes its flow. */
public record TriggerInputBindings(String authProfileId, String dataProfileId, List<String> excludedTargetRuleIds) {
    /** Creates optional references; absent fields in older documents mean None. */
    @JsonCreator
    public TriggerInputBindings(@JsonProperty("authProfileId") String authProfileId,
                                @JsonProperty("dataProfileId") String dataProfileId,
                                @JsonProperty("excludedTargetRuleIds") List<String> excludedTargetRuleIds) {
        this.authProfileId = authProfileId == null ? "" : authProfileId.strip();
        this.dataProfileId = dataProfileId == null ? "" : dataProfileId.strip();
        this.excludedTargetRuleIds = excludedTargetRuleIds == null ? List.of() : excludedTargetRuleIds.stream()
                .map(id -> {
                    if (id == null || id.isBlank()) throw new IllegalArgumentException("Excluded target rule ID must not be blank");
                    return id.strip();
                }).distinct().toList();
    }

    /** Creates profile selections with all target injection rules retained. */
    public TriggerInputBindings(String authProfileId, String dataProfileId) {
        this(authProfileId, dataProfileId, List.of());
    }

    /** Returns bindings without external profiles. */
    public static TriggerInputBindings none() { return new TriggerInputBindings("", ""); }

    /** Whether a profile was explicitly selected. */
    public boolean hasProfiles() { return !authProfileId.isEmpty() || !dataProfileId.isEmpty(); }
}
