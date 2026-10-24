package dev.nquangit.corestra.manual;

import dev.nquangit.corestra.session.SessionExecutionBinding;
import java.util.List;

/** Explicit session behavior and logical Burp tool used by one manual execution. */
public record ManualRunOptions(Mode mode, String profileId, String tool, List<String> additionalOrigins) {
    /** Session Handler scope, explicit choice, fixed profile, or deliberate unauthenticated testing. */
    public enum Mode { SCOPED, ASK, FIXED, NONE }
    /** Validates options before any request can run. */
    public ManualRunOptions {
        if (mode == null || !List.of("REPEATER", "PROXY", "SCANNER", "INTRUDER").contains(tool)) throw new IllegalArgumentException("Invalid manual tool or session mode");
        profileId = profileId == null ? "" : profileId;
        if (mode == Mode.FIXED && profileId.isBlank()) throw new IllegalArgumentException("Choose a Session Profile");
        additionalOrigins = additionalOrigins == null ? List.of() : additionalOrigins.stream().map(SessionExecutionBinding::normalizeOrigin).distinct().toList();
    }
    /** Uses normal project scope/selection with Repeater rule filters. */
    public static ManualRunOptions defaults() { return new ManualRunOptions(Mode.SCOPED, "", "REPEATER", List.of()); }
}
