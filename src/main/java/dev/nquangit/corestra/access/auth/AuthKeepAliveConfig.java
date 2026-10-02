package dev.nquangit.corestra.access.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Optional flow and independent rules for maintaining an existing actor session. */
public record AuthKeepAliveConfig(String flowId, List<AuthSuccessRule> successRules,
                                  List<AuthFailureRule> expiredRules) {
    /** Creates immutable configuration; a blank flow disables keep-alive. */
    @JsonCreator
    public AuthKeepAliveConfig(@JsonProperty("flowId") String flowId,
                              @JsonProperty("successRules") List<AuthSuccessRule> successRules,
                              @JsonProperty("expiredRules") List<AuthFailureRule> expiredRules) {
        this.flowId = flowId == null ? "" : flowId.strip();
        this.successRules = List.copyOf(successRules == null ? List.of() : successRules);
        this.expiredRules = List.copyOf(expiredRules == null ? List.of() : expiredRules);
    }

    /** Returns disabled configuration for providers created before keep-alive support. */
    public static AuthKeepAliveConfig disabled() {
        return new AuthKeepAliveConfig("", List.of(), List.of());
    }

    /** Returns whether a keep-alive flow has been selected. */
    public boolean enabled() {
        return !flowId.isBlank();
    }
}
