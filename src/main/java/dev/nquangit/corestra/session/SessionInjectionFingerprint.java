package dev.nquangit.corestra.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Compares runtime injection snapshots without retaining authentication values in the handoff/context. */
public final class SessionInjectionFingerprint {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private SessionInjectionFingerprint() { }
    /** Hashes profile/actor epochs and the complete ordered injection plan. */
    public static String of(SessionInjectionPlan plan) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(MAPPER.writeValueAsBytes(plan)));
        } catch (Exception exception) { throw new SessionBindingException("Could not verify the session snapshot"); }
    }
}
